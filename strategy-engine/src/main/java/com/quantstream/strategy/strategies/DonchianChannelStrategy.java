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
 * Donchian Channel Strategy
 *
 * Type: Breakout / Trend Following
 *
 * Components:
 * - Upper Channel: Highest high over N periods
 * - Lower Channel: Lowest low over N periods
 * - Middle Channel: (Upper + Lower) / 2
 *
 * Logic:
 * - Price breaks above upper channel → BUY (bullish breakout)
 * - Price breaks below lower channel → SELL (bearish breakout)
 *
 * Key Insight:
 * - Used in Turtle Trading strategy
 * - Captures momentum breakouts from consolidation
 * - Narrow channel → Low volatility → Big move coming
 *
 * Performance:
 * - Best in trending markets (catches breakouts early)
 * - Poor in ranging markets (false breakouts)
 */
@Component
public class DonchianChannelStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(DonchianChannelStrategy.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IndicatorUtils indicators;

    private static final int CHANNEL_PERIOD = 20;

    // Track previous price position relative to channels
    private final Map<String, Boolean> wasAboveUpper = new HashMap<>();
    private final Map<String, Boolean> wasBelowLower = new HashMap<>();

    @Override
    public String getName() {
        return "DONCHIAN_CHANNEL";
    }

    @Override
    public int getRequiredHistoryDays() {
        return CHANNEL_PERIOD;
    }

    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, CHANNEL_PERIOD);

            // Step 2: Validate data
            if (prices.size() < CHANNEL_PERIOD) {
                log.debug("Not enough data for {}: {} ticks (need {})",
                    symbol, prices.size(), CHANNEL_PERIOD);
                return null;
            }

            // Step 3: Calculate Donchian Channel
            IndicatorUtils.DonchianChannel channel = indicators.calculateDonchianChannel(
                prices, CHANNEL_PERIOD
            );

            double currentPrice = prices.get(0);

            // Step 4: Check price position relative to channels
            boolean isAboveUpper = currentPrice > channel.getUpper();
            boolean isBelowLower = currentPrice < channel.getLower();

            // Step 5: Get previous states
            Boolean prevAboveUpper = wasAboveUpper.get(symbol);
            Boolean prevBelowLower = wasBelowLower.get(symbol);

            // Step 6: Detect breakouts
            Signal signal = null;

            if (prevAboveUpper != null && prevBelowLower != null) {
                // Bullish breakout: Price breaks above upper channel
                if (isAboveUpper && !prevAboveUpper) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(currentPrice, channel, true),
                        Instant.now()
                    );
                    log.info("Donchian bullish breakout: {} (price={:.2f}, upper={:.2f})",
                        symbol, currentPrice, channel.getUpper());
                }

                // Bearish breakout: Price breaks below lower channel
                else if (isBelowLower && !prevBelowLower) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(currentPrice, channel, false),
                        Instant.now()
                    );
                    log.info("Donchian bearish breakout: {} (price={:.2f}, lower={:.2f})",
                        symbol, currentPrice, channel.getLower());
                }
            } else {
                log.debug("First run for {}, initializing Donchian state", symbol);
            }

            // Step 7: Store current states
            wasAboveUpper.put(symbol, isAboveUpper);
            wasBelowLower.put(symbol, isBelowLower);

            return signal;

        } catch (Exception e) {
            log.error("Donchian Channel strategy failed for {}: {}", symbol, e.getMessage());
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
     * Calculate confidence based on breakout strength and channel width.
     *
     * Stronger breakout + narrow channel = higher confidence.
     */
    private double calculateConfidence(double price, IndicatorUtils.DonchianChannel channel,
                                      boolean isBuy) {
        double baseConfidence = 0.75;

        double channelWidth = channel.getUpper() - channel.getLower();
        if (channelWidth == 0) return baseConfidence;

        if (isBuy) {
            // Distance above upper channel
            double breakoutStrength = (price - channel.getUpper()) / channelWidth;
            return Math.min(baseConfidence + (breakoutStrength * 0.15), 0.90);
        } else {
            // Distance below lower channel
            double breakoutStrength = (channel.getLower() - price) / channelWidth;
            return Math.min(baseConfidence + (breakoutStrength * 0.15), 0.90);
        }
    }
}
