package com.quantstream.strategy.spi;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;

import java.util.Optional;

/**
 * Base class handling the boilerplate common to all strategies: binding to a token,
 * counting observations, and enforcing the warmup gate.
 *
 * <p>Subclasses implement {@link #evaluate(Features)} with pure strategy logic and
 * may override {@link #updateState(Features)} to maintain a rolling window. The
 * warmup gate is enforced here so a subclass can never accidentally emit before it
 * has enough data.
 */
public abstract class AbstractStrategy implements Strategy {

    private final String token;
    private long observations = 0;

    protected AbstractStrategy(String token) {
        this.token = token;
    }

    @Override
    public final String token() {
        return token;
    }

    @Override
    public final long observationsSeen() {
        return observations;
    }

    @Override
    public final Optional<Signal> onFeatures(Features features) {
        updateState(features);
        observations++;
        if (!isWarm()) {
            return Optional.empty();
        }
        return evaluate(features);
    }

    /**
     * Updates any internal rolling state from the new observation. Default is a no-op
     * (stateless strategies). Called before the warmup check, so the window fills
     * during warmup.
     */
    protected void updateState(Features features) {
        // no-op for stateless strategies
    }

    /**
     * Pure strategy decision, called only once the strategy is warm.
     *
     * @return a signal if the strategy fires, otherwise empty
     */
    protected abstract Optional<Signal> evaluate(Features features);

    /** Convenience for building a signal attributed to this strategy and token. */
    protected Signal signal(Signal.Action action, double price, double confidence,
                            String reason, Features src) {
        return new Signal(name(), token, action, price, confidence, reason, src.timestamp());
    }
}
