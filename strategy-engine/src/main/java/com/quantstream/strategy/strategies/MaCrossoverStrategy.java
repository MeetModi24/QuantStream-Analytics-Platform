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
 * Moving Average Crossover Strategy
 * 
 * Type: Trend Following
 * 
 * Logic:
 * - Golden Cross (MA10 crosses above MA50) → BUY
 * - Death Cross (MA10 crosses below MA50) → SELL
 * 
 * State Tracking:
 * - Must remember previous MA values to detect crossovers
 * - Store per symbol (AAPL's MA ≠ BTC's MA)
 * 
 * Performance:
 * - Best in trending markets (bull/bear runs)
 * - Poor in choppy/sideways markets (many false signals)
 */
@Component
public class MaCrossoverStrategy implements TradingStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(MaCrossoverStrategy.class);
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private IndicatorUtils indicators;
    
    /**
     * Store previous MA values for crossover detection.
     * 
     * Key: symbol (e.g., "AAPL")
     * Value: previous MA10 value
     * 
     * Why HashMap:
     * - Need separate state per symbol
     * - Scheduler is single-threaded (no concurrency issues)
     * - Alternative: ConcurrentHashMap if scheduler becomes multi-threaded
     */
    private final Map<String, Double> previousMA10 = new HashMap<>();
    private final Map<String, Double> previousMA50 = new HashMap<>();
    
    @Override
    public String getName() {
        return "MA_CROSSOVER";
    }
    
    @Override
    public int getRequiredHistoryDays() {
        return 50;  // Need 50 days for MA(50)
    }
    
    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, 50);
            
            // Step 2: Validate data
            if (prices.size() < 50) {
                log.debug("Not enough data for {}: {} daily candles (need 50)", symbol, prices.size());
                return null;
            }
            
            // Step 3: Calculate indicators
            double ma10 = indicators.calculateMA(prices, 10);
            double ma50 = indicators.calculateMA(prices, 50);
            
            // Step 4: Get previous values (null on first run)
            Double prevMA10 = previousMA10.get(symbol);
            Double prevMA50 = previousMA50.get(symbol);
            
            // Step 5: Detect crossovers
            Signal signal = null;
            
            if (prevMA10 != null && prevMA50 != null) {
                // Golden Cross: MA10 crosses ABOVE MA50
                if (ma10 > ma50 && prevMA10 <= prevMA50) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(ma10, ma50, prices.get(0)),
                        Instant.now()
                    );
                    log.info("Golden Cross detected: {} (MA10={:.2f}, MA50={:.2f})", 
                            symbol, ma10, ma50);
                }
                
                // Death Cross: MA10 crosses BELOW MA50
                else if (ma10 < ma50 && prevMA10 >= prevMA50) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(ma10, ma50, prices.get(0)),
                        Instant.now()
                    );
                    log.info("Death Cross detected: {} (MA10={:.2f}, MA50={:.2f})", 
                            symbol, ma10, ma50);
                }
            } else {
                log.debug("First run for {}, initializing state", symbol);
            }
            
            // Step 6: Store current values for next run
            previousMA10.put(symbol, ma10);
            previousMA50.put(symbol, ma50);
            
            return signal;
            
        } catch (Exception e) {
            log.error("MA Crossover failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
    
    /**
     * Query recent prices from QuestDB.
     *
     * SQL:
     * - ORDER BY date DESC: Most recent first
     * - LIMIT 50: Only need 50 for MA(50)
     *
     * Performance:
     * - QuestDB optimized for time-series queries
     * - Query takes ~5-10ms
     *
     * @param symbol Stock/crypto symbol
     * @param limit Number of daily candles to fetch
     * @return List of prices (most recent first)
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
     * Calculate confidence score for signal.
     * 
     * Factors:
     * 1. Gap size (bigger gap = stronger signal)
     * 2. Price position relative to MAs
     * 3. Trend strength
     * 
     * Range: 0.0 to 1.0
     * - 0.7-0.8: Moderate confidence
     * - 0.8-0.9: High confidence
     * - 0.9+: Very high confidence
     * 
     * @param ma10 Short-term MA
     * @param ma50 Long-term MA
     * @param currentPrice Current price
     * @return Confidence score
     */
    private double calculateConfidence(double ma10, double ma50, double currentPrice) {
        // Base confidence for any crossover
        double baseConfidence = 0.75;
        
        // Gap between MAs (as percentage)
        double gap = Math.abs(ma10 - ma50) / ma50;
        
        // Price confirmation (price should support signal direction)
        boolean priceConfirms = false;
        if (ma10 > ma50 && currentPrice > ma10) {
            priceConfirms = true;  // BUY signal, price above MA10
        } else if (ma10 < ma50 && currentPrice < ma10) {
            priceConfirms = true;  // SELL signal, price below MA10
        }
        
        // Adjust confidence
        double confidence = baseConfidence;
        
        // Larger gap = stronger trend = higher confidence
        if (gap > 0.05) {  // 5% gap
            confidence += 0.10;
        }
        
        // Price confirmation adds confidence
        if (priceConfirms) {
            confidence += 0.05;
        }
        
        // Cap at 0.95 (never 100% certain)
        return Math.min(confidence, 0.95);
    }
}