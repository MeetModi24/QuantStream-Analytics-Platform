package com.quantstream.strategy.strategies;

import com.quantstream.strategy.framework.TradingStrategy;
import com.quantstream.strategy.model.Signal;
import com.quantstream.strategy.model.Tick;
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
 * VWAP (Volume-Weighted Average Price) Strategy
 *
 * Type: Volume-based Mean Reversion
 *
 * Formula: Sum(Price × Volume) / Sum(Volume)
 *
 * Logic:
 * - Price crosses below VWAP → BUY (undervalued)
 * - Price crosses above VWAP → SELL (overvalued)
 *
 * Key Insights:
 * - VWAP represents "true" average price (weighted by volume)
 * - Institutional traders use VWAP as benchmark
 * - Typically reset daily (intraday indicator)
 *
 * Performance:
 * - Best intraday with high volume
 * - Poor in low-volume conditions (VWAP unstable)
 */
@Component
public class VwapStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(VwapStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int VWAP_PERIOD = 50;  // Recent ticks for VWAP calculation
    private static final double MIN_DEVIATION = 0.005;  // 0.5% minimum deviation

    // Track previous price position relative to VWAP
    private final Map<String, Boolean> wasAboveVWAP = new HashMap<>();

    @Override
    public String getName() {
        return "VWAP";
    }

    @Override
    public int getRequiredHistoryDays() {
        return 1;  // Intraday indicator
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical ticks with volume
            List<Tick> ticks = queryTicks(symbol, VWAP_PERIOD);

            // Step 2: Validate data
            if (ticks.size() < VWAP_PERIOD) {
                log.debug("Not enough data for {}: {} ticks (need {})",
                    symbol, ticks.size(), VWAP_PERIOD);
                return null;
            }

            // Step 3: Calculate VWAP
            double vwap = indicators.calculateVWAP(ticks);
            double currentPrice = ticks.get(0).getPrice();

            // Step 4: Check price position relative to VWAP
            boolean isAboveVWAP = currentPrice > vwap;

            // Calculate deviation from VWAP
            double deviation = Math.abs(currentPrice - vwap) / vwap;

            // Step 5: Get previous state
            Boolean prevAboveVWAP = wasAboveVWAP.get(symbol);

            // Step 6: Detect crossings with minimum deviation filter
            Signal signal = null;

            if (prevAboveVWAP != null && deviation > MIN_DEVIATION) {
                // Bearish: Price crosses above VWAP (overvalued, expect reversion down)
                if (isAboveVWAP && !prevAboveVWAP) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(deviation),
                        Instant.now()
                    );
                    log.info("VWAP sell signal: {} (price={:.2f}, VWAP={:.2f}, dev={:.2f}%)",
                        symbol, currentPrice, vwap, deviation * 100);
                }

                // Bullish: Price crosses below VWAP (undervalued, expect reversion up)
                else if (!isAboveVWAP && prevAboveVWAP) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(deviation),
                        Instant.now()
                    );
                    log.info("VWAP buy signal: {} (price={:.2f}, VWAP={:.2f}, dev={:.2f}%)",
                        symbol, currentPrice, vwap, deviation * 100);
                }
            } else {
                if (prevAboveVWAP == null) {
                    log.debug("First run for {}, initializing VWAP state", symbol);
                }
            }

            // Step 7: Store current state
            wasAboveVWAP.put(symbol, isAboveVWAP);

            return signal;

        } catch (Exception e) {
            log.error("VWAP strategy failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Query recent ticks with volume from QuestDB.
     */
    private List<Tick> queryTicks(String symbol, int limit) {
        String sql = "SELECT symbol, price, volume, timestamp " +
                    "FROM ticks WHERE symbol = ? " +
                    "ORDER BY timestamp DESC LIMIT ?";

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new Tick(
                rs.getString("symbol"),
                rs.getDouble("price"),
                rs.getDouble("volume"),
                rs.getTimestamp("timestamp").toInstant()
            ),
            symbol,
            limit
        );
    }

    /**
     * Calculate confidence based on deviation from VWAP.
     *
     * Larger deviation = stronger mean reversion signal = higher confidence.
     */
    private double calculateConfidence(double deviation) {
        double baseConfidence = 0.70;

        // Normalize deviation (1% = medium, 3%+ = very strong)
        double strength = Math.min(deviation / 0.03, 1.0);

        return Math.min(baseConfidence + (strength * 0.20), 0.90);
    }
}
