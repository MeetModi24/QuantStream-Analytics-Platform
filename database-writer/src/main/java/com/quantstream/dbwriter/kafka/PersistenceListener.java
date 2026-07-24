package com.quantstream.dbwriter.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantstream.common.model.Features;
import com.quantstream.common.model.OrderBookSnapshot;
import com.quantstream.common.model.Position;
import com.quantstream.common.model.Signal;
import com.quantstream.common.model.StrategyPnl;
import com.quantstream.dbwriter.questdb.QuestDbWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumes the pipeline's Kafka topics and persists each message to QuestDB.
 *
 * <p>Two independent listeners (order books, features) share one writer. Both topics
 * are keyed by token, so the container's per-partition ordering preserves per-token
 * event order into the database.
 *
 * <p>Deserialization failures are logged and skipped (a poison message must not stall
 * the partition); write failures propagate so the offset is not committed and the
 * message is redelivered.
 */
@Component
public class PersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(PersistenceListener.class);

    private final ObjectMapper objectMapper;
    private final QuestDbWriter writer;

    private final AtomicLong orderBooksWritten = new AtomicLong();
    private final AtomicLong featuresWritten = new AtomicLong();
    private final AtomicLong signalsWritten = new AtomicLong();
    private final AtomicLong positionsWritten = new AtomicLong();
    private final AtomicLong strategyPnlWritten = new AtomicLong();

    public PersistenceListener(ObjectMapper objectMapper, QuestDbWriter writer) {
        this.objectMapper = objectMapper;
        this.writer = writer;
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
        writer.writeOrderBook(snapshot);
        long n = orderBooksWritten.incrementAndGet();
        if (n % 50 == 0) {
            log.info("Persisted {} order book snapshots (latest token={})", n, snapshot.token());
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
        writer.writeFeatures(features);
        long n = featuresWritten.incrementAndGet();
        if (n % 50 == 0) {
            log.info("Persisted {} feature rows (latest token={})", n, features.token());
        }
    }

    @KafkaListener(
            topics = "${quantstream.topic.signals:signals}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onSignal(String payload) {
        Signal signal;
        try {
            signal = objectMapper.readValue(payload, Signal.class);
        } catch (Exception e) {
            log.error("Skipping unparseable signal message: {}", truncate(payload), e);
            return;
        }
        writer.writeSignal(signal);
        long n = signalsWritten.incrementAndGet();
        if (n % 10 == 0) {
            log.info("Persisted {} signals (latest {} {} {})",
                    n, signal.strategy(), signal.action(), signal.token());
        }
    }

    @KafkaListener(
            topics = "${quantstream.topic.positions:positions}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onPosition(String payload) {
        Position position;
        try {
            position = objectMapper.readValue(payload, Position.class);
        } catch (Exception e) {
            log.error("Skipping unparseable position message: {}", truncate(payload), e);
            return;
        }
        writer.writePosition(position);
        long n = positionsWritten.incrementAndGet();
        if (n % 50 == 0) {
            log.info("Persisted {} position snapshots (latest {} {})",
                    n, position.strategy(), position.token());
        }
    }

    @KafkaListener(
            topics = "${quantstream.topic.strategy-pnl:strategy-pnl}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onStrategyPnl(String payload) {
        StrategyPnl pnl;
        try {
            pnl = objectMapper.readValue(payload, StrategyPnl.class);
        } catch (Exception e) {
            log.error("Skipping unparseable strategy-pnl message: {}", truncate(payload), e);
            return;
        }
        writer.writeStrategyPnl(pnl);
        long n = strategyPnlWritten.incrementAndGet();
        if (n % 50 == 0) {
            log.info("Persisted {} strategy-pnl snapshots (latest {} total={})",
                    n, pnl.strategy(), pnl.totalPnl());
        }
    }

    private static String truncate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }
}
