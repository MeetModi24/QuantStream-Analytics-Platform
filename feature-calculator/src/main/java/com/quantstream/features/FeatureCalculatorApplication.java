package com.quantstream.features;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Feature Calculator service.
 *
 * <p>Consumes raw order book snapshots from the {@code order-book-data} topic,
 * computes stateless microstructure features (OBI, microprice, spread, ...) and
 * republishes them to the {@code features} topic for the Strategy Engine and
 * Database Writer to consume.
 */
@SpringBootApplication
public class FeatureCalculatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeatureCalculatorApplication.class, args);
    }
}
