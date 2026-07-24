package com.quantstream.generator;

import com.quantstream.common.model.OrderBookSnapshot;
import com.quantstream.common.model.PriceLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates a realistic sequence of order book snapshots for a single token.
 *
 * <p>Holds the minimal state needed for a forward-in-time simulation (last price
 * and last OBI) — see {@code docs/planning/03-historical-data-and-retention.md}.
 * The models used:
 * <ul>
 *   <li><b>Price</b>: geometric Brownian motion random walk.</li>
 *   <li><b>OBI</b>: mean-reverting AR(1) process around zero, which skews bid vs
 *       ask volumes so imbalance is temporally correlated (not white noise).</li>
 *   <li><b>Depth</b>: volume decays exponentially away from the top of book.</li>
 * </ul>
 *
 * <p>Not thread-safe: one instance per token, mutated only by its own scheduled tick.
 */
public class OrderBookSimulator {

    private static final int LEVELS = 5;
    /** Per-level volume decay factor (level n volume = base * DECAY^(n-1)). */
    private static final double DEPTH_DECAY = 0.7;
    /** Mean-reversion coefficient for the OBI AR(1) process. */
    private static final double OBI_PERSISTENCE = 0.9;
    private static final double OBI_NOISE_STD = 0.10;

    private final String token;
    private final double tickSize;
    private final double spread;
    private final double baseVolume;
    private final double volatility; // per-tick price volatility (fraction)

    private double lastPrice;
    private double lastObi = 0.0;

    public OrderBookSimulator(String token, double initialPrice, double tickSize,
                              double spread, double baseVolume, double volatility) {
        this.token = token;
        this.lastPrice = initialPrice;
        this.tickSize = tickSize;
        this.spread = spread;
        this.baseVolume = baseVolume;
        this.volatility = volatility;
    }

    /**
     * Advances the simulation one step and returns the resulting snapshot.
     */
    public OrderBookSnapshot next() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // 1. Price random walk (GBM-style multiplicative step).
        double shock = rnd.nextGaussian() * volatility;
        lastPrice = Math.max(tickSize, lastPrice * Math.exp(shock));

        // 2. Mean-reverting OBI: AR(1) reverting to 0, clamped to (-1, 1).
        lastObi = OBI_PERSISTENCE * lastObi + rnd.nextGaussian() * OBI_NOISE_STD;
        lastObi = Math.max(-0.95, Math.min(0.95, lastObi));

        // 3. Derive best bid/ask around mid, honoring tick size.
        double halfSpread = spread / 2.0;
        double bestBid = roundToTick(lastPrice - halfSpread);
        double bestAsk = roundToTick(lastPrice + halfSpread);
        if (bestAsk <= bestBid) {
            bestAsk = bestBid + tickSize; // guarantee positive spread
        }

        // 4. Skew top-of-book volumes to realize the target OBI:
        //    obi = (bidVol - askVol) / (bidVol + askVol)
        //    => bidVol = base*(1+obi), askVol = base*(1-obi)
        double bidTop = baseVolume * (1.0 + lastObi);
        double askTop = baseVolume * (1.0 - lastObi);

        List<PriceLevel> bids = buildSide(bestBid, -tickSize, bidTop);
        List<PriceLevel> asks = buildSide(bestAsk, tickSize, askTop);

        return new OrderBookSnapshot(token, Instant.now(), bids, asks);
    }

    /**
     * Builds one side of the book: {@code LEVELS} rungs starting at {@code topPrice},
     * stepping by {@code priceStep} each level, with volume decaying by {@link #DEPTH_DECAY}.
     */
    private List<PriceLevel> buildSide(double topPrice, double priceStep, double topVolume) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<PriceLevel> side = new ArrayList<>(LEVELS);
        for (int i = 0; i < LEVELS; i++) {
            double price = roundToTick(topPrice + priceStep * i);
            double decay = Math.pow(DEPTH_DECAY, i);
            // +/-15% jitter so levels are not perfectly smooth.
            double jitter = 0.85 + rnd.nextDouble() * 0.30;
            double volume = Math.round(topVolume * decay * jitter);
            side.add(new PriceLevel(price, volume, i + 1));
        }
        return side;
    }

    private double roundToTick(double price) {
        return Math.round(price / tickSize) * tickSize;
    }

    public String token() {
        return token;
    }
}
