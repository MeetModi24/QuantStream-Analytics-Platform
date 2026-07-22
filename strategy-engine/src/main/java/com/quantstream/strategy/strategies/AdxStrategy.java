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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADX (Average Directional Index) Strategy
 *
 * Type: Trend Strength
 *
 * Components:
 * - ADX: Trend strength (0-100, higher = stronger trend)
 * - +DI: Positive directional indicator
 * - -DI: Negative directional indicator
 *
 * Logic:
 * - ADX > 25 (strong trend) AND +DI crosses above -DI → BUY
 * - ADX > 25 (strong trend) AND -DI crosses above +DI → SELL
 *
 * Key Insight:
 * - ADX measures trend STRENGTH, not direction
 * - Use +DI/-DI crossovers for direction
 * - Only trade when ADX confirms strong trend
 *
 * Performance:
 * - Best in trending markets (filters out weak trends)
 * - Poor in ranging markets (late signals)
 */
@Component
public class AdxStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(AdxStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int ADX_PERIOD = 14;
    private static final double MIN_TREND_STRENGTH = 25.0;

    // Track previous +DI/-DI to detect crossovers
    private final Map<String, Double> previousPlusDI = new HashMap<>();
    private final Map<String, Double> previousMinusDI = new HashMap<>();

    @Override
    public String getName() {
        return "ADX";
    }

    @Override
    public int getRequiredHistoryDays() {
        return ADX_PERIOD + 1;
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, ADX_PERIOD + 1);

            // Step 2: Validate data
            if (prices.size() < ADX_PERIOD + 1) {
                log.debug("Not enough data for {}: {} daily candles (need {})",
                    symbol, prices.size(), ADX_PERIOD + 1);
                return null;
            }

            // Step 3: Calculate ADX
            IndicatorUtils.ADX adxData = indicators.calculateADX(prices, ADX_PERIOD);
            double adx = adxData.getAdx();
            double plusDI = adxData.getPlusDI();
            double minusDI = adxData.getMinusDI();

            // Step 4: Get previous DI values
            Double prevPlusDI = previousPlusDI.get(symbol);
            Double prevMinusDI = previousMinusDI.get(symbol);

            // Step 5: Detect signals (only in strong trends)
            Signal signal = null;

            if (prevPlusDI != null && prevMinusDI != null && adx > MIN_TREND_STRENGTH) {
                // Bullish: +DI crosses above -DI in strong trend
                if (plusDI > minusDI && prevPlusDI <= prevMinusDI) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(adx),
                        Instant.now()
                    );
                    log.info("ADX bullish signal: {} (ADX={:.2f}, +DI={:.2f}, -DI={:.2f})",
                        symbol, adx, plusDI, minusDI);
                }

                // Bearish: -DI crosses above +DI in strong trend
                else if (minusDI > plusDI && prevMinusDI <= prevPlusDI) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(adx),
                        Instant.now()
                    );
                    log.info("ADX bearish signal: {} (ADX={:.2f}, +DI={:.2f}, -DI={:.2f})",
                        symbol, adx, plusDI, minusDI);
                }
            } else {
                if (prevPlusDI == null) {
                    log.debug("First run for {}, initializing ADX state", symbol);
                } else if (adx <= MIN_TREND_STRENGTH) {
                    log.debug("Weak trend for {} (ADX={:.2f}, need > {})",
                        symbol, adx, MIN_TREND_STRENGTH);
                }
            }

            // Step 6: Store current DI values
            previousPlusDI.put(symbol, plusDI);
            previousMinusDI.put(symbol, minusDI);

            return signal;

        } catch (Exception e) {
            log.error("ADX strategy failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Query recent daily candles from QuestDB.
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
     * Calculate confidence based on ADX strength.
     *
     * Higher ADX = stronger trend = higher confidence.
     */
    private double calculateConfidence(double adx) {
        double baseConfidence = 0.75;

        // ADX > 25 = strong, > 50 = very strong
        double strength = Math.min((adx - MIN_TREND_STRENGTH) / 25.0, 1.0);

        return Math.min(baseConfidence + (strength * 0.15), 0.90);
    }
}
