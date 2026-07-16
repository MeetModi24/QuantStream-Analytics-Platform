package com.quantstream.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * Represents a single price tick for a financial instrument.
 * <p>
 * This is received from Kafka and stored in QuestDB.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Tick {

    private String symbol;
    private double price;
    private double volume;
    private Instant timestamp;
}