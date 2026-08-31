1. Paper Trading (Forward Testing)
Before a quantitative strategy is given real capital, it goes through "paper trading" (simulated execution with live market data). This allows quants to measure how the strategy behaves in current market conditions—capturing live spread costs, execution latency, and market impact—without actual financial risk. It helps validate that a strategy's historical backtest wasn't just overfitted to past data.

2. Signals & Position Sizing
The system consumes a Signal, which is the direct output of a quantitative model (often called "Alpha").

Direction & Price: The model determines whether to buy or sell, and at what price.

Lot (Sizing): This is the risk management component. Based on the strategy's confidence in the signal and the current volatility of the asset, the system decides how much capital to allocate (the lot size) to this specific trade.

3. Mark-to-Market (MTM) and Microprice
To know the true value of a portfolio at any given millisecond, quants use a process called Mark-to-Market.

The system constantly ingests a latestMark to calculate how much a position is worth right now.

The code specifically mentions microprice. Standard MTM might just use the last traded price or the exact midpoint between the highest buyer (bid) and lowest seller (ask). A microprice goes deeper by weighting the mid-price based on the volume of orders sitting in the order book. If there are massive buy orders and tiny sell orders, the microprice shifts upward to reflect the true buying pressure, giving a much more accurate valuation for high-frequency or algorithmic strategies.

4. Portfolio Netting and Cross-Strategy Consensus
One of the most critical risk-management concepts here is tracking consensus (which strategies are long vs. short on the same asset).

Internal Crossing: If Strategy A wants to buy 10 Bitcoin and Strategy B wants to sell 10 Bitcoin, the overall portfolio has a net position of zero. A smart aggregation system will "net" these out internally rather than going to the open exchange, saving the firm from paying transaction fees and crossing the bid-ask spread.

Correlation Risk: If five different strategies all suddenly go "net-long" on the same token, the firm’s exposure to that asset spikes. The consensus tracker alerts portfolio managers if supposedly independent strategies are actually highly correlated and reacting to the same hidden market variable.

5. Performance Monitoring & Strategy Decay
The system aggregates Realized PnL (locked-in profits from closed trades), Unrealized PnL (paper profits from open trades), and Win Rate.

Quants monitor these metrics in real-time to detect strategy decay. All quantitative strategies eventually lose their edge as the market adapts.

If a strategy historically had a 55% win rate in backtesting but the live paper-trading snapshot suddenly shows a 40% win rate or an unexpected surge in the number of trades, the system will flag the strategy to be paused or retrained before it loses real money.



Net Position (Long vs. Short)

Long (Positive Quantity): You bought an asset expecting its price to rise. Your profit comes from selling it later at a higher price.

Short (Negative Quantity): You sold an asset you don't own (by borrowing it), expecting the price to fall. Your profit comes from buying it back later at a cheaper price to return it.

The Three Core Trade Actions (Position Lifecycle)
In quantitative trading, tracking the math behind opening and closing trades correctly is notoriously easy to get wrong. This system handles the three fundamental scenarios:

Open / Increase (Same Side): You are adding to your current bet. If you are Long 10 tokens and buy 5 more, you are now Long 15. This action shifts your average entry price based on the size and price of the new trade.

Reduce / Close (Opposite Side): You are taking chips off the table. If you are Long 15 tokens and sell 5, you reduce your position to 10. Crucial rule: Reducing a position does not change your average entry price. If you bought apples at $1 each, selling some of them at $5 doesn't change the fact that the remaining apples still originally cost you $1. This action locks in (realizes) profit or loss.

Flip (Reversal): A strategy suddenly changes its mind completely. If you are Long 10 tokens and the strategy fires a signal to sell 15, the system splits this into two steps instantly: it uses 10 to close the existing Long position (realizing the PnL), and uses the remaining 5 to open a brand-new Short position. The new average entry price simply becomes the current market price.

Pricing and Profitability

Volume-Weighted Average Entry Price (VWAP): Your exact breakeven price. If a strategy buys 10 tokens at $100 and later buys 20 tokens at $130, the average price is not $115. Because a larger volume was bought at the higher price, the math weights it heavily: ((10 * $100) + (20 * $130)) / 30 tokens = $120.

Realized PnL (Profit and Loss): Hard cash. This is the money definitively locked in when a position is reduced, closed, or flipped. Once realized, this money is safe from future market movements.

Unrealized PnL: Paper money. This calculates what your current open position is worth if you instantly liquidated it at the current market price (Mark-to-Market). It fluctuates constantly with the market.

Performance Metrics

Closed Trades vs. Num Trades: A single "closed trade" (a decision to take profit or stop a loss) might take dozens of tiny individual "fills" (num trades) to execute depending on market liquidity. Quants measure the win rate based on the final closed outcome, not the individual micro-executions.

Winning Closed Trades: Tracks how often a strategy's thesis was correct upon exiting the market, yielding the foundational metric of a strategy's statistical edge: its win rate.



Here is exactly how the positions and paper trading resolve this scenario:

1. Independent Strategy Positions
Because the Portfolio class uses a composite key of strategy + token (e.g., Strategy1�BTC), the paper trading engine allows every strategy to hold its own isolated position. If five strategies trade Bitcoin simultaneously, the system maintains five separate PaperPosition records.
For example:

Strategy A: +10 (Long)

Strategy B: -5 (Short)

Strategy C: +2 (Long)

Strategy D: -10 (Short)

Strategy E: 0 (Flat)

On paper, each strategy calculates its own average entry price, realized PnL, and win rate exactly as if it were trading in a vacuum. This is crucial for performance monitoring, as it allows quants to see which specific models are succeeding or failing without their data getting mudded by other strategies.

2. The Net Effect (Firm-Wide Exposure)
While the strategies track their PnL independently, the firm's actual market risk is the sum of all these positions. In the example above, the net effect is -3 BTC (+10 - 5 + 2 - 10). The firm is net-short by 3 tokens.

This is where the consensus() method you saw earlier comes into play. It scans all open positions and groups them into longStrategies and shortStrategies. If the system detects a severe conflict (e.g., Strategy A buying massive amounts while Strategy D sells massive amounts), it logs a warning.

3. How Paper Trading Informs Live Execution (Internal Crossing)
If a firm blindly sent all five of those signals to a live crypto exchange, they would pay transaction fees and bid-ask spread costs on 27 total tokens of volume (10+5+2+10), even though their actual net change in position is only 3 tokens. They would essentially be paying the exchange to trade against themselves.

By modeling this in a paper-trading aggregator first, the execution engine learns to internally cross the trades.

Strategy A's demand for 10 BTC is partially filled by Strategy B and D's desire to sell.

The system internally transfers the risk on paper, keeping the individual strategy PnLs accurate.

The firm only goes to the external, public exchange to sell the net remainder of 3 BTC, saving massive amounts of money in slippage and exchange fees.