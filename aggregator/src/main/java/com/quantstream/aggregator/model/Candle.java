package com.quantstream.aggregator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * OHLC candle output.
 * 
 * Published to candles-1m topic.
 * 
 * Fields:
 * - open: First tick price in window
 * - high: Highest tick price in window
 * - low: Lowest tick price in window
 * - close: Last tick price in window
 * - volume: Sum of all tick volumes in window
 * - timestamp: Window start time
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Candle {
    private String symbol;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    
    @JsonProperty("timestamp")
    private Instant timestamp;
}