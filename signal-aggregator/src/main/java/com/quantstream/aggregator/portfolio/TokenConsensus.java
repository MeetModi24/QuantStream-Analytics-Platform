package com.quantstream.aggregator.portfolio;

import java.util.List;

/**
 * Cross-strategy view of one token: how many strategies are currently long vs. short,
 * and whether they disagree.
 *
 * <p>With one strategy this is trivially consensus of 1; it becomes meaningful as the
 * strategy count grows. A {@code conflict} (some long, some short on the same token) is
 * surfaced as a dashboard alert — in a monitoring system it flags "the strategies do not
 * agree on this name" rather than gating any execution.
 *
 * @param token        symbol
 * @param longStrategies  strategies currently net-long the token
 * @param shortStrategies strategies currently net-short the token
 */
public record TokenConsensus(
        String token,
        List<String> longStrategies,
        List<String> shortStrategies
) {
    public boolean conflict() {
        return !longStrategies.isEmpty() && !shortStrategies.isEmpty();
    }

    /** Net directional agreement: +N all long, -N all short, mixed when in conflict. */
    public int consensusCount() {
        return longStrategies.size() - shortStrategies.size();
    }
}
