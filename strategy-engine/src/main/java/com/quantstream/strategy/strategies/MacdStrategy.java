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
 * MACD (Moving Average Convergence Divergence) Strategy
 *
 * Type: Trend Following + Momentum
 *
 * Components:
 * - MACD Line: EMA(12) - EMA(26)
 * - Signal Line: EMA(9) of MACD Line
 * - Histogram: MACD Line - Signal Line
 *
 * Logic:
 * - MACD crosses above Signal → BUY
 * - MACD crosses below Signal → SELL
 *
 * Performance:
 * - Best in trending markets
 * - Poor in choppy/sideways markets (whipsaws)
 */
@Component
public class MacdStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(MacdStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int MIN_PRICES = 35;  // Need 26 for MACD + 9 for signal
    private static final int SIGNAL_PERIOD = 9;

    // Track MACD line history to calculate signal line (EMA of MACD)
    private final Map<String, List<Double>> macdHistory = new HashMap<>();

    @Override
    public String getName() {
        return "MACD";
    }

    @Override
    public int getRequiredHistoryDays() {
        return MIN_PRICES;
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, MIN_PRICES);

            // Step 2: Validate data
            if (prices.size() < MIN_PRICES) {
                log.debug("Not enough data for {}: {} daily candles (need {})",
                    symbol, prices.size(), MIN_PRICES);
                return null;
            }

            // Step 3: Calculate MACD line
            IndicatorUtils.MACD macdData = indicators.calculateMACD(prices);
            double macdLine = macdData.getLine();

            // Step 4: Update MACD history
            List<Double> history = macdHistory.computeIfAbsent(symbol, k -> new ArrayList<>());
            history.add(0, macdLine);  // Add to front (most recent)

            // Keep only what we need for signal line calculation
            if (history.size() > SIGNAL_PERIOD + 5) {
                history.remove(history.size() - 1);
            }

            // Step 5: Calculate signal line (need at least 9 MACD values)
            if (history.size() < SIGNAL_PERIOD) {
                log.debug("Building MACD history for {}: {}/{}",
                    symbol, history.size(), SIGNAL_PERIOD);
                return null;
            }

            double signalLine = indicators.calculateEMA(history, SIGNAL_PERIOD);
            double histogram = macdLine - signalLine;

            // Step 6: Check for previous values to detect crossover
            if (history.size() < SIGNAL_PERIOD + 1) {
                log.debug("First complete run for {}, initializing state", symbol);
                return null;
            }

            // Get previous MACD and signal
            double prevMacdLine = history.get(1);
            List<Double> prevHistory = history.subList(1, history.size());
            double prevSignalLine = indicators.calculateEMA(prevHistory, SIGNAL_PERIOD);

            // Step 7: Detect crossovers
            Signal signal = null;

            // Bullish crossover: MACD crosses above signal
            if (macdLine > signalLine && prevMacdLine <= prevSignalLine) {
                signal = new Signal(
                    symbol,
                    "BUY",
                    getName(),
                    calculateConfidence(histogram, true),
                    Instant.now()
                );
                log.info("MACD bullish crossover: {} (MACD={:.2f}, Signal={:.2f})",
                    symbol, macdLine, signalLine);
            }

            // Bearish crossover: MACD crosses below signal
            else if (macdLine < signalLine && prevMacdLine >= prevSignalLine) {
                signal = new Signal(
                    symbol,
                    "SELL",
                    getName(),
                    calculateConfidence(histogram, false),
                    Instant.now()
                );
                log.info("MACD bearish crossover: {} (MACD={:.2f}, Signal={:.2f})",
                    symbol, macdLine, signalLine);
            }

            return signal;

        } catch (Exception e) {
            log.error("MACD strategy failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Query recent closing prices from daily candles in QuestDB.
     */
    private List<Double> queryPrices(String symbol, int limit) {
        String sql = "SELECT close FROM candles_1d WHERE symbol = ? ORDER BY date DESC LIMIT ?";

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getDouble("close"),
            symbol,
            limit
        );
    }

    /**
     * Calculate confidence based on histogram strength.
     *
     * Larger histogram = stronger momentum = higher confidence.
     */
    private double calculateConfidence(double histogram, boolean isBuy) {
        double baseConfidence = 0.75;

        // Histogram strength (larger absolute value = stronger signal)
        double strength = Math.abs(histogram);

        // Normalize strength (typical histogram range is 0-5)
        double normalizedStrength = Math.min(strength / 5.0, 1.0);

        return Math.min(baseConfidence + (normalizedStrength * 0.15), 0.90);
    }
}
