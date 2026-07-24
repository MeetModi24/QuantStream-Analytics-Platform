package com.quantstream.common.model;

import java.time.Instant;
import java.util.List;

/**
 * A complete order book snapshot for one token at one instant.
 *
 * <p>This is the core message on the {@code order-book-data} Kafka topic, produced
 * once per second per token by the Order Book Generator.
 *
 * <p>Bids are ordered highest-price-first (best bid at index 0); asks are ordered
 * lowest-price-first (best ask at index 0).
 *
 * @param token     symbol, e.g. "AAPL"
 * @param timestamp snapshot time
 * @param bids      buy orders, best-first (typically 5 levels)
 * @param asks      sell orders, best-first (typically 5 levels)
 */
public record OrderBookSnapshot(
        String token,
        Instant timestamp,
        List<PriceLevel> bids,
        List<PriceLevel> asks
) {
    /** Best (highest) bid price, or NaN if the book side is empty. */
    public double bestBidPrice() {
        return bids.isEmpty() ? Double.NaN : bids.getFirst().price();
    }

    /** Best (lowest) ask price, or NaN if the book side is empty. */
    public double bestAskPrice() {
        return asks.isEmpty() ? Double.NaN : asks.getFirst().price();
    }

    /** Volume resting at the best bid, or 0 if empty. */
    public double bestBidVolume() {
        return bids.isEmpty() ? 0.0 : bids.getFirst().volume();
    }

    /** Volume resting at the best ask, or 0 if empty. */
    public double bestAskVolume() {
        return asks.isEmpty() ? 0.0 : asks.getFirst().volume();
    }
}
