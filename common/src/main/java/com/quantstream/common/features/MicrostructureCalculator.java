package com.quantstream.common.features;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.OrderBookSnapshot;

/**
 * Pure, stateless computation of order book microstructure features.
 *
 * <p>Every metric here is a function of a single {@link OrderBookSnapshot} — no
 * lookback, no history (see {@code docs/planning/03-historical-data-and-retention.md}).
 * Kept free of any framework dependency so it can be unit-tested in isolation and
 * reused by any service.
 */
public final class MicrostructureCalculator {

    private MicrostructureCalculator() {
    }

    /**
     * Computes all stateless features for a snapshot.
     *
     * @throws IllegalArgumentException if the book is missing a bid or ask side
     */
    public static Features compute(OrderBookSnapshot ob) {
        if (ob.bids().isEmpty() || ob.asks().isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot compute features for one-sided book: " + ob.token());
        }

        double bidPrice = ob.bestBidPrice();
        double askPrice = ob.bestAskPrice();
        double bidVol = ob.bestBidVolume();
        double askVol = ob.bestAskVolume();

        double obi = orderBookImbalance(bidVol, askVol);
        double microprice = microprice(bidPrice, bidVol, askPrice, askVol);
        double midPrice = (bidPrice + askPrice) / 2.0;
        double spread = askPrice - bidPrice;
        double spreadBps = midPrice > 0 ? (spread / midPrice) * 10_000.0 : 0.0;

        return new Features(
                ob.token(), ob.timestamp(),
                obi, microprice, midPrice, spread, spreadBps);
    }

    /**
     * Order book imbalance in [-1, 1]. Positive = more bid (buy) volume than ask.
     * Returns 0 when both sides are empty (degenerate book).
     */
    public static double orderBookImbalance(double bidVolume, double askVolume) {
        double total = bidVolume + askVolume;
        if (total <= 0) {
            return 0.0;
        }
        return (bidVolume - askVolume) / total;
    }

    /**
     * Volume-weighted mid price. Weights each side's price by the OPPOSITE side's
     * volume, so heavier bid volume pulls the microprice toward the ask (the classic
     * predictive-mid formulation). Falls back to the simple mid if there is no volume.
     */
    public static double microprice(double bidPrice, double bidVolume,
                                     double askPrice, double askVolume) {
        double total = bidVolume + askVolume;
        if (total <= 0) {
            return (bidPrice + askPrice) / 2.0;
        }
        return (bidPrice * askVolume + askPrice * bidVolume) / total;
    }
}
