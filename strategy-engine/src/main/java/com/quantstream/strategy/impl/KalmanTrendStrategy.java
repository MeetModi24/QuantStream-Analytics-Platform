package com.quantstream.strategy.impl;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import com.quantstream.strategy.spi.AbstractStrategy;

import java.util.Optional;

/**
 * Stateful trend-following strategy driven by a constant-velocity <b>Kalman filter</b> over
 * the microprice (the {@code kalman_trend} idea from the token-selection planning doc).
 *
 * <p>The filter models the microprice as a hidden 2-state system — a true {@code level} and
 * a {@code velocity} (per-tick drift) — evolving as a local-linear-trend random walk and
 * observed through noisy measurements:
 *
 * <pre>
 *   state  x = [level, velocity]      transition F = [[1, 1], [0, 1]]
 *   measurement z = level + noise     observation  H = [1, 0]
 * </pre>
 *
 * Each tick predicts the state forward, then corrects it by the innovation {@code z − level}
 * weighted by the Kalman gain. The recovered {@code velocity}, normalised against the level,
 * is the tradeable trend:
 *
 * <pre>
 *   velocityBps = 10_000 · velocity / level
 * </pre>
 *
 * <ul>
 *   <li>{@code velocityBps ≥ +entry} → estimated uptrend → {@code BUY}.</li>
 *   <li>{@code velocityBps ≤ −entry} → estimated downtrend → {@code SELL}.</li>
 *   <li>{@code |velocityBps| ≤ exit} → drift has flattened → {@code CLOSE}.</li>
 * </ul>
 *
 * <p><b>Distinct from {@link DualMomentumStrategy}:</b> both are trend-followers, but the
 * dual-MA crossover is a fixed-window smoother whereas the Kalman filter is an <em>adaptive</em>
 * recursive estimator — the process/measurement noise ratio ({@code q/r}) governs how quickly
 * it trusts a new move versus the smoothed history, and its covariance self-tunes the gain as
 * it converges. No rolling buffer is kept; the estimate is carried recursively.
 *
 * <p><b>Stateful:</b> the filter needs a short settling period before its velocity estimate is
 * trustworthy, so {@link #warmupObservations()} returns a configurable warmup and the base
 * class suppresses output until then (the intraday "warmup instead of backfill" pattern). The
 * filter itself runs during warmup so it is already converged when signalling begins.
 *
 * <p>Signals are edge-triggered on the discrete position state (FLAT / LONG / SHORT) with an
 * {@code entry > exit} hysteresis band, so the strategy emits once per regime change rather
 * than once per tick.
 */
public class KalmanTrendStrategy extends AbstractStrategy {

    public static final String NAME = "kalman_trend";

    /** Discrete position state used purely for edge-triggering emissions. */
    private enum State { FLAT, LONG, SHORT }

    private final int warmup;
    private final double processNoise;      // q — how freely level/velocity may drift per step
    private final double measurementNoise;  // r — assumed variance of a single microprice tick
    private final double entryBps;
    private final double exitBps;

    // Kalman state: x = [level, velocity].
    private double level;
    private double velocity;
    // Estimate covariance P (symmetric 2×2).
    private double p00, p01, p10, p11;
    private boolean initialized = false;

    private State state = State.FLAT;

    public KalmanTrendStrategy(String token, int warmup, double processNoise,
                               double measurementNoise, double entryBps, double exitBps) {
        super(token);
        if (warmup <= 0) {
            throw new IllegalArgumentException("warmup must be positive: " + warmup);
        }
        if (processNoise <= 0.0 || measurementNoise <= 0.0) {
            throw new IllegalArgumentException(
                    "processNoise and measurementNoise must be positive: q=" + processNoise
                            + " r=" + measurementNoise);
        }
        if (entryBps <= exitBps) {
            throw new IllegalArgumentException(
                    "entryBps must exceed exitBps (hysteresis): entryBps=" + entryBps
                            + " exitBps=" + exitBps);
        }
        this.warmup = warmup;
        this.processNoise = processNoise;
        this.measurementNoise = measurementNoise;
        this.entryBps = entryBps;
        this.exitBps = exitBps;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int warmupObservations() {
        return warmup;
    }

    @Override
    protected void updateState(Features f) {
        // One predict+update step of the filter — runs during warmup too so it is converged
        // by the time signalling begins.
        double z = f.microprice();

        if (!initialized) {
            // Seed the level at the first observation, zero initial velocity, and a diffuse
            // covariance so the first few measurements move the estimate freely.
            level = z;
            velocity = 0.0;
            p00 = measurementNoise;
            p01 = 0.0;
            p10 = 0.0;
            p11 = measurementNoise;
            initialized = true;
            return;
        }

        // --- Predict: x' = F x, P' = F P Fᵀ + Q, with F = [[1,1],[0,1]], Q = diag(q, q).
        double predLevel = level + velocity;
        double predVel = velocity;
        double np00 = p00 + p01 + p10 + p11 + processNoise;
        double np01 = p01 + p11;
        double np10 = p10 + p11;
        double np11 = p11 + processNoise;

        // --- Update with z through H = [1, 0].
        double innovation = z - predLevel;
        double s = np00 + measurementNoise;   // innovation covariance
        double k0 = np00 / s;                 // Kalman gain
        double k1 = np10 / s;

        level = predLevel + k0 * innovation;
        velocity = predVel + k1 * innovation;

        // P = (I − K H) P', with K H = [[k0,0],[k1,0]].
        p00 = (1.0 - k0) * np00;
        p01 = (1.0 - k0) * np01;
        p10 = np10 - k1 * np00;
        p11 = np11 - k1 * np01;
    }

    @Override
    protected Optional<Signal> evaluate(Features f) {
        if (level <= 0.0) {
            return Optional.empty();
        }
        double velocityBps = 10_000.0 * velocity / level;

        // Exit first: if we hold a position and the estimated drift has flattened, close it.
        if (state != State.FLAT && Math.abs(velocityBps) <= exitBps) {
            State closed = state;
            state = State.FLAT;
            return Optional.of(signal(
                    Signal.Action.CLOSE, f.microprice(), 1.0,
                    String.format("Kalman velocity %.2fbps within exit band %.2f — trend flat, closing %s",
                            velocityBps, exitBps, closed), f));
        }

        // Entry: a decisively positive/negative estimated velocity opens a trend position.
        if (velocityBps >= entryBps && state != State.LONG) {
            state = State.LONG;
            return Optional.of(signal(
                    Signal.Action.BUY, f.microprice(), confidence(velocityBps),
                    String.format("Kalman velocity %.2fbps >= %.2f — estimated uptrend, buy",
                            velocityBps, entryBps), f));
        }
        if (velocityBps <= -entryBps && state != State.SHORT) {
            state = State.SHORT;
            return Optional.of(signal(
                    Signal.Action.SELL, f.microprice(), confidence(velocityBps),
                    String.format("Kalman velocity %.2fbps <= -%.2f — estimated downtrend, sell",
                            velocityBps, entryBps), f));
        }
        return Optional.empty();
    }

    /**
     * Confidence in [0, 1]: 0 at the entry threshold, scaling toward 1 as the estimated
     * velocity reaches {@code 3 × entry} and beyond (capped). A steeper drift is a stronger
     * trend.
     */
    private double confidence(double velocityBps) {
        double excess = Math.abs(velocityBps) - entryBps;
        double range = 2.0 * entryBps; // reaches full confidence at 3× the entry band
        if (range <= 0.0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, excess / range));
    }
}
