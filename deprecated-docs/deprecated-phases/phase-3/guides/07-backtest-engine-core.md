# Task 9: Backtest Engine Core - Implementation Guide

## Overview

The **Backtest Engine Core** is the orchestrator that connects all backtesting components into a cohesive pipeline. It coordinates data fetching, indicator calculation, signal generation, trade execution, and metrics calculation.

**File:** `app/core/backtest_engine.py`

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   BACKTEST ENGINE FLOW                       │
└─────────────────────────────────────────────────────────────┘

Input:
  - Strategy (e.g., RsiStrategy)
  - Symbol (e.g., "AAPL")
  - Date Range (start_date, end_date)
  - Initial Capital (e.g., $10,000)
  - Transaction Cost (e.g., 0.1%)

                        ▼
┌─────────────────────────────────────────────────────────────┐
│  STEP 1: Fetch Historical Data                              │
│  - Query QuestDB for ticks/candles                          │
│  - Filter by symbol and date range                          │
│  - Return as Pandas DataFrame                               │
└────────────────────┬────────────────────────────────────────┘
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  STEP 2: Validate & Clean Data                              │
│  - Check minimum candles required                           │
│  - Remove NaN rows                                           │
│  - Validate price > 0                                        │
│  - Sort by timestamp                                         │
└────────────────────┬────────────────────────────────────────┘
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  STEP 3: Initialize Components                              │
│  - Reset strategy state                                      │
│  - Create Portfolio with initial capital                     │
│  - Prepare for loop                                          │
└────────────────────┬────────────────────────────────────────┘
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  STEP 4: Main Backtest Loop                                 │
│                                                              │
│  For each candle (from required_candles to end):            │
│                                                              │
│    1. Get expanding window (all data up to current index)   │
│       → df.iloc[:i+1]                                       │
│                                                              │
│    2. Generate signal from strategy                          │
│       → signal = strategy.generate_signal(window)           │
│                                                              │
│    3. Execute trade if signal exists:                        │
│       - BUY: Buy max shares with available cash             │
│       - SELL: Sell all shares                                │
│                                                              │
│    4. Update portfolio value with current price              │
│       → portfolio.update_value(current_price, timestamp)    │
│                                                              │
└────────────────────┬────────────────────────────────────────┘
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  STEP 5: Calculate Metrics                                   │
│  - Create MetricsCalculator(portfolio)                       │
│  - Calculate all metrics                                     │
│  - Total return, Sharpe, win rate, drawdown, etc.           │
└────────────────────┬────────────────────────────────────────┘
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  STEP 6: Build Result Object                                │
│  - BacktestResult with:                                      │
│    * Strategy name                                           │
│    * Metrics                                                 │
│    * Trades list                                             │
│    * Equity curve                                            │
│    * Configuration used                                      │
└─────────────────────────────────────────────────────────────┘

Output: BacktestResult
```

---

## Design Decisions

### 1. **Candle Frequency: 1-Hour Default**

**Decision:** Use 1-hour candles as default, make configurable via parameter.

**Rationale:**
- Balanced between signal frequency and execution speed
- 720 candles for 30-day backtest (manageable)
- Strategies work on any frequency (not hardcoded to 60s like Phase 2)

**Implementation:**
```python
frequency: str = "1H"  # 1H, 5T (5min), 1D (daily)
```

---

### 2. **Signal Generation: Expanding Window**

**Decision:** Pass all historical data from start to current index.

**Approach:**
```python
for i in range(required_candles, len(df)):
    # Expanding window: all data up to current point
    window = df.iloc[:i+1]
    signal = strategy.generate_signal(window)
```

**Rationale:**
- Mimics real-world trading (strategy sees all past data)
- Indicators need historical context (e.g., MA50 needs 50+ candles)
- Matches Phase 2 behavior where strategies query full history

**Alternative Rejected:** Rolling window (only last N candles)
- Would break indicators that need more history
- Less realistic

---

### 3. **Trade Execution: All-In / All-Out**

**Decision:** Simple position sizing.

**Rules:**
- **BUY Signal:** Buy maximum shares with all available cash
- **SELL Signal:** Sell all shares

**Implementation:**
```python
if signal.action == "BUY":
    if portfolio.cash > 0 and portfolio.shares == 0:
        max_shares = portfolio.calculate_max_shares(current_price)
        portfolio.buy(max_shares, current_price, timestamp)

elif signal.action == "SELL":
    if portfolio.shares > 0:
        portfolio.sell(portfolio.shares, current_price, timestamp)
```

**Rationale:**
- Simple to understand and verify
- Standard for strategy backtesting
- Can add position sizing logic later (e.g., based on confidence)

**Edge Cases Handled:**
- BUY when already holding shares → Ignore (can't buy more)
- SELL when holding no shares → Ignore (nothing to sell)

---

### 4. **Indicator Calculation: On-Demand**

**Decision:** Let strategies calculate their own indicators.

**Current Flow:**
```python
# Strategy internally calls:
df_with_rsi = calculate_rsi(df.copy(), period=14)
```

**Rationale:**
- Strategies are self-contained (clean separation)
- No need to pre-calculate all indicators
- Performance is acceptable for backtesting

**Alternative Rejected:** Pre-calculate all indicators
- Wastes memory
- Harder to maintain (need to know which indicators each strategy uses)

---

### 5. **Confidence Handling: Ignored (Phase 1)**

**Decision:** Execute all signals regardless of confidence.

**Rationale:**
- Simplest approach for initial implementation
- Strategies already filter low-quality signals internally
- Can add confidence-based filtering later:
  - `if signal.confidence >= threshold: execute()`
  - Position sizing based on confidence

**Future Enhancement:**
```python
# Phase 2: Use confidence for position sizing
position_size = base_size * signal.confidence
```

---

### 6. **Data Source: Flexible**

**Decision:** Support both ticks and pre-aggregated candles.

**Implementation:**
```python
# DataFetcher already supports both:
fetcher.fetch_historical_data(symbol, start, end, frequency="1H")
```

**Rationale:**
- `candles_1m` table (if exists) is faster
- Can fallback to ticks and resample
- DataFetcher handles this abstraction

---

## Edge Cases & Error Handling

### 1. **Insufficient Data**

**Scenario:** Strategy needs 50 candles but only 20 available.

**Handling:**
```python
if len(df) < strategy.get_required_candles():
    raise ValueError(
        f"Insufficient data: {len(df)} candles available, "
        f"{strategy.get_required_candles()} required"
    )
```

### 2. **No Signals Generated**

**Scenario:** Strategy never triggers during backtest period.

**Handling:**
```python
# MetricsCalculator already handles this
# Returns BacktestMetrics with has_trades=False
```

### 3. **Invalid Data**

**Scenario:** NaN prices or volume, price <= 0.

**Handling:**
```python
# Remove NaN rows
df = df.dropna(subset=['open', 'high', 'low', 'close', 'volume'])

# Validate price > 0
df = df[df['close'] > 0]
```

### 4. **Empty DataFrame After Fetch**

**Scenario:** No data for symbol/date range.

**Handling:**
```python
if df.empty:
    raise ValueError(f"No data found for {symbol} in date range")
```

### 5. **BUY/SELL When Already In Position**

**Scenario:** BUY signal when already holding shares.

**Handling:**
```python
# Check state before execution
if signal.action == "BUY" and portfolio.shares == 0:
    # Only buy if not already holding
    execute_buy()

elif signal.action == "SELL" and portfolio.shares > 0:
    # Only sell if holding shares
    execute_sell()
```

---

## Return Value: BacktestResult

```python
class BacktestResult(BaseModel):
    """Complete backtest results."""
    
    # Identification
    strategy_name: str
    symbol: str
    
    # Time period
    start_date: datetime
    end_date: datetime
    
    # Configuration
    initial_capital: float
    transaction_cost: float
    frequency: str
    
    # Results
    metrics: BacktestMetrics  # All performance metrics
    trades: List[Trade]  # All executed trades
    equity_curve: List[EquityPoint]  # Portfolio value over time
    
    # Summary
    final_portfolio_value: float
    total_return_pct: float
    num_candles_processed: int
```

---

## Implementation Structure

### Class: `BacktestEngine`

```python
class BacktestEngine:
    """
    Orchestrates backtesting pipeline.
    
    Responsibilities:
    1. Coordinate data fetching
    2. Validate data quality
    3. Execute backtest loop
    4. Generate results
    
    NOT responsible for:
    - Indicator calculation (done by strategies)
    - Trade execution logic (done by Portfolio)
    - Metrics calculation (done by MetricsCalculator)
    """
    
    def __init__(self, data_fetcher: DataFetcher):
        """Initialize with data fetcher dependency."""
        self.data_fetcher = data_fetcher
    
    def run(
        self,
        strategy: BaseStrategy,
        symbol: str,
        start_date: datetime,
        end_date: datetime,
        initial_capital: float = 10000.0,
        transaction_cost: float = 0.001,
        frequency: str = "1H"
    ) -> BacktestResult:
        """
        Run backtest and return results.
        
        Args:
            strategy: Strategy instance (e.g., RsiStrategy())
            symbol: Trading symbol (e.g., "AAPL")
            start_date: Backtest start date
            end_date: Backtest end date
            initial_capital: Starting cash (default: $10,000)
            transaction_cost: Fee as decimal (default: 0.001 = 0.1%)
            frequency: Candle frequency (default: "1H")
        
        Returns:
            BacktestResult with metrics, trades, and equity curve
        
        Raises:
            ValueError: If insufficient data or invalid parameters
        """
        pass
    
    def _validate_data(self, df: pd.DataFrame, strategy: BaseStrategy) -> pd.DataFrame:
        """Validate and clean data."""
        pass
    
    def _execute_backtest_loop(
        self,
        df: pd.DataFrame,
        strategy: BaseStrategy,
        portfolio: Portfolio
    ) -> None:
        """Main backtest loop."""
        pass
    
    def _build_result(
        self,
        strategy: BaseStrategy,
        portfolio: Portfolio,
        config: dict
    ) -> BacktestResult:
        """Build final result object."""
        pass
```

---

## Main Backtest Loop Logic

```python
def _execute_backtest_loop(
    self,
    df: pd.DataFrame,
    strategy: BaseStrategy,
    portfolio: Portfolio
) -> None:
    """
    Main backtest loop.
    
    For each candle:
    1. Generate signal from strategy
    2. Execute trade if signal exists
    3. Update portfolio value
    """
    required_candles = strategy.get_required_candles()
    
    # Start loop after we have enough data
    for i in range(required_candles, len(df)):
        # Get expanding window (all data up to current point)
        current_window = df.iloc[:i+1]
        current_candle = df.iloc[i]
        
        # Extract current values
        current_price = current_candle['close']
        timestamp = current_candle.name  # Index is timestamp
        
        # Generate signal from strategy
        signal = strategy.generate_signal(current_window)
        
        # Execute trade based on signal
        if signal is not None:
            if signal.action == "BUY":
                # Only buy if we have cash and no shares
                if portfolio.cash > 0 and portfolio.shares == 0:
                    max_shares = portfolio.calculate_max_shares(current_price)
                    if max_shares > 0:
                        portfolio.buy(max_shares, current_price, timestamp)
            
            elif signal.action == "SELL":
                # Only sell if we have shares
                if portfolio.shares > 0:
                    portfolio.sell(portfolio.shares, current_price, timestamp)
        
        # Update portfolio value at end of candle
        portfolio.update_value(current_price, timestamp)
```

**Key Points:**

1. **Expanding Window:** `df.iloc[:i+1]` gives all data from start to current index
2. **State Persistence:** Strategy maintains state between calls (no reset in loop)
3. **Trade Conditions:** Only BUY if no position, only SELL if have position
4. **Value Tracking:** Update portfolio value every candle (builds equity curve)

---

## Testing Strategy

### Unit Tests

```python
def test_backtest_insufficient_data():
    """Test error when not enough data."""
    # Create only 10 candles
    # Strategy needs 50
    # Should raise ValueError

def test_backtest_no_signals():
    """Test backtest when strategy generates no signals."""
    # Flat price (no crossovers)
    # Should return metrics with has_trades=False

def test_backtest_single_trade():
    """Test backtest with one BUY and one SELL."""
    # Verify trade execution
    # Verify P/L calculation

def test_backtest_expanding_window():
    """Test that strategy receives expanding window."""
    # Mock strategy to track window size
    # Verify window grows each iteration
```

### Integration Tests

```python
def test_backtest_with_real_data():
    """Test full backtest with QuestDB data."""
    # Fetch real AAPL data
    # Run RSI strategy
    # Verify metrics are reasonable
```

---

## Performance Considerations

### Current Design

- **Candles per iteration:** Growing (1, 2, 3, ..., N)
- **Indicator calculations:** Per iteration (strategies calculate on-demand)
- **Expected speed:** ~1-2 seconds for 30-day backtest (720 candles)

### Optimization Opportunities (Future)

1. **Pre-calculate indicators once**
   - Calculate all indicators at start
   - Strategies extract values instead of recalculating
   - Trade-off: Memory vs speed

2. **Vectorized execution**
   - Calculate all signals at once (no loop)
   - Execute all trades in one pass
   - Much faster but harder to debug

3. **Caching**
   - Cache backtest results by (strategy, symbol, date_range)
   - Return cached result if exists

**Decision:** Start simple (current design), optimize later if needed.

---

## Integration with REST API

The BacktestEngine will be called by the API endpoint:

```python
# In app/api/backtest.py
@router.post("/run")
async def run_backtest(request: BacktestRequest):
    # Initialize engine
    data_fetcher = DataFetcher(db_connection)
    engine = BacktestEngine(data_fetcher)
    
    # Get strategy instance
    strategy = get_strategy_by_name(request.strategy_name)
    
    # Run backtest
    result = engine.run(
        strategy=strategy,
        symbol=request.symbol,
        start_date=request.start_date,
        end_date=request.end_date,
        initial_capital=request.initial_capital,
        transaction_cost=request.transaction_cost
    )
    
    return result
```

---

## Example Usage

```python
# Initialize components
data_fetcher = DataFetcher(db_config)
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
print(f"Total Return: {result.total_return_pct:.2f}%")
print(f"Sharpe Ratio: {result.metrics.sharpe_ratio:.2f}")
print(f"Win Rate: {result.metrics.win_rate_pct:.1f}%")
print(f"Number of Trades: {result.metrics.num_trades}")
```

---

## Summary

**BacktestEngine** is the orchestrator that:
- Fetches and validates data
- Initializes strategy and portfolio
- Executes main backtest loop (expanding window)
- Collects results and calculates metrics
- Returns structured BacktestResult

**Design Philosophy:**
- Simple and explicit (no magic)
- Each component has single responsibility
- Easy to test and debug
- Extensible for future enhancements

**Next Steps After Implementation:**
1. Unit tests for BacktestEngine
2. Integration tests with real QuestDB data
3. REST API endpoint (Task 10-11)
4. Performance testing and optimization
