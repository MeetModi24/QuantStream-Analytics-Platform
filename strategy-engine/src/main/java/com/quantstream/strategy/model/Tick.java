package com.quantstream.strategy.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a single market data tick.
 * 
 * This is a read-only model - strategies query ticks from QuestDB
 * but never modify or write them (Phase 1 handles writes).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tick {
    private String symbol;      // Stock/crypto symbol (AAPL, BTC, etc.)
    private double price;       // Current price
    private double volume;      // Trade volume
    private Instant timestamp;  // When this tick occurred
}