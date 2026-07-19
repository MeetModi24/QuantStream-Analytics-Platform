package com.quantstream.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * Represents a 1-minute OHLC candle.
 * 
 * Received from Kafka topic: candles-1m (produced by aggregator service)
 * Stored in QuestDB table: candles_1m
 * 
 * Fields:
 * - symbol: Stock/crypto symbol (e.g., AAPL, BTC)
 * - open: First tick price in the minute
 * - high: Highest tick price in the minute
 * - low: Lowest tick price in the minute
 * - close: Last tick price in the minute
 * - volume: Sum of all tick volumes in the minute
 * - timestamp: Window start time (e.g., 14:25:00 for 14:25:00-14:25:59 window)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Candle {
    
    private String symbol;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private Instant timestamp;
}