package com.quantstream.aggregator.portfolio;

import com.quantstream.common.model.Signal;

/**
 * Mutable paper-trading position for one strategy on one token.
 *
 * <p>Models simulated fills at signal price. Handles the three cases every position
 * keeper must get right:
 * <ul>
 *   <li><b>Open / increase</b> (same side) — update the volume-weighted average entry.
 *   <li><b>Reduce / close</b> (opposite side, ≤ current size) — realize PnL on the
 *       closed quantity; average entry of the remainder is unchanged.
 *   <li><b>Flip</b> (opposite side, &gt; current size) — realize PnL on the whole old
 *       position, then open the remainder on the new side at the fill price.
 * </ul>
 *
 * <p>Not thread-safe on its own; {@link Portfolio} guards access.
 */
public final class PaperPosition {

    private final String strategy;
    private final String token;

    /** Signed quantity: positive = long, negative = short. */
    private double netPosition;
    /** Volume-weighted average entry price of the currently-open position. */
    private double avgEntryPrice;
    private double realizedPnl;

    // Win-rate accounting: a "closed trade" is any fill that reduces/closes a position.
    private long numTrades;
    private long closedTrades;
    private long winningClosedTrades;

    public PaperPosition(String strategy, String token) {
        this.strategy = strategy;
        this.token = token;
    }

    /**
     * Applies a simulated fill for a signal. {@code lot} is the (positive) quantity per
     * fill; the sign is taken from the action. {@code CLOSE} flattens the position.
     */
    public void apply(Signal.Action action, double fillPrice, double lot) {
        numTrades++;
        if (action == Signal.Action.CLOSE) {
            if (netPosition != 0.0) {
                realizeAndRecord(netPosition, fillPrice);
                netPosition = 0.0;
                avgEntryPrice = 0.0;
            }
            return;
        }

        double signedQty = (action == Signal.Action.BUY) ? lot : -lot;
        applySignedFill(signedQty, fillPrice);
    }

    private void applySignedFill(double signedQty, double fillPrice) {
        if (netPosition == 0.0 || sameSign(netPosition, signedQty)) {
            // Opening or increasing: volume-weighted average entry.
            double newNet = netPosition + signedQty;
            avgEntryPrice = (avgEntryPrice * Math.abs(netPosition)
                    + fillPrice * Math.abs(signedQty)) / Math.abs(newNet);
            netPosition = newNet;
            return;
        }

        // Opposite side: reduce, close, or flip.
        double closingQty = Math.min(Math.abs(signedQty), Math.abs(netPosition));
        double closedSigned = Math.copySign(closingQty, netPosition);
        realizeAndRecord(closedSigned, fillPrice);

        double remainder = Math.abs(signedQty) - closingQty;
        netPosition += signedQty;
        if (Math.abs(netPosition) < 1e-9) {
            netPosition = 0.0;
            avgEntryPrice = 0.0;
        } else if (remainder > 0.0) {
            // Flipped to the other side: the remainder opens a fresh position.
            avgEntryPrice = fillPrice;
        }
        // (pure reduction leaves avgEntryPrice unchanged)
    }

    /** Realizes PnL for closing {@code closedSigned} quantity at {@code fillPrice}. */
    private void realizeAndRecord(double closedSigned, double fillPrice) {
        double pnl = closedSigned * (fillPrice - avgEntryPrice);
        realizedPnl += pnl;
        closedTrades++;
        if (pnl > 0.0) {
            winningClosedTrades++;
        }
    }

    /** Mark-to-market on the open position vs. the latest reference price. */
    public double unrealizedPnl(double markPrice) {
        return netPosition * (markPrice - avgEntryPrice);
    }

    private static boolean sameSign(double a, double b) {
        return (a > 0 && b > 0) || (a < 0 && b < 0);
    }

    public String strategy() { return strategy; }
    public String token() { return token; }
    public double netPosition() { return netPosition; }
    public double avgEntryPrice() { return avgEntryPrice; }
    public double realizedPnl() { return realizedPnl; }
    public long numTrades() { return numTrades; }
    public long closedTrades() { return closedTrades; }
    public long winningClosedTrades() { return winningClosedTrades; }
}
