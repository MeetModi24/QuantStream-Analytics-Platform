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
 * ROC (Rate of Change) Strategy
 *
 * Type: Momentum
 *
 * Formula: ((Current - N periods ago) / N periods ago) × 100
 *
 * Logic:
 * - ROC crosses above 0 → BUY (momentum turning positive)
 * - ROC crosses below 0 → SELL (momentum turning negative)
 *
 * Additional filters:
 * - Strong ROC (>5% or <-5%) = high confidence
 * - Weak ROC near zero = skip (noise)
 *
 * Performance:
 * - Best in trending markets (identifies momentum shifts)
 * - Poor in choppy markets (many zero crossings)
 */
@Component
public class RocStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RocStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int ROC_PERIOD = 10;
    private static final double MIN_ROC_THRESHOLD = 2.0;  // Filter weak signals

    // Track previous ROC to detect zero crossings
    private final Map<String, Double> previousROC = new HashMap<>();

    @Override
    public String getName() {
        return "ROC";
    }

    @Override
    public int getRequiredHistoryDays() {
        return ROC_PERIOD + 1;
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, ROC_PERIOD + 1);

            // Step 2: Validate data
            if (prices.size() < ROC_PERIOD + 1) {
                log.debug("Not enough data for {}: {} ticks (need {})",
                    symbol, prices.size(), ROC_PERIOD + 1);
                return null;
            }

            // Step 3: Calculate ROC
            double roc = indicators.calculateROC(prices, ROC_PERIOD);

            // Step 4: Get previous ROC
            Double prevROC = previousROC.get(symbol);

            // Step 5: Detect zero crossings with minimum threshold
            Signal signal = null;

            if (prevROC != null) {
                // Bullish: ROC crosses above 0 (momentum turning positive)
                if (roc > 0 && prevROC <= 0 && roc > MIN_ROC_THRESHOLD) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(roc),
                        Instant.now()
                    );
                    log.info("ROC bullish signal: {} (ROC={:.2f}%, was {:.2f}%)",
                        symbol, roc, prevROC);
                }

                // Bearish: ROC crosses below 0 (momentum turning negative)
                else if (roc < 0 && prevROC >= 0 && roc < -MIN_ROC_THRESHOLD) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(roc),
                        Instant.now()
                    );
                    log.info("ROC bearish signal: {} (ROC={:.2f}%, was {:.2f}%)",
                        symbol, roc, prevROC);
                }
            } else {
                log.debug("First run for {}, initializing ROC state", symbol);
            }

            // Step 6: Store current ROC for next run
            previousROC.put(symbol, roc);

            return signal;

        } catch (Exception e) {
            log.error("ROC strategy failed for {}: {}", symbol, e.getMessage());
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
     * Calculate confidence based on ROC magnitude.
     *
     * Stronger momentum = higher confidence.
     */
    private double calculateConfidence(double roc) {
        double baseConfidence = 0.70;

        // ROC magnitude (stronger momentum = higher confidence)
        double magnitude = Math.abs(roc);

        // Normalize: 5% ROC = medium, 10%+ = very strong
        double strength = Math.min(magnitude / 10.0, 1.0);

        return Math.min(baseConfidence + (strength * 0.20), 0.90);
    }
}
