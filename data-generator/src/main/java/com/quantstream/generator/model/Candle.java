package com.quantstream.generator.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
    private Instant timestamp;
}
