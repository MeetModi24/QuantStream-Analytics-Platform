package com.quantstream.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantstream.common.config.TokenConfig;
import com.quantstream.common.config.TokenRegistry;
import com.quantstream.common.model.OrderBookSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Drives the order book simulators on a fixed schedule and publishes each snapshot
 * to Kafka.
 *
 * <p>For the walking skeleton this runs a single token (AAPL). Additional tokens
 * are added simply by expanding the simulator list — the tick logic is unchanged.
 */
@Service
public class GeneratorService {

    private static final Logger log = LoggerFactory.getLogger(GeneratorService.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final List<OrderBookSimulator> simulators;

    private long tickCount = 0;

    public GeneratorService(KafkaTemplate<String, String> kafka,
                            ObjectMapper objectMapper,
                            TokenRegistry tokenRegistry,
                            @Value("${quantstream.topic.order-book:order-book-data}") String topic) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = topic;
        // One simulator per ENABLED token. Scaling to 100 tokens is purely a
        // tokens.yml change — no code here changes.
        this.simulators = tokenRegistry.enabledTokens().stream()
                .map(GeneratorService::toSimulator)
                .toList();
        log.info("Initialized {} order book simulator(s): {}",
                simulators.size(),
                simulators.stream().map(OrderBookSimulator::token).toList());
    }

    private static OrderBookSimulator toSimulator(TokenConfig cfg) {
        return new OrderBookSimulator(
                cfg.symbol(), cfg.initialPrice(), cfg.tickSize(),
                cfg.spread(), cfg.baseVolume(), cfg.volatility());
    }

    /**
     * Fires once per second (configurable). Each simulator produces one snapshot,
     * keyed by token so all updates for a symbol land on the same Kafka partition
     * (preserving per-token ordering downstream).
     */
    @Scheduled(fixedRateString = "${quantstream.generator.interval-ms:1000}")
    public void generateTick() {
        OrderBookSnapshot last = null;
        for (OrderBookSimulator sim : simulators) {
            OrderBookSnapshot snapshot = sim.next();
            try {
                String payload = objectMapper.writeValueAsString(snapshot);
                kafka.send(topic, snapshot.token(), payload);
                last = snapshot;
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize snapshot for {}", sim.token(), e);
            }
        }
        tickCount++;
        if (tickCount % 10 == 0 && last != null) {
            log.info("Emitted {} ticks. Latest {} bid={} ask={}",
                    tickCount, last.token(), last.bestBidPrice(), last.bestAskPrice());
        }
    }
}
