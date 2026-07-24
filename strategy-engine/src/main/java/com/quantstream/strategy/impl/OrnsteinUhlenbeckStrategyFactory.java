package com.quantstream.strategy.impl;

import com.quantstream.strategy.spi.Strategy;
import com.quantstream.strategy.spi.StrategyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory for {@link OrnsteinUhlenbeckStrategy}. Auto-discovered as a Spring bean; the
 * window size, entry/exit z-thresholds, and enabled flag are all config-driven so the
 * strategy can be tuned or turned off without a code change.
 */
@Component
public class OrnsteinUhlenbeckStrategyFactory implements StrategyFactory {

    private final boolean enabled;
    private final int window;
    private final double entryZ;
    private final double exitZ;

    public OrnsteinUhlenbeckStrategyFactory(
            @Value("${quantstream.strategy.ou.enabled:true}") boolean enabled,
            @Value("${quantstream.strategy.ou.window:600}") int window,
            @Value("${quantstream.strategy.ou.entry-z:2.0}") double entryZ,
            @Value("${quantstream.strategy.ou.exit-z:0.5}") double exitZ) {
        this.enabled = enabled;
        this.window = window;
        this.entryZ = entryZ;
        this.exitZ = exitZ;
    }

    @Override
    public String name() {
        return OrnsteinUhlenbeckStrategy.NAME;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Strategy create(String token) {
        return new OrnsteinUhlenbeckStrategy(token, window, entryZ, exitZ);
    }
}
