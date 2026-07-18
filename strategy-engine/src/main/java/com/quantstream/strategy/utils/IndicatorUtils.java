package com.quantstream.strategy.utils;

import com.quantstream.strategy.model.Tick;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared utility methods for technical indicator calculations.
 * 
 * Why separate class:
 * - Avoids duplication (MA used by multiple strategies)
 * - Easier to test (test once, works for all strategies)
 * - Single source of truth (change MA calculation in one place)
 * 
 * Why @Component:
 * - Allows dependency injection into strategies
 * - Spring manages lifecycle
 * - Can add caching/metrics later
 */
@Component
public class IndicatorUtils {
    
    // ============================================
    // Moving Average
    // ============================================
    
    /**
     * Simple Moving Average (SMA)
     * 
     * Formula: (p1 + p2 + ... + pN) / N
     * 
     * Use case: Smooth price data, identify trends
     * 
     * @param prices Historical prices (most recent first)
     * @param period Number of periods to average
     * @return SMA value
     */
    public double calculateMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        return prices.stream()
            .limit(period)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    }
    
    /**
     * Exponential Moving Average (EMA)
     * 
     * Formula: EMA = price * k + EMA(previous) * (1 - k)
     *          where k = 2 / (period + 1)
     * 
     * Difference from SMA:
     * - More weight on recent prices
     * - Reacts faster to price changes
     * - Used in MACD
     * 
     * @param prices Historical prices (most recent first)
     * @param period Number of periods
     * @return EMA value
     */
    public double calculateEMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        double multiplier = 2.0 / (period + 1);
        
        // Start with SMA of first N prices
        double ema = prices.subList(prices.size() - period, prices.size())
            .stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
        
        // Apply EMA formula to recent prices
        for (int i = prices.size() - period - 1; i >= 0; i--) {
            ema = (prices.get(i) * multiplier) + (ema * (1 - multiplier));
        }
        
        return ema;
    }
    
    // ============================================
    // RSI (Relative Strength Index)
    // ============================================
    
    /**
     * Relative Strength Index
     * 
     * Formula:
     * 1. Calculate gains and losses over period
     * 2. Average gain = sum(gains) / period
     * 3. Average loss = sum(losses) / period
     * 4. RS = average gain / average loss
     * 5. RSI = 100 - (100 / (1 + RS))
     * 
     * Range: 0 to 100
     * - RSI > 70: Overbought
     * - RSI < 30: Oversold
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 14
     * @return RSI value
     */
    public double calculateRSI(List<Double> prices, int period) {
        if (prices.size() <= period) {
            throw new IllegalArgumentException(
                String.format("Need at least %d prices for RSI(%d), got %d", 
                    period + 1, period, prices.size())
            );
        }
        
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        
        // Calculate price changes (recent to old)
        for (int i = 0; i < period; i++) {
            double change = prices.get(i) - prices.get(i + 1);
            if (change > 0) {
                gains.add(change);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(Math.abs(change));
            }
        }
        
        // Average gains and losses
        double avgGain = gains.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgLoss = losses.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        // Handle division by zero
        if (avgLoss == 0) {
            return 100.0; // All gains, no losses
        }
        
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
    
    // ============================================
    // Bollinger Bands
    // ============================================
    
    /**
     * Bollinger Bands
     * 
     * Components:
     * - Middle Band: 20-day SMA
     * - Upper Band: Middle + (2 × Standard Deviation)
     * - Lower Band: Middle - (2 × Standard Deviation)
     * 
     * Use case:
     * - Price near upper band → Overbought
     * - Price near lower band → Oversold
     * - Bands narrow → Low volatility → Breakout coming
     * - Bands wide → High volatility
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 20
     * @param stdDevMultiplier Typically 2.0
     * @return BollingerBands object
     */
    public BollingerBands calculateBollingerBands(List<Double> prices, int period, double stdDevMultiplier) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        // Middle band (SMA)
        double middleBand = calculateMA(prices, period);
        
        // Standard deviation
        List<Double> recentPrices = prices.subList(0, period);
        double variance = recentPrices.stream()
            .mapToDouble(price -> Math.pow(price - middleBand, 2))
            .average()
            .orElse(0.0);
        double stdDev = Math.sqrt(variance);
        
        // Upper and lower bands
        double upperBand = middleBand + (stdDevMultiplier * stdDev);
        double lowerBand = middleBand - (stdDevMultiplier * stdDev);
        
        return new BollingerBands(upperBand, middleBand, lowerBand);
    }
    
    // ============================================
    // MACD (Moving Average Convergence Divergence)
    // ============================================
    
    /**
     * MACD indicator
     * 
     * Components:
     * - MACD Line: EMA(12) - EMA(26)
     * - Signal Line: EMA(9) of MACD Line
     * - Histogram: MACD Line - Signal Line
     * 
     * Signals:
     * - MACD crosses above signal → Bullish
     * - MACD crosses below signal → Bearish
     * - Histogram growing → Trend strengthening
     * 
     * Note: This is a simplified single-call version.
     * Full implementation needs history of MACD values for signal line.
     * 
     * @param prices Historical prices (most recent first)
     * @return MACD object (line only, signal requires state tracking)
     */
    public MACD calculateMACD(List<Double> prices) {
        if (prices.size() < 26) {
            throw new IllegalArgumentException(
                String.format("Need at least 26 prices for MACD, got %d", prices.size())
            );
        }
        
        double ema12 = calculateEMA(prices, 12);
        double ema26 = calculateEMA(prices, 26);
        double macdLine = ema12 - ema26;
        
        // Signal line requires previous MACD values (state tracking)
        // Strategies will handle this in their analyze() method
        return new MACD(macdLine, 0.0, 0.0);
    }
    
    // ============================================
    // Stochastic Oscillator
    // ============================================
    
    /**
     * Stochastic Oscillator
     * 
     * Formula:
     * %K = ((Current - Lowest Low) / (Highest High - Lowest Low)) × 100
     * %D = 3-day SMA of %K
     * 
     * Range: 0 to 100
     * - %K > 80: Overbought
     * - %K < 20: Oversold
     * - %K crosses above %D: Bullish
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 14
     * @return Stochastic object
     */
    public Stochastic calculateStochastic(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        double currentClose = prices.get(0);
        List<Double> periodPrices = prices.subList(0, period);
        
        double highestHigh = Collections.max(periodPrices);
        double lowestLow = Collections.min(periodPrices);
        
        double percentK = ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100;
        
        // %D requires history of %K values (state tracking in strategy)
        return new Stochastic(percentK, 0.0);
    }
    
    // ============================================
    // Williams %R
    // ============================================
    
    /**
     * Williams %R
     * 
     * Formula: ((Highest High - Close) / (Highest High - Lowest Low)) × -100
     * 
     * Range: -100 to 0 (note negative!)
     * - %R > -20: Overbought
     * - %R < -80: Oversold
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 14
     * @return Williams %R value
     */
    public double calculateWilliamsR(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        double currentClose = prices.get(0);
        List<Double> periodPrices = prices.subList(0, period);
        
        double highestHigh = Collections.max(periodPrices);
        double lowestLow = Collections.min(periodPrices);
        
        return ((highestHigh - currentClose) / (highestHigh - lowestLow)) * -100;
    }
    
    // ============================================
    // Rate of Change (ROC)
    // ============================================
    
    /**
     * Rate of Change
     * 
     * Formula: ((Current Price - Price N Periods Ago) / Price N Periods Ago) × 100
     * 
     * Shows momentum as percentage change.
     * - ROC > 0: Upward momentum
     * - ROC < 0: Downward momentum
     * - ROC crossing 0: Trend change
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 10
     * @return ROC percentage
     */
    public double calculateROC(List<Double> prices, int period) {
        if (prices.size() <= period) {
            throw new IllegalArgumentException(
                String.format("Need at least %d prices for ROC(%d), got %d", 
                    period + 1, period, prices.size())
            );
        }
        
        double currentPrice = prices.get(0);
        double priceNPeriodsAgo = prices.get(period);
        
        return ((currentPrice - priceNPeriodsAgo) / priceNPeriodsAgo) * 100;
    }
    
    // ============================================
    // VWAP (Volume-Weighted Average Price)
    // ============================================
    
    /**
     * Volume-Weighted Average Price
     * 
     * Formula: Sum(Price × Volume) / Sum(Volume)
     * 
     * "True" average price accounting for trade size.
     * - Price < VWAP: Undervalued
     * - Price > VWAP: Overvalued
     * 
     * Typically used intraday (reset daily).
     * 
     * @param ticks Historical ticks with volume (most recent first)
     * @return VWAP value
     */
    public double calculateVWAP(List<Tick> ticks) {
        if (ticks.isEmpty()) {
            throw new IllegalArgumentException("Need at least 1 tick for VWAP");
        }
        
        double sumPriceVolume = 0;
        double sumVolume = 0;
        
        for (Tick tick : ticks) {
            sumPriceVolume += tick.getPrice() * tick.getVolume();
            sumVolume += tick.getVolume();
        }
        
        if (sumVolume == 0) {
            throw new IllegalArgumentException("Total volume is zero");
        }
        
        return sumPriceVolume / sumVolume;
    }
    
    // ============================================
    // ADX (Average Directional Index)
    // ============================================

    /**
     * Average Directional Index
     *
     * Measures trend strength (not direction).
     *
     * Components:
     * - +DI: Positive Directional Indicator
     * - -DI: Negative Directional Indicator
     * - ADX: Smoothed average of DX values
     *
     * Range: 0 to 100
     * - ADX < 20: Weak trend
     * - ADX 20-40: Strong trend
     * - ADX > 40: Very strong trend
     *
     * Signals:
     * - +DI crosses above -DI: Uptrend
     * - -DI crosses above +DI: Downtrend
     *
     * @param prices Historical prices (most recent first)
     * @param period Typically 14
     * @return ADX object
     */
    public ADX calculateADX(List<Double> prices, int period) {
        if (prices.size() < period + 1) {
            throw new IllegalArgumentException(
                String.format("Need at least %d prices for ADX(%d), got %d",
                    period + 1, period, prices.size())
            );
        }

        // Calculate True Range (TR) and Directional Movement (DM)
        List<Double> trValues = new ArrayList<>();
        List<Double> plusDM = new ArrayList<>();
        List<Double> minusDM = new ArrayList<>();

        for (int i = 0; i < period; i++) {
            double high = prices.get(i);
            double low = prices.get(i);
            double prevClose = prices.get(i + 1);

            // True Range = max(high-low, abs(high-prevClose), abs(low-prevClose))
            double tr = Math.max(high - low,
                         Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trValues.add(tr);

            // Directional Movement
            double upMove = prices.get(i) - prices.get(i + 1);
            double downMove = prices.get(i + 1) - prices.get(i);

            plusDM.add(upMove > downMove && upMove > 0 ? upMove : 0);
            minusDM.add(downMove > upMove && downMove > 0 ? downMove : 0);
        }

        // Calculate smoothed averages
        double avgTR = trValues.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double avgPlusDM = plusDM.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgMinusDM = minusDM.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        // Prevent division by zero
        if (avgTR == 0) avgTR = 1.0;

        // Directional Indicators
        double plusDI = (avgPlusDM / avgTR) * 100;
        double minusDI = (avgMinusDM / avgTR) * 100;

        // ADX calculation (simplified - full version needs smoothing)
        double dx = Math.abs(plusDI - minusDI) / (plusDI + minusDI + 0.0001) * 100;

        return new ADX(dx, plusDI, minusDI);
    }

    // ============================================
    // Donchian Channel
    // ============================================

    /**
     * Donchian Channel
     *
     * Components:
     * - Upper Channel: Highest high over N periods
     * - Lower Channel: Lowest low over N periods
     * - Middle Channel: (Upper + Lower) / 2
     *
     * Use case:
     * - Price breaks above upper channel → Bullish breakout
     * - Price breaks below lower channel → Bearish breakout
     * - Narrow channel → Low volatility
     *
     * @param prices Historical prices (most recent first)
     * @param period Typically 20
     * @return DonchianChannel object
     */
    public DonchianChannel calculateDonchianChannel(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }

        List<Double> periodPrices = prices.subList(0, period);

        double upper = Collections.max(periodPrices);
        double lower = Collections.min(periodPrices);
        double middle = (upper + lower) / 2.0;

        return new DonchianChannel(upper, middle, lower);
    }

    // ============================================
    // Supporting Data Classes
    // ============================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BollingerBands {
        private double upper;
        private double middle;
        private double lower;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MACD {
        private double line;        // MACD line
        private double signal;      // Signal line
        private double histogram;   // MACD - Signal
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stochastic {
        private double percentK;
        private double percentD;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ADX {
        private double adx;         // ADX value (trend strength)
        private double plusDI;      // +DI (positive directional indicator)
        private double minusDI;     // -DI (negative directional indicator)
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DonchianChannel {
        private double upper;
        private double middle;
        private double lower;
    }
}