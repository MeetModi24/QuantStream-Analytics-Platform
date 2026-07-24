package com.quantstream.aggregator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Signal Aggregator (Component 5): consumes {@code signals} and {@code features},
 * maintains a simulated paper-trading portfolio, and emits {@code positions} and
 * {@code strategy-pnl} for the dashboard. See docs/planning/05-signal-aggregator.md.
 */
@SpringBootApplication
@EnableScheduling
public class SignalAggregatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SignalAggregatorApplication.class, args);
    }
}
