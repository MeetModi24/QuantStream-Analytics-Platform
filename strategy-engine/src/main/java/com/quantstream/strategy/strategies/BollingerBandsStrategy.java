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
 * Bollinger Bands Strategy
 *
 * Type: Volatility / Mean Reversion
 *
 * Logic:
 * - Price touches/crosses below lower band → BUY (oversold)
 * - Price touches/crosses above upper band → SELL (overbought)
 *
 * Additional Logic:
 * - Band squeeze (narrow bands) → Volatility breakout coming
 * - Band expansion → High volatility, ride the trend
 *
 * Performance:
 * - Best in ranging markets with normal volatility
 * - Poor in strong trends (price rides bands)
 */
@Component
public class BollingerBandsStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(BollingerBandsStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int BB_PERIOD = 20;
    private static final double STD_DEV_MULTIPLIER = 2.0;

    // Track previous price position relative to bands
    private final Map<String, Boolean> wasAboveUpperBand = new HashMap<>();
    private final Map<String, Boolean> wasBelowLowerBand = new HashMap<>();

    @Override
    public String getName() {
        return "BOLLINGER_BANDS";
    }

    @Override
    public int getRequiredHistoryDays() {
        return BB_PERIOD;
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, BB_PERIOD);

            // Step 2: Validate data
            if (prices.size() < BB_PERIOD) {
                log.debug("Not enough data for {}: {} ticks (need {})",
                    symbol, prices.size(), BB_PERIOD);
                return null;
            }

            // Step 3: Calculate Bollinger Bands
            IndicatorUtils.BollingerBands bb = indicators.calculateBollingerBands(
                prices, BB_PERIOD, STD_DEV_MULTIPLIER
            );

            double currentPrice = prices.get(0);

            // Step 4: Check price position relative to bands
            boolean isAboveUpper = currentPrice > bb.getUpper();
            boolean isBelowLower = currentPrice < bb.getLower();

            // Step 5: Get previous states
            Boolean prevAboveUpper = wasAboveUpperBand.get(symbol);
            Boolean prevBelowLower = wasBelowLowerBand.get(symbol);

            // Step 6: Detect signals
            Signal signal = null;

            if (prevBelowLower != null && prevAboveUpper != null) {
                // Price crosses into lower band zone → BUY (oversold, expect bounce)
                if (isBelowLower && !prevBelowLower) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(currentPrice, bb, true),
                        Instant.now()
                    );
                    log.info("BB lower band touch: {} (price={:.2f}, lower={:.2f})",
                        symbol, currentPrice, bb.getLower());
                }

                // Price crosses into upper band zone → SELL (overbought, expect pullback)
                else if (isAboveUpper && !prevAboveUpper) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(currentPrice, bb, false),
                        Instant.now()
                    );
                    log.info("BB upper band touch: {} (price={:.2f}, upper={:.2f})",
                        symbol, currentPrice, bb.getUpper());
                }
            } else {
                log.debug("First run for {}, initializing BB state", symbol);
            }

            // Step 7: Store current states
            wasAboveUpperBand.put(symbol, isAboveUpper);
            wasBelowLowerBand.put(symbol, isBelowLower);

            return signal;

        } catch (Exception e) {
            log.error("Bollinger Bands strategy failed for {}: {}", symbol, e.getMessage());
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
     * Calculate confidence based on distance from band.
     *
     * Further from middle = higher confidence.
     */
    private double calculateConfidence(double price, IndicatorUtils.BollingerBands bb, boolean isBuy) {
        double baseConfidence = 0.70;

        double bandWidth = bb.getUpper() - bb.getLower();
        if (bandWidth == 0) return baseConfidence;

        if (isBuy) {
            // Distance below lower band
            double penetration = (bb.getLower() - price) / bandWidth;
            return Math.min(baseConfidence + (penetration * 0.20), 0.90);
        } else {
            // Distance above upper band
            double penetration = (price - bb.getUpper()) / bandWidth;
            return Math.min(baseConfidence + (penetration * 0.20), 0.90);
        }
    }
}
