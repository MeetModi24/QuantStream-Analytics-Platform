package com.quantstream.strategy.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import com.quantstream.strategy.engine.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumes features, runs them through the {@link StrategyEngine}, and publishes any
 * resulting signals to the {@code signals} topic (keyed by token to preserve order).
 */
@Component
public class FeaturesListener {

    private static final Logger log = LoggerFactory.getLogger(FeaturesListener.class);

    private final ObjectMapper objectMapper;
    private final StrategyEngine engine;
    private final KafkaTemplate<String, String> kafka;
    private final String signalsTopic;

    private final AtomicLong signalsEmitted = new AtomicLong();

    public FeaturesListener(ObjectMapper objectMapper,
                            StrategyEngine engine,
                            KafkaTemplate<String, String> kafka,
                            @Value("${quantstream.topic.signals:signals}") String signalsTopic) {
        this.objectMapper = objectMapper;
        this.engine = engine;
        this.kafka = kafka;
        this.signalsTopic = signalsTopic;
    }

    @KafkaListener(
            topics = "${quantstream.topic.features:features}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onFeatures(String payload) {
        Features features;
        try {
            features = objectMapper.readValue(payload, Features.class);
        } catch (Exception e) {
            log.error("Skipping unparseable features message: {}", truncate(payload), e);
            return;
        }

        List<Signal> signals = engine.onFeatures(features);
        for (Signal signal : signals) {
            publish(signal);
        }
    }

    private void publish(Signal signal) {
        try {
            String out = objectMapper.writeValueAsString(signal);
            kafka.send(signalsTopic, signal.token(), out);
            long n = signalsEmitted.incrementAndGet();
            log.info("Signal #{}: {} {} {} @ {} (conf {}) — {}",
                    n, signal.strategy(), signal.action(), signal.token(),
                    String.format("%.4f", signal.price()),
                    String.format("%.2f", signal.confidence()),
                    signal.reason());
        } catch (Exception e) {
            log.error("Failed to publish signal for {}", signal.token(), e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }
}
