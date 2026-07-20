# BacktestEngine Usage Examples

## Basic Usage

```python
from datetime import datetime
from app.core.data_fetcher import QuestDBFetcher
from app.core.backtest_engine import BacktestEngine
from app.strategies import RsiStrategy

# Initialize components
data_fetcher = QuestDBFetcher()
engine = BacktestEngine(data_fetcher)

# Create strategy
strategy = RsiStrategy()

# Run backtest
result = engine.run(
    strategy=strategy,
    symbol="AAPL",
    start_date=datetime(2026, 6, 19),
    end_date=datetime(2026, 7, 19),
    initial_capital=10000.0,
    transaction_cost=0.001,
    frequency="1H"
)

# Print results
print(f"Strategy: {result.strategy_name}")
print(f"Symbol: {result.symbol}")
print(f"Period: {result.period.start} to {result.period.end}")
print(f"\n=== Performance Metrics ===")
print(f"Total Return: {result.total_return_pct:.2f}%")
print(f"Final Value: ${result.final_portfolio_value:,.2f}")
print(f"Sharpe Ratio: {result.metrics.sharpe_ratio:.2f}")
print(f"Win Rate: {result.metrics.win_rate_pct:.1f}%")
print(f"Max Drawdown: {result.metrics.max_drawdown_pct:.2f}%")
print(f"\n=== Trade Statistics ===")
print(f"Total Trades: {result.metrics.num_trades}")
print(f"Winning Trades: {result.metrics.num_winning_trades}")
print(f"Losing Trades: {result.metrics.num_losing_trades}")
print(f"Average Win: ${result.metrics.avg_win:.2f}")
print(f"Average Loss: ${result.metrics.avg_loss:.2f}")
print(f"Risk/Reward Ratio: {result.metrics.risk_reward_ratio:.2f}")
```

## Test All Strategies

```python
from app.strategies import (
    RsiStrategy, MaCrossoverStrategy, MacdStrategy,
    BollingerBandsStrategy, StochasticStrategy, WilliamsRStrategy,
    AdxStrategy, DonchianChannelStrategy, RocStrategy, VwapStrategy
)

# Define all strategies
strategies = [
    RsiStrategy(),
    MaCrossoverStrategy(),
    MacdStrategy(),
    BollingerBandsStrategy(),
    StochasticStrategy(),
    WilliamsRStrategy(),
    AdxStrategy(),
    DonchianChannelStrategy(),
    RocStrategy(),
    VwapStrategy()
]

# Run backtest for each strategy
results = []
for strategy in strategies:
    result = engine.run(
        strategy=strategy,
        symbol="AAPL",
        start_date=datetime(2026, 6, 19),
        end_date=datetime(2026, 7, 19)
    )
    results.append(result)

# Sort by Sharpe ratio
results_sorted = sorted(results, key=lambda r: r.metrics.sharpe_ratio, reverse=True)

# Print leaderboard
print("\n=== Strategy Leaderboard ===")
print(f"{'Rank':<6} {'Strategy':<25} {'Return %':<10} {'Sharpe':<10} {'Trades':<10}")
print("-" * 70)
for i, result in enumerate(results_sorted, 1):
    print(f"{i:<6} {result.strategy_name:<25} {result.total_return_pct:>8.2f}% "
          f"{result.metrics.sharpe_ratio:>8.2f} {result.metrics.num_trades:>8}")
```

## Access Trade History

```python
result = engine.run(...)

# Print all trades
print("\n=== Trade History ===")
for trade in result.trades:
    action = trade.action
    price = trade.price
    shares = trade.shares
    timestamp = trade.timestamp
    
    if trade.action == "BUY":
        print(f"{timestamp}: BUY {shares:.2f} shares @ ${price:.2f}")
    else:
        pnl = trade.pnl or 0
        pnl_pct = trade.pnl_pct or 0
        print(f"{timestamp}: SELL {shares:.2f} shares @ ${price:.2f} | "
              f"P/L: ${pnl:.2f} ({pnl_pct:.2f}%)")
```

## Plot Equity Curve

```python
import matplotlib.pyplot as plt

result = engine.run(...)

# Extract data
timestamps = [point.timestamp for point in result.equity_curve]
values = [point.value for point in result.equity_curve]

# Plot
plt.figure(figsize=(12, 6))
plt.plot(timestamps, values, label='Portfolio Value')
plt.axhline(y=result.config.initial_capital, color='r', linestyle='--', 
            label=f'Initial Capital (${result.config.initial_capital:,.0f})')
plt.xlabel('Time')
plt.ylabel('Portfolio Value ($)')
plt.title(f'{result.strategy_name} - Equity Curve ({result.symbol})')
plt.legend()
plt.grid(True, alpha=0.3)
plt.tight_layout()
plt.show()
```

## Different Frequencies

```python
# Hourly candles (default)
result_1h = engine.run(strategy, "AAPL", start, end, frequency="1H")

# 5-minute candles (more signals)
result_5m = engine.run(strategy, "AAPL", start, end, frequency="5T")

# Daily candles (fewer signals)
result_1d = engine.run(strategy, "AAPL", start, end, frequency="1D")

print(f"1H: {result_1h.metrics.num_trades} trades, {result_1h.total_return_pct:.2f}%")
print(f"5T: {result_5m.metrics.num_trades} trades, {result_5m.total_return_pct:.2f}%")
print(f"1D: {result_1d.metrics.num_trades} trades, {result_1d.total_return_pct:.2f}%")
```

## Compare Multiple Symbols

```python
symbols = ["AAPL", "GOOGL", "MSFT", "TSLA"]
strategy = RsiStrategy()

for symbol in symbols:
    result = engine.run(
        strategy=strategy,
        symbol=symbol,
        start_date=start,
        end_date=end
    )
    
    print(f"\n{symbol}:")
    print(f"  Return: {result.total_return_pct:.2f}%")
    print(f"  Sharpe: {result.metrics.sharpe_ratio:.2f}")
    print(f"  Trades: {result.metrics.num_trades}")
```

## Error Handling

```python
try:
    result = engine.run(
        strategy=strategy,
        symbol="UNKNOWN",
        start_date=datetime(2026, 1, 1),
        end_date=datetime(2026, 1, 31)
    )
except ValueError as e:
    if "No data found" in str(e):
        print(f"No data available for symbol in date range")
    elif "Insufficient data" in str(e):
        print(f"Not enough candles for strategy requirements")
    else:
        print(f"Validation error: {e}")
```

## Export Results to CSV

```python
import pandas as pd

result = engine.run(...)

# Export trades
trades_df = pd.DataFrame([
    {
        'timestamp': t.timestamp,
        'action': t.action,
        'price': t.price,
        'shares': t.shares,
        'total': t.total_amount,
        'fee': t.fee,
        'pnl': t.pnl,
        'pnl_pct': t.pnl_pct
    }
    for t in result.trades
])
trades_df.to_csv(f'{result.strategy_name}_{result.symbol}_trades.csv', index=False)

# Export equity curve
equity_df = pd.DataFrame([
    {
        'timestamp': p.timestamp,
        'value': p.value
    }
    for p in result.equity_curve
])
equity_df.to_csv(f'{result.strategy_name}_{result.symbol}_equity.csv', index=False)

print(f"Exported results to CSV files")
```

## Custom Transaction Costs

```python
# No transaction costs
result_no_fees = engine.run(strategy, symbol, start, end, transaction_cost=0.0)

# Standard retail (0.1%)
result_retail = engine.run(strategy, symbol, start, end, transaction_cost=0.001)

# High fees (0.5%)
result_high_fees = engine.run(strategy, symbol, start, end, transaction_cost=0.005)

print(f"No Fees: {result_no_fees.total_return_pct:.2f}%")
print(f"Retail: {result_retail.total_return_pct:.2f}%")
print(f"High Fees: {result_high_fees.total_return_pct:.2f}%")
```

## Integration with REST API (Future)

```python
from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()

class BacktestRequest(BaseModel):
    strategy_name: str
    symbol: str
    start_date: datetime
    end_date: datetime
    initial_capital: float = 10000.0
    transaction_cost: float = 0.001
    frequency: str = "1H"

@router.post("/backtest/run")
async def run_backtest(request: BacktestRequest):
    # Get strategy by name
    strategy_map = {
        "RSI": RsiStrategy(),
        "MA_CROSSOVER": MaCrossoverStrategy(),
        # ... other strategies
    }
    strategy = strategy_map.get(request.strategy_name)
    
    if not strategy:
        raise HTTPException(status_code=400, detail="Unknown strategy")
    
    # Run backtest
    data_fetcher = QuestDBFetcher()
    engine = BacktestEngine(data_fetcher)
    
    result = engine.run(
        strategy=strategy,
        symbol=request.symbol,
        start_date=request.start_date,
        end_date=request.end_date,
        initial_capital=request.initial_capital,
        transaction_cost=request.transaction_cost,
        frequency=request.frequency
    )
    
    return result
```
