package com.quantstream.aggregator.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantstream.aggregator.portfolio.Portfolio;
import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Feeds the {@link Portfolio} from two streams:
 * <ul>
 *   <li>{@code signals} — each signal is a simulated fill that opens/adjusts/closes a
 *       paper position.
 *   <li>{@code features} — the microprice is captured as the latest mark for that token,
 *       so open positions can be marked to market continuously (not just at signal time).
 * </ul>
 *
 * <p>Deserialization failures are logged and skipped so a poison message cannot stall a
 * partition.
 */
@Component
public class AggregatorListener {

    private static final Logger log = LoggerFactory.getLogger(AggregatorListener.class);

    private final ObjectMapper objectMapper;
    private final Portfolio portfolio;
    private final double notionalPerFill;

    private final AtomicLong fills = new AtomicLong();

    public AggregatorListener(ObjectMapper objectMapper,
                              Portfolio portfolio,
                              @Value("${quantstream.aggregator.notional-per-fill:10000}") double notionalPerFill) {
        this.objectMapper = objectMapper;
        this.portfolio = portfolio;
        this.notionalPerFill = notionalPerFill;
    }

    @KafkaListener(
            topics = "${quantstream.topic.signals:signals}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onSignal(String payload) {
        Signal signal;
        try {
            signal = objectMapper.readValue(payload, Signal.class);
        } catch (Exception e) {
            log.error("Skipping unparseable signal: {}", truncate(payload), e);
            return;
        }
        // Size by notional, not units: lot = $notional / fill-price. A CLOSE flattens the
        // existing position regardless of lot, so its sizing is irrelevant; guard price>0.
        double lot = signal.price() > 0 ? notionalPerFill / signal.price() : 0.0;
        portfolio.applySignal(signal, lot);
        long n = fills.incrementAndGet();
        if (n % 20 == 0) {
            log.info("Applied {} simulated fills (latest {} {} {})",
                    n, signal.strategy(), signal.action(), signal.token());
        }
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
        // Microprice is the mark for open positions (volume-weighted fair value).
        portfolio.updateMark(features.token(), features.microprice());
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }
}
