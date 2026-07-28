package com.quantstream.strategy.impl;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import com.quantstream.strategy.spi.AbstractStrategy;
import com.quantstream.strategy.spi.RollingWindow;

import java.util.Optional;

/**
 * Stateful trend-following strategy based on a dual moving-average crossover of the
 * microprice.
 *
 * <p>It keeps two rolling means of the microprice — a short/fast window and a long/slow
 * window — and trades the gap between them, normalised by the slow mean:
 *
 * <pre>
 *   spreadBps = 10_000 · (fastMean − slowMean) / slowMean
 * </pre>
 *
 * <ul>
 *   <li>{@code spreadBps ≥ +entry} → fast has pulled decisively above slow → uptrend →
 *       {@code BUY} (bet the trend continues).</li>
 *   <li>{@code spreadBps ≤ −entry} → fast below slow → downtrend → {@code SELL}.</li>
 *   <li>{@code |spreadBps| ≤ exit} → the averages have converged → trend spent →
 *       {@code CLOSE}.</li>
 * </ul>
 *
 * <p><b>Deliberately the opposite thesis to {@link OrnsteinUhlenbeckStrategy}:</b>
 * momentum bets a move <em>continues</em>, mean-reversion bets it <em>reverts</em>. Running
 * both over the same token surfaces genuine cross-strategy agreement and conflict on the
 * dashboard rather than two variations of the same idea.
 *
 * <p><b>Stateful:</b> both windows must be warm before a crossover is meaningful, so
 * {@link #warmupObservations()} returns the slow-window size and the base class suppresses
 * output until then — the same intraday "warmup instead of backfill" pattern used by O-U.
 *
 * <p>Signals are edge-triggered on the discrete position state (FLAT / LONG / SHORT), so
 * the strategy emits once per regime change, not once per tick while a trend persists. The
 * {@code entry > exit} neutral band provides hysteresis that avoids whipsaw churn when the
 * averages hover near each other.
 */
public class DualMomentumStrategy extends AbstractStrategy {

    public static final String NAME = "dual_momentum";

    /** Discrete position state used purely for edge-triggering emissions. */
    private enum State { FLAT, LONG, SHORT }

    private final int slowWindow;
    private final double entryBps;
    private final double exitBps;
    private final RollingWindow fast;
    private final RollingWindow slow;

    private State state = State.FLAT;

    public DualMomentumStrategy(String token, int fastWindow, int slowWindow,
                                double entryBps, double exitBps) {
        super(token);
        if (fastWindow >= slowWindow) {
            throw new IllegalArgumentException(
                    "fastWindow must be smaller than slowWindow: fast=" + fastWindow
                            + " slow=" + slowWindow);
        }
        if (entryBps <= exitBps) {
            throw new IllegalArgumentException(
                    "entryBps must exceed exitBps (hysteresis): entryBps=" + entryBps
                            + " exitBps=" + exitBps);
        }
        this.slowWindow = slowWindow;
        this.entryBps = entryBps;
        this.exitBps = exitBps;
        this.fast = new RollingWindow(fastWindow);
        this.slow = new RollingWindow(slowWindow);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int warmupObservations() {
        // The slow window is the binding constraint; once it is full, both means are valid.
        return slowWindow;
    }

    @Override
    protected void updateState(Features f) {
        // Fill both windows forward from the stream — runs during warmup too.
        fast.add(f.microprice());
        slow.add(f.microprice());
    }

    @Override
    protected Optional<Signal> evaluate(Features f) {
        double slowMean = slow.mean();
        if (slowMean <= 0.0) {
            return Optional.empty();
        }
        double spreadBps = 10_000.0 * (fast.mean() - slowMean) / slowMean;

        // Exit first: if we hold a position and the averages have converged, close it.
        if (state != State.FLAT && Math.abs(spreadBps) <= exitBps) {
            State closed = state;
            state = State.FLAT;
            return Optional.of(signal(
                    Signal.Action.CLOSE, f.microprice(), 1.0,
                    String.format("MA spread %.2fbps within exit band %.2f — trend spent, closing %s",
                            spreadBps, exitBps, closed), f));
        }

        // Entry: fast decisively above slow → ride the uptrend; below → ride the downtrend.
        if (spreadBps >= entryBps && state != State.LONG) {
            state = State.LONG;
            return Optional.of(signal(
                    Signal.Action.BUY, f.microprice(), confidence(spreadBps),
                    String.format("fast > slow by %.2fbps >= %.2f — uptrend, buy momentum",
                            spreadBps, entryBps), f));
        }
        if (spreadBps <= -entryBps && state != State.SHORT) {
            state = State.SHORT;
            return Optional.of(signal(
                    Signal.Action.SELL, f.microprice(), confidence(spreadBps),
                    String.format("fast < slow by %.2fbps <= -%.2f — downtrend, sell momentum",
                            spreadBps, entryBps), f));
        }
        return Optional.empty();
    }

    /**
     * Confidence in [0, 1]: 0 at the entry threshold, scaling toward 1 as the crossover
     * widens to {@code 3 × entry} and beyond (capped). A wider separation is a stronger
     * trend.
     */
    private double confidence(double spreadBps) {
        double excess = Math.abs(spreadBps) - entryBps;
        double range = 2.0 * entryBps; // reaches full confidence at 3× the entry band
        if (range <= 0.0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, excess / range));
    }
}
