package com.quantstream.generator.service;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Generates realistic price movements using Geometric Brownian Motion (GBM).
 * <p>
 * GBM Formula: S(t+dt) = S(t) × exp((μ - 0.5σ²)dt + σ√dt × dW)
 * Where:
 * - S(t) = current price
 * - μ = drift (expected return, e.g., 0.08 = 8% annual)
 * - σ = volatility (randomness, e.g., 0.25 = 25% annual)
 * - dt = time step (1 second as fraction of year)
 * - dW = random Gaussian number from N(0,1)
 */
public class PriceSimulator {
    
    private static final Logger log = LoggerFactory.getLogger(PriceSimulator.class);
    
    // Number of seconds in a year (365 days × 24 hours × 60 minutes × 60 seconds)
    private static final double SECONDS_PER_YEAR = 365.0 * 24.0 * 60.0 * 60.0;
    
    // Time step: 1 second as fraction of year
    private static final double DT = 1.0 / SECONDS_PER_YEAR;
    
    // Random number generator for Gaussian (normal) distribution
    private final Random random = new Random();
    
    // Current price (updated after each tick)
    @Getter
    private double currentPrice;
    
    // Drift: expected return (annual)
    private final double drift;
    
    // Volatility: standard deviation of returns (annual)
    private final double volatility;
    
    // Pre-calculated drift term (optimization)
    private final double driftTerm;
    
    // Pre-calculated volatility term (optimization)
    private final double volatilityTerm;
    
    /**
     * Creates a new price simulator.
     *
     * @param initialPrice Starting price (e.g., 180.00 for AAPL)
     * @param drift        Annual drift/trend (e.g., 0.08 = 8% annual growth)
     * @param volatility   Annual volatility (e.g., 0.25 = 25% annual volatility)
     */
    public PriceSimulator(double initialPrice, double drift, double volatility) {
        if (initialPrice <= 0) {
            throw new IllegalArgumentException("Initial price must be positive, got: " + initialPrice);
        }
        if (volatility < 0) {
            throw new IllegalArgumentException("Volatility cannot be negative, got: " + volatility);
        }
        
        this.currentPrice = initialPrice;
        this.drift = drift;
        this.volatility = volatility;
        
        // Pre-calculate constant terms (avoid recalculating every tick)
        this.driftTerm = (drift - 0.5 * volatility * volatility) * DT;
        this.volatilityTerm = volatility * Math.sqrt(DT);
        
        log.debug("Created PriceSimulator: initialPrice={}, drift={}, volatility={}", 
                  initialPrice, drift, volatility);
    }
    
    /**
     * Generates the next price using GBM.
     * <p>
     * This method:
     * 1. Generates a random Gaussian number (dW)
     * 2. Calculates price change using GBM formula
     * 3. Updates and returns current price
     *
     * @return New price (one second later)
     */
    public double generateNextPrice() {
        // Generate random Gaussian number: mean=0, std=1
        // ~68% of values between -1 and +1
        // ~95% of values between -2 and +2
        double dW = random.nextGaussian();
        
        // GBM formula: newPrice = currentPrice × exp(driftTerm + volatilityTerm × dW)
        // 
        // Breaking it down:
        // 1. driftTerm = (μ - 0.5σ²) × dt
        //    This is the deterministic component (trend)
        // 
        // 2. volatilityTerm × dW = σ × √dt × dW
        //    This is the random component (noise)
        // 
        // 3. exp(driftTerm + volatilityTerm × dW)
        //    Exponential ensures price is always positive
        //    Converts additive changes to multiplicative (compound growth)
        double changeMultiplier = Math.exp(driftTerm + volatilityTerm * dW);
        
        // Update current price
        currentPrice = currentPrice * changeMultiplier;
        
        return currentPrice;
    }
    
    /**
     * Generates the next price with a specified volume.
     * Volume varies randomly between 50% and 150% of base volume.
     *
     * @param baseVolume Base volume (e.g., 1000.0)
     * @return Random volume around base volume
     */
    public double generateVolume(double baseVolume) {
        // Random multiplier between 0.5 and 1.5
        // 0.5 = 50% of base volume
        // 1.5 = 150% of base volume
        double multiplier = 0.5 + random.nextDouble();
        return baseVolume * multiplier;
    }
}