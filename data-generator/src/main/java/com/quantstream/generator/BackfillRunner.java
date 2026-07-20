package com.quantstream.generator;

import com.quantstream.generator.model.TokenConfig;
import com.quantstream.generator.model.Tick;
import com.quantstream.generator.service.PriceSimulator;
import com.quantstream.generator.service.TokenRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Backfill Runner - Generates historical data fast.
 *
 * Run with: mvn spring-boot:run -Dspring-boot.run.arguments="--backfill.enabled=true --backfill.days=30"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillRunner implements CommandLineRunner {

    private final TokenRegistryService tokenRegistryService;
    private final KafkaTemplate<String, Tick> kafkaTemplate;

    @Value("${backfill.enabled:false}")
    private boolean backfillEnabled;

    @Value("${backfill.days:30}")
    private int days;

    @Value("${backfill.ticks-per-hour:60}")
    private int ticksPerHour;

    @Override
    public void run(String... args) throws Exception {
        if (!backfillEnabled) {
            log.info("Backfill not enabled. Normal operation.");
            return;
        }

        log.info("========================================");
        log.info("BACKFILL MODE ENABLED");
        log.info("========================================");

        log.info("Backfill Configuration:");
        log.info("  Days: {}", days);
        log.info("  Ticks per hour: {}", ticksPerHour);

        // Run backfill
        backfillHistoricalData(days, ticksPerHour);

        log.info("========================================");
        log.info("BACKFILL COMPLETE - Exiting");
        log.info("========================================");

        // Exit after backfill
        System.exit(0);
    }

    private void backfillHistoricalData(int days, int ticksPerHour) {
        var activeTokens = tokenRegistryService.getActiveTokens();

        // Create simulators for each token
        Map<String, PriceSimulator> simulators = new HashMap<>();
        for (TokenConfig config : activeTokens) {
            PriceSimulator simulator = new PriceSimulator(
                config.initialPrice(),
                config.drift(),
                config.volatility()
            );
            simulators.put(config.symbol(), simulator);
        }

        // Calculate total ticks to generate
        int hoursToGenerate = days * 24;
        long totalTicks = (long) hoursToGenerate * ticksPerHour * activeTokens.size();

        log.info("Generating {} ticks ({} hours × {} ticks/hour × {} symbols)",
                totalTicks, hoursToGenerate, ticksPerHour, activeTokens.size());

        // Start from 'days' ago
        Instant currentTime = Instant.now().minus(days, ChronoUnit.DAYS);
        long secondsBetweenTicks = 3600 / ticksPerHour; // seconds per tick

        long ticksGenerated = 0;
        long lastLogTime = System.currentTimeMillis();

        // Generate historical data
        for (int hour = 0; hour < hoursToGenerate; hour++) {
            for (int tickIdx = 0; tickIdx < ticksPerHour; tickIdx++) {
                // Generate tick for each symbol
                for (TokenConfig config : activeTokens) {
                    String symbol = config.symbol();
                    PriceSimulator simulator = simulators.get(symbol);

                    // Generate price and volume
                    double price = simulator.generateNextPrice();
                    double volume = simulator.generateVolume(config.baseVolume());

                    // Create tick (order: symbol, price, volume, timestamp)
                    Tick tick = new Tick(
                        symbol,
                        price,
                        volume,
                        currentTime
                    );

                    // Send to Kafka
                    kafkaTemplate.send("market-data", symbol, tick);

                    ticksGenerated++;
                }

                // Advance time
                currentTime = currentTime.plusSeconds(secondsBetweenTicks);

                // Log progress every 10 seconds
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 10000) {
                    double progress = (double) ticksGenerated / totalTicks * 100;
                    log.info("Progress: {}/{} ticks ({:.1f}%) - Current time: {}",
                            ticksGenerated, totalTicks, progress, currentTime);
                    lastLogTime = now;
                }
            }
        }

        // Flush Kafka
        kafkaTemplate.flush();

        log.info("✅ Generated {} ticks from {} to {}",
                ticksGenerated,
                currentTime.minus(days, ChronoUnit.DAYS),
                currentTime);
    }
}
