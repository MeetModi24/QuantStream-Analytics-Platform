package com.quantstream.strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Strategy Engine - Analyzes market data and generates trading signals.
 * 
 * Architecture:
 * - Queries QuestDB for historical ticks
 * - Runs 10 trading strategies every minute
 * - Produces signals to Kafka "trading-signals" topic
 * 
 * Design:
 * - Interface-based (TradingStrategy interface)
 * - Spring auto-discovery of strategies
 * - Scheduled execution (@Scheduled)
 */
@SpringBootApplication
@EnableScheduling  // Enable @Scheduled annotation
public class StrategyEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(StrategyEngineApplication.class, args);
    }
}