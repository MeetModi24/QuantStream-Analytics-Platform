package com.quantstream.common.model;

import java.time.Instant;

/**
 * A paper-trading position snapshot for one strategy on one token.
 *
 * <p>Emitted by the Signal Aggregator on the {@code positions} Kafka topic. QuantStream
 * is a monitoring system, not a trading system — this is a <em>simulated</em> position
 * (fills modelled at signal price) used to score strategy quality on the dashboard.
 *
 * @param strategy       strategy that owns this position
 * @param token          symbol
 * @param netPosition    signed quantity held: positive = long, negative = short
 * @param avgEntryPrice  volume-weighted average entry price of the open position
 * @param realizedPnl    locked-in PnL from trades that reduced/closed the position
 * @param unrealizedPnl  mark-to-market PnL on the open position vs. latest microprice
 * @param timestamp      time this snapshot was produced
 */
public record Position(
        String strategy,
        String token,
        double netPosition,
        double avgEntryPrice,
        double realizedPnl,
        double unrealizedPnl,
        Instant timestamp
) {
}
