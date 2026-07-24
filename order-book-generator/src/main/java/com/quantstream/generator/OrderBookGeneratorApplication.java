package com.quantstream.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order Book Generator service.
 *
 * <p>Simulates order book snapshots for a configured set of tokens and publishes
 * them to Kafka at a fixed rate (default 1/sec/token). This is the source of all
 * data in the QuantStream pipeline.
 */
@SpringBootApplication
@EnableScheduling
public class OrderBookGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderBookGeneratorApplication.class, args);
    }
}
