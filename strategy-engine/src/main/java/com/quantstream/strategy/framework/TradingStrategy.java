package com.quantstream.strategy.framework;

import com.quantstream.strategy.model.Signal;

/**
 * Contract that all trading strategies must implement.
 * 
 * Design Pattern: Strategy Pattern (GoF)
 * - Defines family of algorithms (trading strategies)
 * - Encapsulates each one in a class
 * - Makes them interchangeable (all implement same interface)
 * 
 * Spring Auto-Discovery:
 * - Classes implementing this interface + @Component annotation
 * - Are automatically discovered by Spring
 * - Injected as List<TradingStrategy> into StrategyScheduler
 */
public interface TradingStrategy {
    
    /**
     * Strategy identifier.
     * 
     * Used in Signal.strategyName to track which strategy generated signal.
     * 
     * @return Unique strategy name (e.g., "MA_CROSSOVER", "RSI", "MACD")
     */
    String getName();
    
    /**
     * Minimum historical data required.
     * 
     * Examples:
     * - MA(50) strategy needs 50 days
     * - RSI strategy needs 14 days
     * - VWAP strategy needs 1 day (intraday only)
     * 
     * Used to:
     * 1. Validate sufficient data before running strategy
     * 2. Document strategy requirements
     * 3. Optimize database queries
     * 
     * @return Number of days of historical data required
     */
    int getRequiredHistoryDays();
    
    /**
     * Core strategy logic.
     * 
     * Executed by StrategyScheduler every minute for each symbol.
     * 
     * Implementation pattern:
     * 1. Query historical data from QuestDB
     * 2. Calculate indicators (MA, RSI, etc.)
     * 3. Apply strategy rules
     * 4. Generate signal if conditions met
     * 5. Return null if no signal
     * 
     * Error handling:
     * - Throw exceptions for critical errors (DB connection failure)
     * - Return null for expected cases (not enough data, no signal)
     * - Scheduler will catch exceptions and log them
     * 
     * Thread safety:
     * - Strategies are Spring singletons (one instance per strategy)
     * - Scheduler runs single-threaded (no concurrent calls)
     * - Safe to store state in instance variables (e.g., previous MA values)
     * 
     * @param symbol Stock/crypto symbol to analyze (e.g., "AAPL", "BTC")
     * @return Signal if strategy conditions met, null otherwise
     */
    Signal analyze(String symbol);
}