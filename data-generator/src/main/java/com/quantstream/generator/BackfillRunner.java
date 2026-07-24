package com.quantstream.generator;

import com.quantstream.generator.model.TokenConfig;
import com.quantstream.generator.model.Candle;
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
 * Backfill Runner - Generates historical candle data (1-minute OHLC).
 *
 * Run with: mvn spring-boot:run -Dspring-boot.run.arguments="--backfill.enabled=true --backfill.days=60"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillRunner implements CommandLineRunner {

    private final TokenRegistryService tokenRegistryService;
    private final KafkaTemplate<String, Candle> kafkaTemplate;

    @Value("${backfill.enabled:false}")
    private boolean backfillEnabled;

    @Value("${backfill.days:60}")
    private int days;

    @Value("${backfill.ticks-per-candle:60}")
    private int ticksPerCandle;

    @Override
    public void run(String... args) throws Exception {
        if (!backfillEnabled) {
            log.info("Backfill not enabled. Normal operation.");
            return;
        }

        log.info("========================================");
        log.info("BACKFILL MODE ENABLED - GENERATING CANDLES");
        log.info("========================================");

        log.info("Backfill Configuration:");
        log.info("  Days: {}", days);
        log.info("  Ticks per candle: {} (for price variation simulation)", ticksPerCandle);

        // Run backfill
        backfillHistoricalCandles(days, ticksPerCandle);

        log.info("========================================");
        log.info("BACKFILL COMPLETE - Exiting");
        log.info("========================================");

        // Exit after backfill
        System.exit(0);
    }

    private void backfillHistoricalCandles(int days, int ticksPerCandle) {
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

        // Calculate total candles to generate: days × 24 hours × 60 minutes × symbols
        int minutesToGenerate = days * 24 * 60;
        long totalCandles = (long) minutesToGenerate * activeTokens.size();

        log.info("Generating {} candles ({} minutes × {} symbols = {})",
                totalCandles, minutesToGenerate, activeTokens.size(),
                totalCandles);
        log.info("Expected: 60 days × 1440 min/day × 10 symbols = 864,000 candles");

        // Start from 'days' ago, aligned to minute boundary
        Instant startTime = Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        Instant currentMinute = startTime;

        long candlesGenerated = 0;
        long lastLogTime = System.currentTimeMillis();

        // Generate candles minute by minute
        for (int minute = 0; minute < minutesToGenerate; minute++) {
            // Generate one candle per symbol for this minute
            for (TokenConfig config : activeTokens) {
                String symbol = config.symbol();
                PriceSimulator simulator = simulators.get(symbol);

                // Simulate multiple ticks within the minute to get OHLC variation
                double open = simulator.generateNextPrice();
                double high = open;
                double low = open;
                double close = open;
                double totalVolume = 0;

                // Generate ticksPerCandle ticks and aggregate OHLC
                for (int tickIdx = 0; tickIdx < ticksPerCandle; tickIdx++) {
                    double price = simulator.generateNextPrice();
                    double volume = simulator.generateVolume(config.baseVolume());

                    high = Math.max(high, price);
                    low = Math.min(low, price);
                    close = price; // Last tick becomes close
                    totalVolume += volume;
                }

                // Create candle
                Candle candle = new Candle(
                    symbol,
                    open,
                    high,
                    low,
                    close,
                    totalVolume,
                    currentMinute
                );

                // Send to Kafka candles-1m topic
                kafkaTemplate.send("candles-1m", symbol, candle);

                candlesGenerated++;
            }

            // Advance to next minute
            currentMinute = currentMinute.plus(1, ChronoUnit.MINUTES);

            // Log progress every 10 seconds
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 10000) {
                double progress = (double) candlesGenerated / totalCandles * 100;
                log.info("Progress: {}/{} candles ({:.1f}%) - Current time: {}",
                        candlesGenerated, totalCandles, progress, currentMinute);
                lastLogTime = now;
            }
        }

        // Flush Kafka
        kafkaTemplate.flush();

        log.info("✅ Generated {} candles from {} to {}",
                candlesGenerated,
                startTime,
                currentMinute);
    }
}
