package com.quantstream.generator.service;

import com.quantstream.generator.model.Tick;
import com.quantstream.generator.model.TokenConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Market data generator that produces realistic tick data for configured tokens.
 * <p>
 * Design Philosophy:
 * - Loads token configuration from TokenRegistryService (data-driven)
 * - Creates simulators only for ACTIVE tokens (efficient)
 * - Runs at 1 msg/sec for development (will reduce for production)
 * - Demonstrates production-ready, scalable architecture
 * <p>
 * Scalability:
 * - Can handle 10,000+ tokens in registry
 * - Generates ticks only for enabled tokens
 * - Change token list via config (no recompile)
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "backfill.enabled", havingValue = "false", matchIfMissing = true)
public class MarketDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(MarketDataGenerator.class);

    // Kafka topic name
    private static final String TOPIC = "market-data";

    // Injected dependencies (constructor injection via @RequiredArgsConstructor)
    private final KafkaTemplate<String, Tick> kafkaTemplate;
    private final TokenRegistryService tokenRegistryService;

    // Map: symbol → price simulator (only for active tokens)
    private final Map<String, PriceSimulator> simulators = new HashMap<>();

    /**
     * Initializes price simulators for active tokens.
     * Called automatically by Spring after bean creation.
     */
    @PostConstruct
    public void init() {
        var activeTokens = tokenRegistryService.getActiveTokens();

        log.info("Initializing Market Data Generator");
        log.info("Total tokens in registry: {}", tokenRegistryService.getTotalTokenCount());
        log.info("Active tokens: {}", activeTokens.size());

        // Create simulator for each active token using TokenConfig parameters
        for (TokenConfig token : activeTokens) {
            PriceSimulator simulator = new PriceSimulator(
                token.initialPrice(),
                token.drift(),
                token.volatility()
            );

            simulators.put(token.symbol(), simulator);

            log.info("Initialized {} ({}): price=${}, drift={}%, volatility={}%, volume={}, priority={}",
                     token.symbol(),
                     token.name(),
                     token.initialPrice(),
                     token.drift() * 100,
                     token.volatility() * 100,
                     token.baseVolume(),
                     token.priority());
        }

        log.info("Market Data Generator ready. Will generate {} ticks per second.", activeTokens.size());

        // Log tokens not enabled
        int tokensNotUsed = tokenRegistryService.getTotalTokenCount() - activeTokens.size();
        if (tokensNotUsed > 0) {
            log.info("Note: {} tokens available in registry but not enabled", tokensNotUsed);
            log.info("To enable more tokens, update 'market.data.enabled-symbols' in application.yml");
        }
    }

    /**
     * Generates and sends tick data for all active tokens.
     * Runs every 1000ms (1 second).
     * <p>
     * Note: This frequency is for development. In production, we'll reduce
     * to stay within free tier limits (10,000 messages/day).
     */
    @Scheduled(fixedRate = 1000)
    public void generateTicks() {
        Instant timestamp = Instant.now();
        var activeTokens = tokenRegistryService.getActiveTokens();

        // Iterate over activeTokens from service
        for (TokenConfig tokenConfig : activeTokens) {
            String symbol = tokenConfig.symbol();

            try {
                // Get simulator for this token
                PriceSimulator simulator = simulators.get(symbol);
                if (simulator == null) {
                    log.error("No simulator found for active token: {}", symbol);
                    continue;
                }

                // Generate next price and volume using TokenConfig parameters
                double price = simulator.generateNextPrice();
                double volume = simulator.generateVolume(tokenConfig.baseVolume());

                // Create tick
                Tick tick = new Tick(symbol, price, volume, timestamp);

                // Send to Kafka (async) - keeping all existing Kafka sending logic
                kafkaTemplate.send(TOPIC, symbol, tick)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to send tick for {}: {}", symbol, ex.getMessage());
                        } else {
                            log.debug("Tick sent: {} -> ${} (volume: {})",
                                     symbol, String.format("%.2f", price), String.format("%.0f", volume));
                        }
                    });

            } catch (Exception e) {
                log.error("Error generating tick for {}: {}", symbol, e.getMessage(), e);
            }
        }
    }
}
