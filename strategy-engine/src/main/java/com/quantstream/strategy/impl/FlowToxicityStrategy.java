package com.quantstream.strategy.impl;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import com.quantstream.strategy.spi.AbstractStrategy;
import com.quantstream.strategy.spi.RollingWindow;

import java.util.Optional;

/**
 * Stateful order-flow-persistence strategy — a volume-free analog of VPIN-style flow
 * toxicity built on the {@code obi} feature.
 *
 * <p>True VPIN buckets signed <em>volume</em> to gauge how one-sided (informed / "toxic")
 * recent flow is; this pipeline exposes no volume feature, so this strategy uses the signed
 * order-book imbalance as the flow proxy and measures its <b>persistence</b> via a rolling
 * mean:
 *
 * <pre>
 *   flow = mean(OBI over the last N observations)   ∈ [−1, 1]
 * </pre>
 *
 * <ul>
 *   <li>{@code flow ≥ +entry} → sustained buy-side pressure → {@code BUY}.</li>
 *   <li>{@code flow ≤ −entry} → sustained sell-side pressure → {@code SELL}.</li>
 *   <li>{@code |flow| ≤ exit} → pressure has balanced out → {@code CLOSE}.</li>
 * </ul>
 *
 * <p><b>Deliberately distinct from {@link ObiMarketMakingStrategy}:</b> that strategy is
 * stateless and reacts to an <em>instantaneous</em> imbalance spike (a single tick crossing
 * a level). This one is stateful and only acts when imbalance stays one-sided across a whole
 * window — persistence, not a spike. A momentary OBI jolt that mean-reverts within the window
 * never trips it, so the two frequently disagree, which is exactly the cross-strategy signal
 * the dashboard is meant to surface.
 *
 * <p><b>Stateful:</b> {@link #warmupObservations()} returns the window size and the base class
 * suppresses output until the window is warm — the intraday "warmup instead of backfill"
 * pattern. Signals are edge-triggered on the discrete position state (FLAT / LONG / SHORT)
 * with an {@code entry > exit} hysteresis band, so the strategy emits once per regime change
 * rather than once per tick.
 */
public class FlowToxicityStrategy extends AbstractStrategy {

    public static final String NAME = "flow_toxicity";

    /** Discrete position state used purely for edge-triggering emissions. */
    private enum State { FLAT, LONG, SHORT }

    private final int window;
    private final double entry;
    private final double exit;
    private final RollingWindow flow;

    private State state = State.FLAT;

    public FlowToxicityStrategy(String token, int window, double entry, double exit) {
        super(token);
        if (entry <= exit) {
            throw new IllegalArgumentException(
                    "entry must exceed exit (hysteresis): entry=" + entry + " exit=" + exit);
        }
        if (entry <= 0.0 || entry > 1.0) {
            throw new IllegalArgumentException(
                    "entry must be in (0, 1] (OBI is bounded to [-1, 1]): entry=" + entry);
        }
        this.window = window;
        this.entry = entry;
        this.exit = exit;
        this.flow = new RollingWindow(window);
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
        // Accumulate signed imbalance forward from the stream — runs during warmup too.
        flow.add(f.obi());
    }

    @Override
    protected Optional<Signal> evaluate(Features f) {
        double flowMean = flow.mean();

        // Exit first: if we hold a position and pressure has balanced out, close it.
        if (state != State.FLAT && Math.abs(flowMean) <= exit) {
            State closed = state;
            state = State.FLAT;
            return Optional.of(signal(
                    Signal.Action.CLOSE, f.microprice(), 1.0,
                    String.format("mean OBI %.3f within exit band %.3f — flow balanced, closing %s",
                            flowMean, exit, closed), f));
        }

        // Entry: sustained one-sided flow opens a position in its direction.
        if (flowMean >= entry && state != State.LONG) {
            state = State.LONG;
            return Optional.of(signal(
                    Signal.Action.BUY, f.microprice(), confidence(flowMean),
                    String.format("mean OBI %.3f >= %.3f — sustained buy pressure",
                            flowMean, entry), f));
        }
        if (flowMean <= -entry && state != State.SHORT) {
            state = State.SHORT;
            return Optional.of(signal(
                    Signal.Action.SELL, f.microprice(), confidence(flowMean),
                    String.format("mean OBI %.3f <= -%.3f — sustained sell pressure",
                            flowMean, entry), f));
        }
        return Optional.empty();
    }

    /**
     * Confidence in [0, 1]: 0 at the entry threshold, scaling linearly to 1 as the mean
     * imbalance approaches its bound of 1.0. More one-sided flow is a stronger conviction.
     */
    private double confidence(double flowMean) {
        double magnitude = Math.abs(flowMean);
        double range = 1.0 - entry;
        if (range <= 0.0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, (magnitude - entry) / range));
    }
}
