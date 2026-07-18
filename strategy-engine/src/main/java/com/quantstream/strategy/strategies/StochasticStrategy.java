package com.quantstream.strategy.strategies;

import com.quantstream.strategy.framework.TradingStrategy;
import com.quantstream.strategy.model.Signal;
import com.quantstream.strategy.utils.IndicatorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stochastic Oscillator Strategy
 *
 * Type: Momentum / Oscillator
 *
 * Components:
 * - %K: Fast stochastic (current momentum)
 * - %D: Slow stochastic (3-period SMA of %K)
 *
 * Logic:
 * - %K crosses above %D in oversold zone (<20) → BUY
 * - %K crosses below %D in overbought zone (>80) → SELL
 *
 * Performance:
 * - Best in ranging markets with clear support/resistance
 * - Poor in strong trends (stays in extreme zones)
 */
@Component
public class StochasticStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(StochasticStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int STOCH_PERIOD = 14;
    private static final int D_PERIOD = 3;
    private static final double OVERSOLD = 20.0;
    private static final double OVERBOUGHT = 80.0;

    // Track %K history to calculate %D (SMA of %K)
    private final Map<String, List<Double>> percentKHistory = new HashMap<>();

    @Override
    public String getName() {
        return "STOCHASTIC";
    }

    @Override
    public int getRequiredHistoryDays() {
        return STOCH_PERIOD;
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, STOCH_PERIOD);

            // Step 2: Validate data
            if (prices.size() < STOCH_PERIOD) {
                log.debug("Not enough data for {}: {} ticks (need {})",
                    symbol, prices.size(), STOCH_PERIOD);
                return null;
            }

            // Step 3: Calculate %K
            IndicatorUtils.Stochastic stoch = indicators.calculateStochastic(prices, STOCH_PERIOD);
            double percentK = stoch.getPercentK();

            // Step 4: Update %K history
            List<Double> kHistory = percentKHistory.computeIfAbsent(symbol, k -> new ArrayList<>());
            kHistory.add(0, percentK);  // Add to front

            // Keep only what we need for %D
            if (kHistory.size() > D_PERIOD + 5) {
                kHistory.remove(kHistory.size() - 1);
            }

            // Step 5: Calculate %D (need at least 3 %K values)
            if (kHistory.size() < D_PERIOD) {
                log.debug("Building %K history for {}: {}/{}",
                    symbol, kHistory.size(), D_PERIOD);
                return null;
            }

            double percentD = kHistory.stream().limit(D_PERIOD)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

            // Step 6: Check for previous values to detect crossover
            if (kHistory.size() < D_PERIOD + 1) {
                log.debug("First complete run for {}, initializing state", symbol);
                return null;
            }

            // Get previous %K and %D
            double prevPercentK = kHistory.get(1);
            double prevPercentD = kHistory.subList(1, D_PERIOD + 1).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

            // Step 7: Detect crossovers in extreme zones
            Signal signal = null;

            // Bullish: %K crosses above %D in oversold zone
            if (percentK > percentD && prevPercentK <= prevPercentD && percentK < OVERSOLD + 10) {
                signal = new Signal(
                    symbol,
                    "BUY",
                    getName(),
                    calculateConfidence(percentK, true),
                    Instant.now()
                );
                log.info("Stochastic bullish crossover: {} (%K={:.2f}, %D={:.2f})",
                    symbol, percentK, percentD);
            }

            // Bearish: %K crosses below %D in overbought zone
            else if (percentK < percentD && prevPercentK >= prevPercentD && percentK > OVERBOUGHT - 10) {
                signal = new Signal(
                    symbol,
                    "SELL",
                    getName(),
                    calculateConfidence(percentK, false),
                    Instant.now()
                );
                log.info("Stochastic bearish crossover: {} (%K={:.2f}, %D={:.2f})",
                    symbol, percentK, percentD);
            }

            return signal;

        } catch (Exception e) {
            log.error("Stochastic strategy failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Query recent prices from QuestDB.
     */
    private List<Double> queryPrices(String symbol, int limit) {
        String sql = "SELECT price FROM ticks WHERE symbol = ? ORDER BY timestamp DESC LIMIT ?";

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getDouble("price"),
            symbol,
            limit
        );
    }

    /**
     * Calculate confidence based on position in extreme zone.
     *
     * More extreme = higher confidence.
     */
    private double calculateConfidence(double percentK, boolean isBuy) {
        double baseConfidence = 0.70;

        if (isBuy) {
            // Lower %K in oversold zone = higher confidence
            double extremity = (OVERSOLD - percentK) / OVERSOLD;
            return Math.min(baseConfidence + (Math.max(0, extremity) * 0.20), 0.90);
        } else {
            // Higher %K in overbought zone = higher confidence
            double extremity = (percentK - OVERBOUGHT) / (100 - OVERBOUGHT);
            return Math.min(baseConfidence + (Math.max(0, extremity) * 0.20), 0.90);
        }
    }
}
