package com.quantstream.strategy.framework;

import com.quantstream.strategy.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

/**
 * Scheduler that runs all trading strategies periodically.
 * 
 * Design:
 * - Spring auto-discovers all @Component classes implementing TradingStrategy
 * - Injects them as List<TradingStrategy>
 * - Runs each strategy every minute
 * - Sends signals to Kafka
 * 
 * Error Handling:
 * - Individual strategy failures don't stop other strategies
 * - Errors logged but not re-thrown
 * - Scheduler continues on next cycle
 */
@Component
public class StrategyScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(StrategyScheduler.class);
    
    /**
     * All strategies implementing TradingStrategy interface.
     * Spring auto-injects ALL @Component classes implementing the interface.
     */
    @Autowired
    private List<TradingStrategy> strategies;
    
    /**
     * Kafka producer for sending signals.
     */
    @Autowired
    private KafkaTemplate<String, Signal> kafkaTemplate;
    
    /**
     * Symbols to analyze (injected from application.yml).
     * 
     * Format in application.yml:
     *   strategy.symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC
     * 
     * Benefits:
     * - No recompilation needed to change symbols
     * - Different symbols per environment (dev/prod)
     * - Easy to add/remove symbols via config
     */
    @Value("${strategy.symbols}")
    private String symbolsConfig;
    
    private List<String> symbols;
    
    /**
     * Initialize symbols list on startup.
     */
    @PostConstruct
    public void init() {
        this.symbols = Arrays.asList(symbolsConfig.split(","));
        log.info("Initialized with {} symbols: {}", symbols.size(), symbols);
    }
    
    /**
     * Main scheduled task.
     * 
     * Runs every 60 seconds (configurable via ${strategy.execution.interval-ms}).
     * 
     * Execution:
     * - For each strategy
     * - For each symbol
     * - Call strategy.analyze(symbol)
     * - If signal returned, send to Kafka
     * 
     * Performance:
     * - 10 strategies × 10 symbols = 100 analyses per minute
     * - Each analysis: ~10ms (DB query + calculation)
     * - Total: ~1 second per cycle
     */
    @Scheduled(fixedRateString = "${strategy.execution.interval-ms}")
    public void runAllStrategies() {
        log.info("=== Running {} strategies for {} symbols ===", 
                strategies.size(), symbols.size());
        
        int signalsGenerated = 0;
        int errorsEncountered = 0;
        
        for (TradingStrategy strategy : strategies) {
            for (String symbol : symbols) {
                try {
                    Signal signal = strategy.analyze(symbol);
                    
                    if (signal != null) {
                        // Send to Kafka
                        kafkaTemplate.send("trading-signals", signal);
                        signalsGenerated++;
                        
                        log.debug("Signal: {} {} from {} (confidence: {:.2f})", 
                                 signal.getAction(), 
                                 symbol, 
                                 strategy.getName(), 
                                 signal.getConfidence());
                    }
                    
                } catch (IllegalArgumentException e) {
                    // Expected errors (not enough data, invalid input)
                    log.debug("Strategy {} skipped {}: {}", 
                             strategy.getName(), symbol, e.getMessage());
                    
                } catch (Exception e) {
                    // Unexpected errors (DB connection, etc.)
                    log.error("Strategy {} failed for {}: {}", 
                             strategy.getName(), symbol, e.getMessage(), e);
                    errorsEncountered++;
                }
            }
        }
        
        log.info("=== Completed: {} signals generated, {} errors ===", 
                signalsGenerated, errorsEncountered);
    }
}
