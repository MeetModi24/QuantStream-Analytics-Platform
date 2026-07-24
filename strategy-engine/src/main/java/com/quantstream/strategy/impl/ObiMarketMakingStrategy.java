package com.quantstream.strategy.impl;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import com.quantstream.strategy.spi.AbstractStrategy;

import java.util.Optional;

/**
 * Stateless order-book-imbalance strategy.
 *
 * <p>Interprets a strong imbalance as short-term directional pressure:
 * <ul>
 *   <li>OBI ≥ +threshold → buy pressure → {@code BUY}</li>
 *   <li>OBI ≤ −threshold → sell pressure → {@code SELL}</li>
 * </ul>
 *
 * <p>To avoid emitting a signal on every tick while imbalance stays elevated, it
 * fires only on a <em>state change</em> (edge-triggered): a new BUY is emitted when
 * OBI first crosses the upper threshold, and not again until OBI has returned to the
 * neutral band and crossed once more. This keeps the signal stream meaningful rather
 * than a per-second repeat.
 *
 * <p>Stateless w.r.t. price history (warmup = 0); the only state is the last emitted
 * side, used purely for edge detection.
 */
public class ObiMarketMakingStrategy extends AbstractStrategy {

    public static final String NAME = "obi_market_making";

    private final double threshold;

    /** Last side we emitted, so we only fire on transitions. */
    private Signal.Action lastSide = null;

    public ObiMarketMakingStrategy(String token, double threshold) {
        super(token);
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int warmupObservations() {
        return 0; // stateless
    }

    @Override
    protected Optional<Signal> evaluate(Features f) {
        double obi = f.obi();

        if (obi >= threshold) {
            if (lastSide != Signal.Action.BUY) {
                lastSide = Signal.Action.BUY;
                return Optional.of(signal(
                        Signal.Action.BUY, f.microprice(), confidence(obi),
                        String.format("OBI %.3f >= %.3f (buy pressure)", obi, threshold), f));
            }
        } else if (obi <= -threshold) {
            if (lastSide != Signal.Action.SELL) {
                lastSide = Signal.Action.SELL;
                return Optional.of(signal(
                        Signal.Action.SELL, f.microprice(), confidence(obi),
                        String.format("OBI %.3f <= -%.3f (sell pressure)", obi, threshold), f));
            }
        } else {
            // Returned to neutral band — reset so the next crossing fires again.
            lastSide = null;
        }
        return Optional.empty();
    }

    /**
     * Maps imbalance magnitude to a confidence in [0, 1]: at the threshold confidence
     * is ~0, scaling linearly to 1.0 as |OBI| approaches its max of 1.0.
     */
    private double confidence(double obi) {
        double magnitude = Math.abs(obi);
        double range = 1.0 - threshold;
        if (range <= 0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, (magnitude - threshold) / range));
    }
}
