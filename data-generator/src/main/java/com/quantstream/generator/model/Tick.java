package com.quantstream.generator.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a single price tick for a financial instrument.
 * <p>
 * This is sent to Kafka and stored in QuestDB.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tick {
    
    /**
     * Symbol of the instrument (e.g., "AAPL", "BTC")
     */
    private String symbol;
    
    /**
     * Current price in USD
     */
    private double price;
    
    /**
     * Trading volume (for stocks: shares, for crypto: coins)
     */
    private double volume;
    
    /**
     * When this tick occurred (ISO-8601 timestamp)
     */
    private Instant timestamp;
}
