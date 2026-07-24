package com.quantstream.strategy.spi;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;

import java.util.Optional;

/**
 * A single quantitative strategy instance bound to ONE token.
 *
 * <p>Instantiating one strategy object per token is deliberate: it lets stateful
 * strategies (pairs, Ornstein-Uhlenbeck, VPIN) own a private rolling window built
 * forward from the stream, with no shared mutable state and no cross-token locking.
 * Stateless strategies (OBI, microprice) simply ignore the accumulated state. This
 * is the design that scales cleanly from 1 to 30,000 tokens — see
 * {@code docs/planning/03-historical-data-and-retention.md}.
 *
 * <p>Implementations are NOT required to be thread-safe: the engine guarantees that
 * {@link #onFeatures} is called for a given (token, strategy) instance from a single
 * thread at a time (per-partition Kafka ordering).
 */
public interface Strategy {

    /** Stable, unique strategy name (e.g. {@code "obi_market_making"}). */
    String name();

    /** The token this instance is bound to. */
    String token();

    /**
     * Number of observations this strategy must accumulate before it can emit
     * signals. Stateless strategies return 0. While {@code observationsSeen() <
     * warmupObservations()} the strategy is "warming up" and must not emit — this
     * is the intraday replacement for database backfill.
     */
    int warmupObservations();

    /** How many feature observations this instance has processed so far. */
    long observationsSeen();

    /** True once enough observations have been seen to emit signals. */
    default boolean isWarm() {
        return observationsSeen() >= warmupObservations();
    }

    /**
     * Processes one feature observation and optionally emits a signal.
     *
     * <p>Implementations should update any internal state first, then decide. They
     * must return {@link Optional#empty()} while still warming up.
     *
     * @param features latest features for {@link #token()}
     * @return a signal if the strategy fired, otherwise empty
     */
    Optional<Signal> onFeatures(Features features);
}
