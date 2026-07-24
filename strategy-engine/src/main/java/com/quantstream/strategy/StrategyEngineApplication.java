package com.quantstream.strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Strategy Engine service.
 *
 * <p>Consumes microstructure features, runs every enabled strategy per token, and
 * publishes resulting trading signals to the {@code signals} topic. Strategies build
 * any lookback window in-memory from the stream — the engine never reads history from
 * the database.
 */
@SpringBootApplication
public class StrategyEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(StrategyEngineApplication.class, args);
    }
}
