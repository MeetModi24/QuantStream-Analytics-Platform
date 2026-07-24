package com.quantstream.strategy.impl;

import com.quantstream.strategy.spi.Strategy;
import com.quantstream.strategy.spi.StrategyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory for {@link ObiMarketMakingStrategy}. Registered as a Spring bean so the
 * engine auto-discovers it; the threshold and enabled flag are config-driven.
 */
@Component
public class ObiMarketMakingStrategyFactory implements StrategyFactory {

    private final boolean enabled;
    private final double threshold;

    public ObiMarketMakingStrategyFactory(
            @Value("${quantstream.strategy.obi.enabled:true}") boolean enabled,
            @Value("${quantstream.strategy.obi.threshold:0.5}") double threshold) {
        this.enabled = enabled;
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return ObiMarketMakingStrategy.NAME;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Strategy create(String token) {
        return new ObiMarketMakingStrategy(token, threshold);
    }
}
