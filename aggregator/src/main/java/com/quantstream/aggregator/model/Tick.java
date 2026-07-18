package com.quantstream.aggregator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Incoming tick from market-data topic.
 * 
 * Matches data-generator output format.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tick {
    private String symbol;
    private double price;
    private double volume;
    
    @JsonProperty("timestamp")
    private Instant timestamp;
}