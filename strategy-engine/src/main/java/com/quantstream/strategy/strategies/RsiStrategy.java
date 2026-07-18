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
 * RSI (Relative Strength Index) Strategy
 *
 * Type: Mean Reversion / Oscillator
 *
 * Logic:
 * - RSI < 30 (oversold) → BUY
 * - RSI > 70 (overbought) → SELL
 *
 * Additional Confirmation:
 * - Look for RSI crossing thresholds (not just being above/below)
 * - Prevents repeated signals in same zone
 *
 * Performance:
 * - Best in ranging/sideways markets
 * - Poor in strong trends (stays overbought/oversold)
 */
@Component
public class RsiStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RsiStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int RSI_PERIOD = 14;
    private static final double OVERSOLD = 30.0;
    private static final double OVERBOUGHT = 70.0;

    // Track previous RSI to detect threshold crossings
    private final Map<String, Double> previousRSI = new HashMap<>();

    @Override
    public String getName() {
        return "RSI";
    }

    @Override
    public int getRequiredHistoryDays() {
        return RSI_PERIOD + 1;  // Need 15 data points for RSI(14)
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, RSI_PERIOD + 1);

            // Step 2: Validate data
            if (prices.size() < RSI_PERIOD + 1) {
                log.debug("Not enough data for {}: {} ticks (need {})",
                    symbol, prices.size(), RSI_PERIOD + 1);
                return null;
            }

            // Step 3: Calculate RSI
            double rsi = indicators.calculateRSI(prices, RSI_PERIOD);

            // Step 4: Get previous RSI
            Double prevRSI = previousRSI.get(symbol);

            // Step 5: Detect signals
            Signal signal = null;

            if (prevRSI != null) {
                // Oversold → BUY (RSI crosses above 30 from below)
                if (rsi > OVERSOLD && prevRSI <= OVERSOLD) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(rsi, true),
                        Instant.now()
                    );
                    log.info("RSI oversold signal: {} (RSI={:.2f}, was {:.2f})",
                        symbol, rsi, prevRSI);
                }

                // Overbought → SELL (RSI crosses below 70 from above)
                else if (rsi < OVERBOUGHT && prevRSI >= OVERBOUGHT) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(rsi, false),
                        Instant.now()
                    );
                    log.info("RSI overbought signal: {} (RSI={:.2f}, was {:.2f})",
                        symbol, rsi, prevRSI);
                }
            } else {
                log.debug("First run for {}, initializing RSI state", symbol);
            }

            // Step 6: Store current RSI for next run
            previousRSI.put(symbol, rsi);

            return signal;

        } catch (Exception e) {
            log.error("RSI strategy failed for {}: {}", symbol, e.getMessage());
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
     * Calculate confidence based on RSI extremity.
     *
     * More extreme RSI = higher confidence.
     * - RSI 10 (very oversold) → high confidence BUY
     * - RSI 90 (very overbought) → high confidence SELL
     */
    private double calculateConfidence(double rsi, boolean isBuy) {
        double baseConfidence = 0.70;

        if (isBuy) {
            // Lower RSI = higher confidence (more oversold)
            double extremity = (OVERSOLD - rsi) / OVERSOLD;  // 0 to 1
            return Math.min(baseConfidence + (extremity * 0.20), 0.90);
        } else {
            // Higher RSI = higher confidence (more overbought)
            double extremity = (rsi - OVERBOUGHT) / (100 - OVERBOUGHT);  // 0 to 1
            return Math.min(baseConfidence + (extremity * 0.20), 0.90);
        }
    }
}
