package com.quantstream.aggregator.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantstream.aggregator.portfolio.Portfolio;
import com.quantstream.aggregator.portfolio.TokenConsensus;
import com.quantstream.common.model.Position;
import com.quantstream.common.model.StrategyPnl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodically snapshots the {@link Portfolio} and publishes it downstream.
 *
 * <p>Emitting on a timer (rather than only on each signal) is deliberate: open positions
 * are marked to market against the live microprice, so PnL must update every interval
 * even when no new signal fires. Positions are keyed by token and PnL by strategy on
 * Kafka to preserve per-entity ordering, matching the rest of the pipeline.
 *
 * <p>Persistence is intentionally NOT done here — the database-writer owns QuestDB. This
 * component only computes and emits (see docs/planning/05-signal-aggregator.md, D3).
 */
@Component
public class SnapshotPublisher {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPublisher.class);

    private final Portfolio portfolio;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String positionsTopic;
    private final String pnlTopic;

    public SnapshotPublisher(Portfolio portfolio,
                             KafkaTemplate<String, String> kafka,
                             ObjectMapper objectMapper,
                             @Value("${quantstream.topic.positions:positions}") String positionsTopic,
                             @Value("${quantstream.topic.strategy-pnl:strategy-pnl}") String pnlTopic) {
        this.portfolio = portfolio;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.positionsTopic = positionsTopic;
        this.pnlTopic = pnlTopic;
    }

    @Scheduled(fixedRateString = "${quantstream.aggregator.publish-interval-ms:1000}")
    public void publish() {
        Instant now = Instant.now();

        List<Position> positions = portfolio.snapshotPositions(now);
        for (Position p : positions) {
            send(positionsTopic, p.token(), p);
        }

        List<StrategyPnl> pnls = portfolio.snapshotStrategyPnl(now);
        for (StrategyPnl pnl : pnls) {
            send(pnlTopic, pnl.strategy(), pnl);
        }

        logConflicts();
    }

    private void logConflicts() {
        for (TokenConsensus c : portfolio.consensus()) {
            if (c.conflict()) {
                log.warn("CONFLICT on {}: long={} short={}",
                        c.token(), c.longStrategies(), c.shortStrategies());
            }
        }
    }

    private void send(String topic, String key, Object value) {
        try {
            kafka.send(topic, key, objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            log.error("Failed to publish to {} (key={})", topic, key, e);
        }
    }
}
