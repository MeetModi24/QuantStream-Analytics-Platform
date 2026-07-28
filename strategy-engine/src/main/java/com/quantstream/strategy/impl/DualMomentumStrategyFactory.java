package com.quantstream.strategy.impl;

import com.quantstream.strategy.spi.Strategy;
import com.quantstream.strategy.spi.StrategyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory for {@link DualMomentumStrategy}. Auto-discovered as a Spring bean; the fast/slow
 * window sizes, entry/exit thresholds (in bps), and enabled flag are all config-driven so
 * the strategy can be tuned or turned off without a code change.
 */
@Component
public class DualMomentumStrategyFactory implements StrategyFactory {

    private final boolean enabled;
    private final int fastWindow;
    private final int slowWindow;
    private final double entryBps;
    private final double exitBps;

    public DualMomentumStrategyFactory(
            @Value("${quantstream.strategy.momentum.enabled:true}") boolean enabled,
            @Value("${quantstream.strategy.momentum.fast-window:60}") int fastWindow,
            @Value("${quantstream.strategy.momentum.slow-window:300}") int slowWindow,
            @Value("${quantstream.strategy.momentum.entry-bps:5.0}") double entryBps,
            @Value("${quantstream.strategy.momentum.exit-bps:1.0}") double exitBps) {
        this.enabled = enabled;
        this.fastWindow = fastWindow;
        this.slowWindow = slowWindow;
        this.entryBps = entryBps;
        this.exitBps = exitBps;
    }

    @Override
    public String name() {
        return DualMomentumStrategy.NAME;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Strategy create(String token) {
        return new DualMomentumStrategy(token, fastWindow, slowWindow, entryBps, exitBps);
    }
}
