package com.quantstream.strategy.spi;

/**
 * Creates per-token {@link Strategy} instances.
 *
 * <p>The engine discovers all {@code StrategyFactory} beans on the classpath and, for
 * each enabled token, asks every factory for an instance. Adding a new strategy to
 * the system is therefore just adding a new factory bean — no engine changes. This is
 * the extension point that lets the 30 strategies be built incrementally.
 */
public interface StrategyFactory {

    /** Stable, unique strategy name — matches {@link Strategy#name()}. */
    String name();

    /** Whether this strategy should run at all (config-driven enable/disable). */
    default boolean enabled() {
        return true;
    }

    /**
     * Creates a fresh strategy instance bound to the given token, with its own state.
     */
    Strategy create(String token);
}
