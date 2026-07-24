package com.quantstream.strategy.engine;

import com.quantstream.common.config.TokenRegistry;
import com.quantstream.common.model.Features;
import com.quantstream.common.model.Signal;
import com.quantstream.strategy.spi.Strategy;
import com.quantstream.strategy.spi.StrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the matrix of running strategy instances: one {@link Strategy} object per
 * (token, strategy-factory). Routes each incoming {@link Features} observation to
 * every strategy bound to that token and collects any emitted signals.
 *
 * <p>Instances are created lazily on first sight of a token, so tokens that appear at
 * runtime (or a growing enabled set) are handled without restart. All enabled tokens
 * from the registry are also pre-created at startup so warmup begins immediately.
 *
 * <p>Per-token instance lists are only mutated under {@code computeIfAbsent}, and a
 * given token's features arrive on a single partition/thread, so no per-strategy
 * locking is needed.
 */
@Component
public class StrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final List<StrategyFactory> enabledFactories;
    private final Map<String, List<Strategy>> strategiesByToken = new ConcurrentHashMap<>();

    public StrategyEngine(List<StrategyFactory> factories, TokenRegistry tokenRegistry) {
        this.enabledFactories = factories.stream()
                .filter(StrategyFactory::enabled)
                .toList();

        log.info("Strategy engine starting with {} enabled strateg(ies): {}",
                enabledFactories.size(),
                enabledFactories.stream().map(StrategyFactory::name).toList());

        // Pre-create instances for all enabled tokens so stateful strategies begin
        // warming up from the first observation.
        for (var token : tokenRegistry.enabledTokens()) {
            instancesFor(token.symbol());
        }
    }

    /**
     * Routes one observation to all strategies for its token, returning every signal
     * that fired.
     */
    public List<Signal> onFeatures(Features features) {
        List<Strategy> strategies = instancesFor(features.token());
        List<Signal> signals = new ArrayList<>();
        for (Strategy strategy : strategies) {
            try {
                strategy.onFeatures(features).ifPresent(signals::add);
            } catch (Exception e) {
                log.error("Strategy {} failed on token {}",
                        strategy.name(), features.token(), e);
            }
        }
        return signals;
    }

    private List<Strategy> instancesFor(String token) {
        return strategiesByToken.computeIfAbsent(token, t -> {
            List<Strategy> list = new ArrayList<>(enabledFactories.size());
            for (StrategyFactory factory : enabledFactories) {
                list.add(factory.create(t));
            }
            log.debug("Instantiated {} strategies for token {}", list.size(), t);
            return list;
        });
    }
}
