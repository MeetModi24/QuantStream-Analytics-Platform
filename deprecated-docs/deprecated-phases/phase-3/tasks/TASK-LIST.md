# Phase 3: Backtesting Engine - Task List

## Overview

This document breaks down Phase 3 (Backtesting Engine) into discrete, actionable tasks. Each task is designed to be completed independently and has clear success criteria.

**Estimated Total Time:** 12-15 hours  
**Technology:** Python 3.11+, FastAPI, Pandas, NumPy, Psycopg2

---

## Task Status Legend
- ⏳ **Not Started**
- 🚧 **In Progress**
- ✅ **Complete**
- ❌ **Blocked**

---

## Task 1: Project Setup and Dependencies ⏳

**Goal:** Set up Python FastAPI project structure with all required dependencies.

**Steps:**
1. Create `backtester/` directory at project root
2. Initialize Python virtual environment
3. Create `requirements.txt` with dependencies:
   - fastapi
   - uvicorn
   - pandas
   - numpy
   - psycopg2-binary
   - pydantic
   - python-dotenv
   - pandas-ta (for technical indicators)
4. Create project structure (directories and `__init__.py` files)
5. Create `app/config.py` for configuration management
6. Create `.env` file for environment variables

**Success Criteria:**
- ✅ Virtual environment created and activated
- ✅ All dependencies installed without errors
- ✅ Project structure matches design (see PHASE-3-OVERVIEW.md)
- ✅ Can import FastAPI and run basic "Hello World" endpoint
- ✅ Can connect to QuestDB using psycopg2

**Files Created:**
- `backtester/requirements.txt`
- `backtester/app/__init__.py`
- `backtester/app/main.py`
- `backtester/app/config.py`
- `backtester/.env`
- All subdirectory `__init__.py` files

**Time Estimate:** 1 hour

**Guide Reference:** `guides/01-project-setup.md`

---

## Task 2: QuestDB Data Fetcher ⏳

**Goal:** Implement module to fetch historical price data from QuestDB.

**Requirements:**
1. Create `app/core/data_fetcher.py`
2. Implement `QuestDBFetcher` class with methods:
   - `fetch_ticks(symbol, start_date, end_date)` → Returns DataFrame
   - `fetch_candles(symbol, start_date, end_date, interval)` → Returns DataFrame
3. Use connection pooling for efficiency
4. Convert QuestDB timestamp to pandas DatetimeIndex
5. Handle errors (connection failures, empty results)

**Success Criteria:**
- ✅ Can fetch 30 days of AAPL ticks from QuestDB
- ✅ Returns Pandas DataFrame with columns: [timestamp, symbol, price, volume]
- ✅ DataFrame has DatetimeIndex (timestamp)
- ✅ Handles missing data gracefully (returns empty DataFrame)
- ✅ Connection pool reuses connections efficiently

**Test Data:**
```python
# Should return ~86,400 rows for 30 days (1 tick/sec)
df = fetcher.fetch_ticks("AAPL", "2026-06-19", "2026-07-19")
assert len(df) > 0
assert 'price' in df.columns
assert 'volume' in df.columns
```

**Time Estimate:** 2 hours

**Guide Reference:** `guides/02-data-fetcher-implementation.md`

---

## Task 3: Data Resampling Module ⏳

**Goal:** Implement OHLC resampling to convert tick data to candles.

**Requirements:**
1. Add method to `QuestDBFetcher`:
   - `resample_to_ohlc(df, frequency)` → Returns DataFrame with OHLC
2. Support frequencies: '1T' (1 minute), '5T', '15T', '1H', '1D'
3. Calculate: Open, High, Low, Close, Volume
4. Handle missing periods (forward-fill or drop)

**Success Criteria:**
- ✅ Can convert 86,400 tick rows to 1,440 candle rows (1-minute)
- ✅ OHLC values calculated correctly:
  - Open = first tick price in period
  - High = max tick price in period
  - Low = min tick price in period
  - Close = last tick price in period
  - Volume = sum of volumes in period
- ✅ Handles edge cases (single tick in period, no ticks in period)

**Test Data:**
```python
ticks_df = fetcher.fetch_ticks("AAPL", "2026-07-19", "2026-07-19")
candles_df = fetcher.resample_to_ohlc(ticks_df, '1H')
assert len(candles_df) == 24  # 24 hours
assert 'open' in candles_df.columns
assert 'high' in candles_df.columns
assert candles_df['high'].iloc[0] >= candles_df['low'].iloc[0]
```

**Time Estimate:** 1.5 hours

**Guide Reference:** `guides/02-data-fetcher-implementation.md` (Section: Resampling)

---

## Task 4: Technical Indicators Module ⏳

**Goal:** Implement calculation of technical indicators (RSI, MACD, Bollinger Bands, etc.).

**Requirements:**
1. Create `app/core/indicators.py`
2. Implement indicator functions:
   - `calculate_rsi(df, period=14)` → Adds 'rsi' column
   - `calculate_macd(df, fast=12, slow=26, signal=9)` → Adds 'macd', 'macd_signal', 'macd_hist'
   - `calculate_bollinger_bands(df, period=20, std=2)` → Adds 'bb_upper', 'bb_middle', 'bb_lower'
   - `calculate_sma(df, period)` → Adds 'sma_{period}' column
   - `calculate_ema(df, period)` → Adds 'ema_{period}' column
   - `calculate_stochastic(df, k_period=14, d_period=3)` → Adds 'stoch_k', 'stoch_d'
   - `calculate_williams_r(df, period=14)` → Adds 'williams_r'
   - `calculate_adx(df, period=14)` → Adds 'adx'
   - `calculate_donchian_channel(df, period=20)` → Adds 'dc_upper', 'dc_lower'
   - `calculate_roc(df, period=12)` → Adds 'roc'
3. Use `pandas_ta` library where possible
4. Handle NaN values gracefully (indicator needs warmup period)

**Success Criteria:**
- ✅ RSI values between 0 and 100
- ✅ MACD crossover detection works
- ✅ Bollinger Bands: upper > middle > lower
- ✅ Indicators match manual calculations (test on sample data)
- ✅ First N rows are NaN (warmup period)
- ✅ All 10 strategies have their required indicators

**Test Data:**
```python
df = pd.DataFrame({'close': [100, 102, 101, 103, 105, 104, 106]})
df = calculate_rsi(df, period=3)
assert not df['rsi'].isna().all()
assert df['rsi'].iloc[-1] >= 0 and df['rsi'].iloc[-1] <= 100
```

**Time Estimate:** 3 hours

**Guide Reference:** `guides/03-indicators-implementation.md`

---

## Task 5: Portfolio Simulation Engine ⏳

**Goal:** Implement portfolio class to simulate trading with buy/sell logic.

**Requirements:**
1. Create `app/core/portfolio.py`
2. Implement `Portfolio` class with:
   - `__init__(initial_capital, transaction_cost=0.0)`
   - `buy(shares, price, timestamp)` → Execute buy order
   - `sell(shares, price, timestamp)` → Execute sell order
   - `get_current_value(current_price)` → Returns portfolio value
   - `get_trades()` → Returns list of all trades
   - `get_equity_curve()` → Returns portfolio value over time
3. Track: cash, shares, trades list
4. Include transaction costs (e.g., 0.1% per trade)
5. Prevent invalid trades (buying with no cash, selling with no shares)

**Success Criteria:**
- ✅ Starting with $10,000 cash, 0 shares
- ✅ Buy 50 shares at $100 → cash = $10,000 - $5,000 = $5,000, shares = 50
- ✅ Sell 50 shares at $110 → cash = $5,000 + $5,500 = $10,500, shares = 0
- ✅ Total return = 5% (includes transaction costs)
- ✅ Cannot buy more shares than cash allows
- ✅ Cannot sell more shares than owned
- ✅ Transaction costs deducted correctly

**Test Data:**
```python
portfolio = Portfolio(initial_capital=10000, transaction_cost=0.001)
portfolio.buy(100, 100, timestamp)
assert portfolio.cash == 0
assert portfolio.shares == 100
portfolio.sell(100, 110, timestamp)
assert portfolio.cash == 10989  # 11,000 - 0.1% transaction cost
assert portfolio.shares == 0
```

**Time Estimate:** 2 hours

**Guide Reference:** `guides/04-portfolio-simulation.md`

---

## Task 6: Performance Metrics Calculator ⏳

**Goal:** Calculate backtesting performance metrics.

**Requirements:**
1. Create `app/core/metrics.py`
2. Implement metric functions:
   - `calculate_total_return(initial_capital, final_value)` → Returns %
   - `calculate_sharpe_ratio(returns, risk_free_rate=0.0)` → Returns float
   - `calculate_win_rate(trades)` → Returns %
   - `calculate_max_drawdown(equity_curve)` → Returns %
   - `calculate_avg_win_loss(trades)` → Returns (avg_win, avg_loss)
   - `calculate_risk_reward_ratio(trades)` → Returns float
   - `calculate_cagr(initial_capital, final_value, days)` → Returns % (Compound Annual Growth Rate)
3. Validate inputs (handle division by zero, empty lists)

**Success Criteria:**
- ✅ Total return calculation: `((13250 - 10000) / 10000) * 100 = 32.5%`
- ✅ Win rate calculation: `(331 / 487) * 100 = 68%`
- ✅ Sharpe ratio matches manual calculation (using sample returns)
- ✅ Max drawdown correctly identifies largest drop
- ✅ All metrics return reasonable values (no NaN, no infinity)

**Test Data:**
```python
metrics = calculate_metrics(
    initial_capital=10000,
    final_value=13250,
    trades=[...],
    equity_curve=[...]
)
assert metrics['total_return_pct'] == 32.5
assert metrics['win_rate_pct'] == 68.0
assert 1.0 < metrics['sharpe_ratio'] < 2.0
```

**Time Estimate:** 2 hours

**Guide Reference:** `guides/05-performance-metrics.md`

---

## Task 7: Base Strategy Class ⏳

**Goal:** Create abstract base class for all strategies.

**Requirements:**
1. Create `app/strategies/base_strategy.py`
2. Implement `BaseStrategy` abstract class with methods:
   - `generate_signals(df)` → Returns DataFrame with 'signal' column
   - `get_name()` → Returns strategy name
   - `get_parameters()` → Returns dict of parameters
   - `validate_data(df)` → Checks if DataFrame has required columns
3. Define signal types: 'BUY', 'SELL', 'HOLD'

**Success Criteria:**
- ✅ Abstract class cannot be instantiated directly
- ✅ Subclasses must implement `generate_signals()` method
- ✅ `validate_data()` raises error if required columns missing
- ✅ All strategies follow same interface

**Time Estimate:** 1 hour

**Guide Reference:** `guides/06-implementing-strategies.md` (Section: Base Class)

---

## Task 8: Implement 10 Trading Strategies ⏳

**Goal:** Implement all 10 strategy classes matching production logic.

**Requirements:**

Create strategy files in `app/strategies/`:
1. `rsi_strategy.py` - RSI Mean Reversion
2. `ma_crossover.py` - Moving Average Crossover
3. `macd_strategy.py` - MACD Momentum
4. `bollinger_strategy.py` - Bollinger Bands
5. `stochastic_strategy.py` - Stochastic Oscillator
6. `williams_r_strategy.py` - Williams %R
7. `adx_strategy.py` - ADX Trend Strength
8. `donchian_strategy.py` - Donchian Channel Breakout
9. `roc_strategy.py` - Rate of Change
10. `vwap_strategy.py` - VWAP Deviation

Each strategy must:
- Extend `BaseStrategy`
- Implement `generate_signals(df)` method
- Match production strategy logic from `strategy-engine` service
- Include proper parameter defaults
- Handle edge cases (insufficient data, NaN values)

**Success Criteria:**
- ✅ All 10 strategies implemented
- ✅ Each strategy generates BUY/SELL/HOLD signals
- ✅ Logic matches production strategy (cross-validated)
- ✅ Can run backtest with each strategy independently
- ✅ No runtime errors on valid data

**Test Data:**
```python
strategy = RSIStrategy(period=14, oversold=30, overbought=70)
df = fetcher.fetch_ticks("AAPL", "2026-07-01", "2026-07-02")
df = fetcher.resample_to_ohlc(df, '1H')
df = calculate_rsi(df, period=14)
df = strategy.generate_signals(df)
assert 'signal' in df.columns
assert df['signal'].isin(['BUY', 'SELL', 'HOLD']).all()
```

**Time Estimate:** 4 hours (24 min per strategy)

**Guide Reference:** `guides/06-implementing-strategies.md`

---

## Task 9: Backtest Engine Core ⏳

**Goal:** Implement main backtest orchestration logic.

**Requirements:**
1. Create `app/core/backtester.py`
2. Implement `Backtester` class with method:
   - `run_backtest(strategy, symbol, start_date, end_date, initial_capital, transaction_cost)`
3. Orchestrate full pipeline:
   - Fetch data from QuestDB
   - Resample to strategy frequency
   - Calculate required indicators
   - Generate signals
   - Simulate portfolio trades
   - Calculate performance metrics
4. Return `BacktestResult` object

**Success Criteria:**
- ✅ Can run complete backtest for RSI strategy on AAPL (30 days)
- ✅ Returns all required metrics
- ✅ Execution time < 5 seconds for 30-day backtest
- ✅ Handles errors gracefully (no data, invalid dates, etc.)
- ✅ Results are consistent across multiple runs (deterministic)

**Test Data:**
```python
backtester = Backtester()
result = backtester.run_backtest(
    strategy=RSIStrategy(),
    symbol="AAPL",
    start_date="2026-06-19",
    end_date="2026-07-19",
    initial_capital=10000,
    transaction_cost=0.001
)
assert result.metrics.total_return_pct > 0
assert len(result.trades) > 0
assert len(result.equity_curve) > 0
```

**Time Estimate:** 2 hours

**Guide Reference:** `guides/07-backtest-engine-core.md`

---

## Task 10: Pydantic Data Models ⏳

**Goal:** Define request/response models for API endpoints.

**Requirements:**
1. Create `app/models/backtest_request.py`:
   - `BacktestRequest` (POST body)
   - `CompareRequest` (POST body for comparison)
2. Create `app/models/backtest_result.py`:
   - `BacktestMetrics` (performance metrics)
   - `Trade` (single trade)
   - `EquityCurvePoint` (timestamp + value)
   - `BacktestResult` (complete response)
3. Create `app/models/leaderboard.py`:
   - `StrategyRanking` (single strategy entry)
   - `Leaderboard` (rankings list)

**Success Criteria:**
- ✅ All models use Pydantic for validation
- ✅ Request models validate input (dates, positive numbers, etc.)
- ✅ Response models serialize to JSON correctly
- ✅ OpenAPI schema generated automatically by FastAPI

**Time Estimate:** 1.5 hours

**Guide Reference:** `guides/08-api-models.md`

---

## Task 11: REST API Endpoints ⏳

**Goal:** Implement FastAPI endpoints for backtesting.

**Requirements:**
1. Create `app/api/backtest.py`:
   - `POST /api/backtest/run` → Run backtest
   - `GET /api/backtest/results/{backtest_id}` → Get cached results
2. Create `app/api/leaderboard.py`:
   - `GET /api/backtest/leaderboard` → Get strategy rankings
3. Create `app/api/compare.py`:
   - `POST /api/backtest/compare` → Compare multiple strategies
4. Add CORS middleware for frontend integration
5. Add error handling middleware

**Success Criteria:**
- ✅ All endpoints return proper status codes (200, 400, 404, 500)
- ✅ Request validation works (400 for invalid input)
- ✅ Response matches OpenAPI schema
- ✅ Can test all endpoints with curl/Postman
- ✅ CORS allows requests from frontend (localhost:5173)

**Test Requests:**
```bash
# Run backtest
curl -X POST http://localhost:8085/api/backtest/run \
  -H "Content-Type: application/json" \
  -d '{
    "strategy": "RSI_MEAN_REVERSION",
    "symbol": "AAPL",
    "start_date": "2026-06-19",
    "end_date": "2026-07-19",
    "initial_capital": 10000
  }'

# Get leaderboard
curl http://localhost:8085/api/backtest/leaderboard?period=30d
```

**Time Estimate:** 2 hours

**Guide Reference:** `guides/09-api-endpoints.md`

---

## Task 12: Unit Tests ⏳

**Goal:** Write unit tests for core components.

**Requirements:**
1. Create test files in `tests/`:
   - `test_portfolio.py` → Test portfolio buy/sell logic
   - `test_metrics.py` → Test metric calculations
   - `test_indicators.py` → Test indicator calculations
   - `test_strategies.py` → Test strategy signal generation
2. Use `pytest` framework
3. Mock QuestDB connection for isolated tests
4. Achieve > 80% code coverage

**Success Criteria:**
- ✅ All tests pass (`pytest tests/`)
- ✅ Portfolio tests cover edge cases (no cash, no shares, transaction costs)
- ✅ Metrics tests validate calculations with known inputs
- ✅ Strategy tests verify signal generation logic
- ✅ Code coverage > 80%

**Time Estimate:** 2 hours

**Guide Reference:** `guides/10-testing.md`

---

## Task 13: Integration Testing ⏳

**Goal:** Test end-to-end backtest with real QuestDB data.

**Requirements:**
1. Start Docker containers (QuestDB, Kafka)
2. Ensure QuestDB has historical data (from Phase 2)
3. Run backtest for all 10 strategies on AAPL
4. Verify results are consistent and reasonable
5. Document actual performance metrics

**Success Criteria:**
- ✅ All 10 strategies complete without errors
- ✅ Metrics are within reasonable ranges:
  - Total return: -50% to +100%
  - Sharpe ratio: -2.0 to +3.0
  - Win rate: 30% to 80%
  - Max drawdown: 0% to -50%
- ✅ Execution time < 10 seconds per strategy
- ✅ Results documented in `docs/phase-3/BACKTEST-RESULTS.md`

**Time Estimate:** 1 hour

**Guide Reference:** `guides/11-integration-testing.md`

---

## Task 14: Documentation and Deployment ⏳

**Goal:** Document API and prepare for deployment.

**Requirements:**
1. Create `backtester/README.md`:
   - Project overview
   - Installation instructions
   - Running locally
   - API endpoint examples
2. Create `backtester/Dockerfile`
3. Update main project README with Phase 3 status
4. Document performance benchmarks
5. Create deployment guide for Render.com

**Success Criteria:**
- ✅ README includes setup instructions for beginners
- ✅ Docker image builds successfully
- ✅ Can run backtester in Docker container
- ✅ API documentation accessible at `/docs` (FastAPI Swagger)
- ✅ Deployment guide tested on Render free tier

**Time Estimate:** 1.5 hours

**Guide Reference:** `guides/12-deployment.md`

---

## Task Dependencies

```
Task 1 (Project Setup)
  │
  ├─→ Task 2 (Data Fetcher)
  │     │
  │     └─→ Task 3 (Resampling)
  │           │
  │           └─→ Task 4 (Indicators)
  │                 │
  │                 └─→ Task 7 (Base Strategy)
  │                       │
  │                       └─→ Task 8 (10 Strategies)
  │
  ├─→ Task 5 (Portfolio)
  │
  ├─→ Task 6 (Metrics)
  │
  └─→ Task 9 (Backtest Engine)
        │
        ├─→ Task 10 (Pydantic Models)
        │
        └─→ Task 11 (API Endpoints)
              │
              ├─→ Task 12 (Unit Tests)
              │
              ├─→ Task 13 (Integration Tests)
              │
              └─→ Task 14 (Documentation)
```

**Critical Path:** Tasks 1 → 2 → 3 → 4 → 7 → 8 → 9 → 11 → 13

**Parallel Work Possible:**
- Tasks 5 and 6 can be done in parallel with Tasks 2-4
- Tasks 10 and 12 can be done in parallel with Task 11

---

## Estimated Timeline

**Week 1 (8 hours):**
- Day 1: Tasks 1-3 (Setup, Data Fetcher, Resampling)
- Day 2: Tasks 4-6 (Indicators, Portfolio, Metrics)

**Week 2 (7 hours):**
- Day 3: Tasks 7-8 (Base Strategy, 10 Strategies)
- Day 4: Tasks 9-11 (Backtest Engine, Models, API)

**Week 3 (4 hours):**
- Day 5: Tasks 12-14 (Tests, Integration, Documentation)

**Total:** ~15-20 hours for complete Phase 3 implementation

---

## Next Steps

1. Read `guides/01-project-setup.md` to start Task 1
2. Complete tasks in order (respect dependencies)
3. Mark tasks as complete (✅) in this document
4. Document any issues or blockers

**Ready to begin?** Start with Task 1: Project Setup!
