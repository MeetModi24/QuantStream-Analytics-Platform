package com.quantstream.common.model;

import java.time.Instant;

/**
 * Derived microstructure metrics computed from an {@link OrderBookSnapshot}.
 *
 * <p>Published on the {@code features} Kafka topic by the Feature Calculator and
 * consumed by the Strategy Engine. All fields are stateless functions of a single
 * snapshot (no lookback), so they can be computed one-message-in, one-message-out.
 *
 * @param token      symbol
 * @param timestamp  time of the source snapshot
 * @param obi        order book imbalance in [-1, 1]; positive = buy pressure
 * @param microprice volume-weighted mid price (more predictive than plain mid)
 * @param midPrice   simple mid = (bestBid + bestAsk) / 2
 * @param spread     bestAsk - bestBid
 * @param spreadBps  spread expressed in basis points of mid price
 */
public record Features(
        String token,
        Instant timestamp,
        double obi,
        double microprice,
        double midPrice,
        double spread,
        double spreadBps
) {
}
