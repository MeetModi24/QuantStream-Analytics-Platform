package com.quantstream.strategy.impl;

import com.quantstream.strategy.spi.Strategy;
import com.quantstream.strategy.spi.StrategyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory for {@link FlowToxicityStrategy}. Auto-discovered as a Spring bean; the window
 * size, entry/exit thresholds (mean-OBI levels), and enabled flag are all config-driven so
 * the strategy can be tuned or turned off without a code change.
 */
@Component
public class FlowToxicityStrategyFactory implements StrategyFactory {

    private final boolean enabled;
    private final int window;
    private final double entry;
    private final double exit;

    public FlowToxicityStrategyFactory(
            @Value("${quantstream.strategy.flow.enabled:true}") boolean enabled,
            @Value("${quantstream.strategy.flow.window:120}") int window,
            @Value("${quantstream.strategy.flow.entry:0.3}") double entry,
            @Value("${quantstream.strategy.flow.exit:0.1}") double exit) {
        this.enabled = enabled;
        this.window = window;
        this.entry = entry;
        this.exit = exit;
    }

    @Override
    public String name() {
        return FlowToxicityStrategy.NAME;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Strategy create(String token) {
        return new FlowToxicityStrategy(token, window, entry, exit);
    }
}
