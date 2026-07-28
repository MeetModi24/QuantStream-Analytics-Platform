package com.quantstream.strategy.impl;

import com.quantstream.strategy.spi.Strategy;
import com.quantstream.strategy.spi.StrategyFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Factory for {@link KalmanTrendStrategy}. Auto-discovered as a Spring bean; the warmup,
 * process/measurement noise, entry/exit thresholds (in bps), and enabled flag are all
 * config-driven so the filter can be tuned or turned off without a code change.
 */
@Component
public class KalmanTrendStrategyFactory implements StrategyFactory {

    private final boolean enabled;
    private final int warmup;
    private final double processNoise;
    private final double measurementNoise;
    private final double entryBps;
    private final double exitBps;

    public KalmanTrendStrategyFactory(
            @Value("${quantstream.strategy.kalman.enabled:true}") boolean enabled,
            @Value("${quantstream.strategy.kalman.warmup:120}") int warmup,
            @Value("${quantstream.strategy.kalman.process-noise:0.01}") double processNoise,
            @Value("${quantstream.strategy.kalman.measurement-noise:1.0}") double measurementNoise,
            @Value("${quantstream.strategy.kalman.entry-bps:3.0}") double entryBps,
            @Value("${quantstream.strategy.kalman.exit-bps:0.5}") double exitBps) {
        this.enabled = enabled;
        this.warmup = warmup;
        this.processNoise = processNoise;
        this.measurementNoise = measurementNoise;
        this.entryBps = entryBps;
        this.exitBps = exitBps;
    }

    @Override
    public String name() {
        return KalmanTrendStrategy.NAME;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Strategy create(String token) {
        return new KalmanTrendStrategy(
                token, warmup, processNoise, measurementNoise, entryBps, exitBps);
    }
}
