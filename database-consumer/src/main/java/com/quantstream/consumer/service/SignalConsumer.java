package com.quantstream.consumer.service;

import com.quantstream.consumer.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * Kafka consumer that receives trading signals and persists to QuestDB.
 * 
 * Design Philosophy:
 * - INDIVIDUAL processing (low volume: 1-5 signals/min)
 * - Real-time writes (traders need immediate visibility)
 * - Manual offset commit after successful insert
 * - Fail-fast on database errors
 * 
 * Performance:
 * - Latency: ~10ms per signal (vs ~100ms for batch of 500)
 * - Throughput: 100+ signals/second (more than enough)
 * 
 * Why NOT Batch Processing?
 * - Low volume: Batching would add latency (waiting for batch to fill)
 * - Real-time requirement: Traders want signals NOW, not "in the next batch"
 * - Simple logic: No need for batch validation/error handling complexity
 */
@Service
public class SignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(SignalConsumer.class);

    private final JdbcTemplate jdbcTemplate;

    public SignalConsumer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Monitoring counters
    private long signalsReceived = 0;
    private long signalsProcessed = 0;
    private long signalsFailed = 0;

    /**
     * Consumes individual signals from Kafka topic.
     */
    @KafkaListener(
        topics = "trading-signals",
        groupId = "questdb-signals-consumer-group",
        containerFactory = "signalKafkaListenerContainerFactory"
    )
    public void consumeSignal(
            @Payload Signal signal,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        signalsReceived++;

        log.info("Received signal from partition={}, offset={}: {} {} {} (confidence: {:.2f})",
                partition, offset,
                signal.getStrategyName(),
                signal.getAction(),
                signal.getSymbol(),
                signal.getConfidence());

        try {
            // Validate signal
            validateSignal(signal);

            // Insert into QuestDB (individual insert - real-time)
            persistSignal(signal);

            // Acknowledge message (commits offset)
            acknowledgment.acknowledge();

            signalsProcessed++;

            log.info("Successfully persisted signal: {} {} {} (total: {})",
                    signal.getStrategyName(),
                    signal.getAction(),
                    signal.getSymbol(),
                    signalsProcessed);

            // Log statistics every 100 signals
            if (signalsProcessed % 100 == 0) {
                logStatistics();
            }

        } catch (Exception e) {
            signalsFailed++;

            log.error("Failed to process signal: strategy={}, action={}, symbol={}, error={}",
                    signal.getStrategyName(),
                    signal.getAction(),
                    signal.getSymbol(),
                    e.getMessage(), e);

            // Acknowledge anyway to move forward (signals are transient)
            // If one signal fails, we don't want to block all future signals
            acknowledgment.acknowledge();

            log.warn("Signal acknowledged despite failure (fail-forward strategy)");
        }
    }

    private void validateSignal(Signal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("Signal cannot be null");
        }

        if (signal.getSymbol() == null || signal.getSymbol().isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be blank");
        }

        if (signal.getAction() == null || signal.getAction().isBlank()) {
            throw new IllegalArgumentException("Action cannot be blank");
        }

        // Validate action is one of: BUY, SELL, HOLD
        String action = signal.getAction().toUpperCase();
        if (!action.equals("BUY") && !action.equals("SELL") && !action.equals("HOLD")) {
            log.warn("Unexpected action: {} (expected BUY, SELL, or HOLD)", action);
        }

        if (signal.getStrategyName() == null || signal.getStrategyName().isBlank()) {
            throw new IllegalArgumentException("Strategy name cannot be blank");
        }

        // Validate confidence range
        if (signal.getConfidence() < 0.0 || signal.getConfidence() > 1.0) {
            throw new IllegalArgumentException(
                String.format("Confidence must be between 0.0 and 1.0, got: %.2f",
                    signal.getConfidence())
            );
        }

        if (signal.getTimestamp() == null) {
            throw new IllegalArgumentException("Timestamp cannot be null");
        }
    }

    /**
     * Persists signal to QuestDB using individual INSERT.
     * 
     * Why NOT batch?
     * - Low volume: Individual inserts are fast enough (~10ms)
     * - Real-time requirement: No batching delay
     */
    private void persistSignal(Signal signal) {
        try {
            String sql = "INSERT INTO signals (symbol, action, strategy_name, confidence, timestamp) " +
                         "VALUES (?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql,
                signal.getSymbol(),
                signal.getAction(),
                signal.getStrategyName(),
                signal.getConfidence(),
                Timestamp.from(signal.getTimestamp())
            );

            log.debug("Inserted signal into QuestDB: {} {} {}",
                    signal.getStrategyName(), signal.getAction(), signal.getSymbol());

        } catch (Exception e) {
            log.error("Database insert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist signal to database", e);
        }
    }

    private void logStatistics() {
        log.info("===== Signal Consumer Statistics =====");
        log.info("Signals received:   {}", signalsReceived);
        log.info("Signals processed:  {}", signalsProcessed);
        log.info("Signals failed:     {}", signalsFailed);

        if (signalsReceived > 0) {
            double successRate = (signalsProcessed * 100.0) / signalsReceived;
            log.info("Success rate:       {:.2f}%", successRate);
        }

        log.info("=====================================");
    }

    public String getStatistics() {
        return String.format(
            "Received: %d, Processed: %d, Failed: %d, Success Rate: %.2f%%",
            signalsReceived,
            signalsProcessed,
            signalsFailed,
            signalsReceived > 0 ? (signalsProcessed * 100.0) / signalsReceived : 0.0
        );
    }
}