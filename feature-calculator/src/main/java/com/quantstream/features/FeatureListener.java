package com.quantstream.features;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantstream.common.features.MicrostructureCalculator;
import com.quantstream.common.model.Features;
import com.quantstream.common.model.OrderBookSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumes order book snapshots, computes microstructure features, and publishes
 * them to the {@code features} topic.
 *
 * <p>Stateless and per-message: each snapshot maps to exactly one feature record.
 * The output is keyed by token (same as the input) so per-token ordering is
 * preserved across the pipeline and all of a token's features land on one partition.
 */
@Component
public class FeatureListener {

    private static final Logger log = LoggerFactory.getLogger(FeatureListener.class);

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafka;
    private final String featuresTopic;

    private final AtomicLong processed = new AtomicLong();

    public FeatureListener(ObjectMapper objectMapper,
                           KafkaTemplate<String, String> kafka,
                           @Value("${quantstream.topic.features:features}") String featuresTopic) {
        this.objectMapper = objectMapper;
        this.kafka = kafka;
        this.featuresTopic = featuresTopic;
    }

    @KafkaListener(
            topics = "${quantstream.topic.order-book:order-book-data}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onOrderBook(String payload) {
        OrderBookSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(payload, OrderBookSnapshot.class);
        } catch (Exception e) {
            log.error("Skipping unparseable order-book message: {}", truncate(payload), e);
            return;
        }

        Features features;
        try {
            features = MicrostructureCalculator.compute(snapshot);
        } catch (IllegalArgumentException e) {
            // Degenerate/one-sided book — skip rather than emit a garbage feature.
            log.warn("Skipping feature computation for {}: {}", snapshot.token(), e.getMessage());
            return;
        }

        try {
            String out = objectMapper.writeValueAsString(features);
            kafka.send(featuresTopic, features.token(), out);
        } catch (Exception e) {
            log.error("Failed to publish features for {}", features.token(), e);
            return;
        }

        long n = processed.incrementAndGet();
        if (n % 50 == 0) {
            log.info("Computed {} feature records (latest {} obi={} microprice={})",
                    n, features.token(),
                    String.format("%.4f", features.obi()),
                    String.format("%.4f", features.microprice()));
        }
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }
}
