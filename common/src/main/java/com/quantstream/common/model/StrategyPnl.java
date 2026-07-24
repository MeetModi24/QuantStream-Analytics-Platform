package com.quantstream.common.model;

import java.time.Instant;

/**
 * A per-strategy performance snapshot, aggregated across all tokens that strategy trades.
 *
 * <p>Emitted by the Signal Aggregator on the {@code strategy-pnl} Kafka topic on a fixed
 * interval (so PnL updates live even when no new signal fires). All figures are from the
 * simulated paper-trading portfolio — QuantStream is a monitoring system, not a trading
 * system.
 *
 * @param strategy       strategy name
 * @param realizedPnl    sum of realized PnL across the strategy's tokens
 * @param unrealizedPnl  sum of mark-to-market PnL across open positions
 * @param totalPnl       realized + unrealized
 * @param numTrades      count of simulated fills executed
 * @param winRate        fraction of closed trades with positive realized PnL, in [0, 1]
 * @param timestamp      time this snapshot was produced
 */
public record StrategyPnl(
        String strategy,
        double realizedPnl,
        double unrealizedPnl,
        double totalPnl,
        long numTrades,
        double winRate,
        Instant timestamp
) {
}
