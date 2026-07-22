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
 * Williams %R Strategy
 *
 * Type: Momentum / Oscillator
 *
 * Range: -100 to 0 (negative scale)
 *
 * Logic:
 * - %R crosses above -80 (from oversold) → BUY
 * - %R crosses below -20 (from overbought) → SELL
 *
 * Similar to Stochastic but inverted scale.
 *
 * Performance:
 * - Best in ranging markets
 * - Poor in strong trends (whipsaws in extreme zones)
 */
@Component
public class WilliamsRStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(WilliamsRStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int WILLIAMS_PERIOD = 14;
    private static final double OVERSOLD = -80.0;
    private static final double OVERBOUGHT = -20.0;

    // Track previous %R to detect threshold crossings
    private final Map<String, Double> previousWilliamsR = new HashMap<>();

    @Override
    public String getName() {
        return "WILLIAMS_R";
    }

    @Override
    public int getRequiredHistoryDays() {
        return WILLIAMS_PERIOD;
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, WILLIAMS_PERIOD);

            // Step 2: Validate data
            if (prices.size() < WILLIAMS_PERIOD) {
                log.debug("Not enough data for {}: {} daily candles (need {})",
                    symbol, prices.size(), WILLIAMS_PERIOD);
                return null;
            }

            // Step 3: Calculate Williams %R
            double williamsR = indicators.calculateWilliamsR(prices, WILLIAMS_PERIOD);

            // Step 4: Get previous %R
            Double prevWilliamsR = previousWilliamsR.get(symbol);

            // Step 5: Detect signals
            Signal signal = null;

            if (prevWilliamsR != null) {
                // Oversold → BUY (%R crosses above -80 from below)
                if (williamsR > OVERSOLD && prevWilliamsR <= OVERSOLD) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(williamsR, true),
                        Instant.now()
                    );
                    log.info("Williams %R oversold signal: {} (%R={:.2f}, was {:.2f})",
                        symbol, williamsR, prevWilliamsR);
                }

                // Overbought → SELL (%R crosses below -20 from above)
                else if (williamsR < OVERBOUGHT && prevWilliamsR >= OVERBOUGHT) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(williamsR, false),
                        Instant.now()
                    );
                    log.info("Williams %R overbought signal: {} (%R={:.2f}, was {:.2f})",
                        symbol, williamsR, prevWilliamsR);
                }
            } else {
                log.debug("First run for {}, initializing Williams %R state", symbol);
            }

            // Step 6: Store current %R for next run
            previousWilliamsR.put(symbol, williamsR);

            return signal;

        } catch (Exception e) {
            log.error("Williams %R strategy failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Query recent daily candle close prices from QuestDB.
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
     * Calculate confidence based on %R extremity.
     *
     * More extreme = higher confidence.
     */
    private double calculateConfidence(double williamsR, boolean isBuy) {
        double baseConfidence = 0.70;

        if (isBuy) {
            // More negative (closer to -100) = more oversold = higher confidence
            double extremity = Math.abs(williamsR + 100) / 20.0;  // 0 to 1 range
            return Math.min(baseConfidence + (extremity * 0.20), 0.90);
        } else {
            // Less negative (closer to 0) = more overbought = higher confidence
            double extremity = Math.abs(williamsR) / 20.0;  // 0 to 1 range
            return Math.min(baseConfidence + ((1 - extremity) * 0.20), 0.90);
        }
    }
}
