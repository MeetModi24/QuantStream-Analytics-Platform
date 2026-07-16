package com.quantstream.consumer.service;

import com.quantstream.consumer.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Kafka consumer that receives tick data and persists to QuestDB.
 * <p>
 * Design Philosophy:
 * - Annotation-based consumption (@KafkaListener)
 * - Automatic deserialization (JSON → Tick object)
 * - Manual offset commit (acknowledge after successful persistence)
 * - JdbcTemplate for QuestDB (JPA doesn't work - QuestDB lacks transaction support)
 * - Robust error handling (log and continue)
 * - High observability (detailed logging)
 * <p>
 * Performance Characteristics:
 * - Processes messages one at a time (can be configured for batch)
 * - Commits offset after each successful insert
 * - Can handle 1000+ messages/second with connection pooling
 */
@Service
public class TickConsumer {

    private static final Logger log = LoggerFactory.getLogger(TickConsumer.class);

    private final JdbcTemplate jdbcTemplate;

    public TickConsumer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Counters for monitoring
    private long messagesReceived = 0;
    private long messagesProcessed = 0;
    private long messagesFailed = 0;

    /**
     * Consumes tick messages from Kafka topic.
     */
    @KafkaListener(
        topics = "market-data",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeTick(
            @Payload Tick tick,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp,
            Acknowledgment acknowledgment) {

        messagesReceived++;

        log.debug("Received tick from partition={}, offset={}: {} @ ${} (volume: {})",
                 partition, offset, tick.getSymbol(), tick.getPrice(), tick.getVolume());

        try {
            // Validate message
            validateTick(tick);

            // Insert into QuestDB
            persistTick(tick);

            // Acknowledge message (commits offset)
            acknowledgment.acknowledge();

            messagesProcessed++;

            log.debug("Successfully persisted tick: {} (total processed: {})",
                     tick.getSymbol(), messagesProcessed);

        } catch (Exception e) {
            messagesFailed++;

            log.error("Failed to process tick: symbol={}, price={}, volume={}, error={}",
                     tick.getSymbol(), tick.getPrice(), tick.getVolume(), e.getMessage(), e);

            // Acknowledge anyway to move forward
            acknowledgment.acknowledge();

            // Log statistics every 100 failures
            if (messagesFailed % 100 == 0) {
                logStatistics();
            }
        }
    }

    private void validateTick(Tick tick) {
        if (tick == null) {
            throw new IllegalArgumentException("Tick cannot be null");
        }

        if (tick.getSymbol() == null || tick.getSymbol().isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be blank");
        }

        if (tick.getPrice() <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + tick.getPrice());
        }

        if (tick.getVolume() < 0) {
            throw new IllegalArgumentException("Volume cannot be negative: " + tick.getVolume());
        }

        if (tick.getTimestamp() == null) {
            throw new IllegalArgumentException("Timestamp cannot be null");
        }

        Instant now = Instant.now();
        long diffSeconds = Math.abs(now.getEpochSecond() - tick.getTimestamp().getEpochSecond());

        if (diffSeconds > 3600) {
            log.warn("Tick timestamp is {} seconds off from current time: {}",
                    diffSeconds, tick.getTimestamp());
        }
    }

    /**
     * Persists tick to QuestDB using JDBC.
     * QuestDB doesn't support transactions, so JdbcTemplate is more appropriate than JPA.
     */
    private void persistTick(Tick tick) {
        try {
            String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
            jdbcTemplate.update(sql,
                tick.getSymbol(),
                tick.getPrice(),
                tick.getVolume(),
                java.sql.Timestamp.from(tick.getTimestamp())
            );

            log.trace("Inserted into QuestDB: {} @ ${}", tick.getSymbol(), tick.getPrice());

        } catch (Exception e) {
            log.error("Database insert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist tick to database", e);
        }
    }

    private void logStatistics() {
        log.info("===== Consumer Statistics =====");
        log.info("Messages received:  {}", messagesReceived);
        log.info("Messages processed: {}", messagesProcessed);
        log.info("Messages failed:    {}", messagesFailed);

        if (messagesReceived > 0) {
            double successRate = (messagesProcessed * 100.0) / messagesReceived;
            log.info("Success rate:       {:.2f}%", successRate);
        }
        log.info("===============================");
    }

    public String getStatistics() {
        return String.format(
            "Received: %d, Processed: %d, Failed: %d, Success Rate: %.2f%%",
            messagesReceived,
            messagesProcessed,
            messagesFailed,
            messagesReceived > 0 ? (messagesProcessed * 100.0) / messagesReceived : 0.0
        );
    }
}