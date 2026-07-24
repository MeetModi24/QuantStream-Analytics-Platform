package com.quantstream.strategy.impl;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import com.quantstream.strategy.spi.AbstractStrategy;
import com.quantstream.strategy.spi.RollingWindow;

import java.util.Optional;

/**
 * Stateful mean-reversion strategy modelled on an Ornstein-Uhlenbeck process.
 *
 * <p>An O-U process is a random walk with a pull back toward a long-run mean μ; the
 * microprice of a liquid instrument over short horizons behaves much like one. Rather
 * than fit the full continuous-time parameters (θ, μ, σ), this strategy uses the
 * practical discrete proxy that HFT desks actually trade on: the <b>z-score</b> of the
 * current microprice against a rolling mean and standard deviation.
 *
 * <pre>
 *   z = (price − rollingMean) / rollingStdDev
 * </pre>
 *
 * <ul>
 *   <li>{@code z ≤ −entry} → price is unusually cheap vs. its recent mean → {@code BUY}
 *       (bet on reversion up).</li>
 *   <li>{@code z ≥ +entry} → price is unusually rich → {@code SELL} (bet on reversion
 *       down).</li>
 *   <li>{@code |z| ≤ exit} → price has reverted to the mean → {@code CLOSE} the position.</li>
 * </ul>
 *
 * <p><b>Stateful, unlike OBI:</b> it needs a warmed rolling window before it can judge
 * whether a price is "unusual", so {@link #warmupObservations()} returns the window size
 * and the base class suppresses all output until that many observations have streamed in.
 * This is the intraday "warmup instead of backfill" pattern in action.
 *
 * <p>Signals are edge-triggered on the discrete position state (FLAT / LONG / SHORT) so
 * the strategy emits once per transition, not once per tick while a band stays breached.
 */
public class OrnsteinUhlenbeckStrategy extends AbstractStrategy {

    public static final String NAME = "ornstein_uhlenbeck";

    /** Discrete position state used purely for edge-triggering emissions. */
    private enum State { FLAT, LONG, SHORT }

    private final int window;
    private final double entryZ;
    private final double exitZ;
    private final RollingWindow prices;

    private State state = State.FLAT;

    public OrnsteinUhlenbeckStrategy(String token, int window, double entryZ, double exitZ) {
        super(token);
        if (entryZ <= exitZ) {
            throw new IllegalArgumentException(
                    "entryZ must exceed exitZ (hysteresis): entryZ=" + entryZ + " exitZ=" + exitZ);
        }
        this.window = window;
        this.entryZ = entryZ;
        this.exitZ = exitZ;
        this.prices = new RollingWindow(window);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int warmupObservations() {
        return window;
    }

    @Override
    protected void updateState(Features f) {
        // Fill the window forward from the stream — runs during warmup too.
        prices.add(f.microprice());
    }

    @Override
    protected Optional<Signal> evaluate(Features f) {
        double sd = prices.stdDev();
        if (sd <= 0.0) {
            // Degenerate flat window (no dispersion) — no meaningful z-score.
            return Optional.empty();
        }
        double mean = prices.mean();
        double z = (f.microprice() - mean) / sd;

        // Exit first: if we hold a position and price has reverted, close it.
        if (state != State.FLAT && Math.abs(z) <= exitZ) {
            State closed = state;
            state = State.FLAT;
            return Optional.of(signal(
                    Signal.Action.CLOSE, f.microprice(), 1.0,
                    String.format("z %.2f within exit band %.2f — reverted, closing %s",
                            z, exitZ, closed), f));
        }

        // Entry: price is unusually cheap → buy the reversion; unusually rich → sell it.
        if (z <= -entryZ && state != State.LONG) {
            state = State.LONG;
            return Optional.of(signal(
                    Signal.Action.BUY, f.microprice(), confidence(z),
                    String.format("z %.2f <= -%.2f (cheap vs mean %.4f) — buy reversion",
                            z, entryZ, mean), f));
        }
        if (z >= entryZ && state != State.SHORT) {
            state = State.SHORT;
            return Optional.of(signal(
                    Signal.Action.SELL, f.microprice(), confidence(z),
                    String.format("z %.2f >= %.2f (rich vs mean %.4f) — sell reversion",
                            z, entryZ, mean), f));
        }
        return Optional.empty();
    }

    /**
     * Confidence in [0, 1]: 0 at the entry threshold, scaling toward 1 as the z-score
     * stretches to 3σ and beyond (capped). A more extreme deviation is a stronger
     * reversion bet.
     */
    private double confidence(double z) {
        double excess = Math.abs(z) - entryZ;
        double range = 3.0 - entryZ;
        if (range <= 0.0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, excess / range));
    }
}
