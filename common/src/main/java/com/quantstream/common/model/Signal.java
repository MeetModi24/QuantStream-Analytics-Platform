package com.quantstream.common.model;

import java.time.Instant;

/**
 * A trading signal emitted by a strategy for one token at one instant.
 *
 * <p>Published on the {@code signals} Kafka topic by the Strategy Engine. This is a
 * monitoring/visualization signal (QuantStream is a dashboard, not a trading system) —
 * it records what a strategy <em>would</em> do and why.
 *
 * @param strategy   name of the strategy that produced this signal
 * @param token      symbol
 * @param action     the recommended action
 * @param price      reference price at signal time (typically microprice or mid)
 * @param confidence strength of the signal in [0, 1]
 * @param reason     human-readable explanation (shown on the dashboard)
 * @param timestamp  time of the source data that triggered the signal
 */
public record Signal(
        String strategy,
        String token,
        Action action,
        double price,
        double confidence,
        String reason,
        Instant timestamp
) {
    /** Recommended direction. */
    public enum Action {
        BUY,
        SELL,
        /** Exit an existing position (flatten). */
        CLOSE
    }
}
