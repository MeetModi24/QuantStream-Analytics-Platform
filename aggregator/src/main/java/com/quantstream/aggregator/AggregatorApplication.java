package com.quantstream.aggregator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aggregator Service - OHLC Candle Creation
 * 
 * Kafka Streams application that:
 * 1. Consumes ticks from market-data topic
 * 2. Windows into 1-minute intervals
 * 3. Aggregates into OHLC candles
 * 4. Produces candles to candles-1m topic
 * 
 * Processing Model:
 * - Stateful (maintains RocksDB state store)
 * - Event-time windowing (uses tick timestamps)
 * - Exactly-once semantics (transactional)
 * 
 * State Management:
 * - Local: /tmp/kafka-streams/aggregator-service/
 * - Changelog: __aggregator-service-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog
 * 
 * Failure Recovery:
 * - State automatically restored from changelog topic
 * - Resumes from last committed offset
 * - No data loss (exactly-once guarantee)
 */
@SpringBootApplication
public class AggregatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(AggregatorApplication.class, args);
    }
}