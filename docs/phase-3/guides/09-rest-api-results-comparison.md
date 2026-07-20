# Task 11: REST API - Results & Comparison

**Guide for implementing backtest results retrieval and strategy comparison endpoints**

---

## Overview

This guide covers the implementation of REST API endpoints for retrieving and analyzing backtest results. These endpoints allow users to:
- Retrieve complete backtest results with all metrics and trades
- Get equity curve data optimized for chart rendering
- Compare multiple strategies side-by-side
- Export results in multiple formats (JSON, CSV)
- Generate performance reports

**Technology:** FastAPI with optimized JSON serialization

---

## Architecture

```
┌────────────────────────────────────────────────────────┐
│           Frontend Components Requiring Data            │
├────────────────────────────────────────────────────────┤
│  1. Results Summary Card                                │
│     → Total Return, Sharpe, Win Rate, Max Drawdown     │
│                                                          │
│  2. Equity Curve Chart (Recharts/Chart.js)            │
│     → Array of {timestamp, value} points               │
│                                                          │
│  3. Trade History Table                                 │
│     → List of trades with P/L, entry/exit prices       │
│                                                          │
│  4. Strategy Comparison Matrix                          │
│     → Side-by-side metrics for multiple strategies     │
│                                                          │
│  5. Performance Heatmap                                 │
│     → Strategy × Symbol grid showing returns            │
└────────────────────────────────────────────────────────┘
                          ▲
                          │ HTTP GET
                          │
┌────────────────────────────────────────────────────────┐
│         FastAPI Endpoints (/api/v1/backtest)           │
├────────────────────────────────────────────────────────┤
│  GET /results/{id}              → Full backtest result │
│  GET /{id}/equity-curve         → Optimized chart data │
│  GET /{id}/trades               → Paginated trade list │
│  GET /{id}/summary              → Metrics only (fast)  │
│  POST /compare                  → Compare strategies   │
│  GET /{id}/export?format=csv    → Download results     │
└────────────────────────────────────────────────────────┘
                          ▲
                          │
┌────────────────────────────────────────────────────────┐
│              Result Cache (In-Memory Dict)              │
│  Key: backtest_id                                      │
│  Value: BacktestResult (from Task 9)                   │
└────────────────────────────────────────────────────────┘
```

---

## API Endpoints

### 1. Get Complete Backtest Results

**Endpoint:** `GET /api/v1/backtest/results/{backtest_id}`

**Purpose:** Retrieve full backtest results including metrics, trades, and equity curve.

**Response (200 OK):**
```json
{
  "backtest_id": "a1b2c3d4-5678-90ab-cdef-1234567890ab",
  "strategy_name": "RSI",
  "symbol": "AAPL",
  "period": {
    "start": "2026-06-20T00:00:00Z",
    "end": "2026-07-20T23:59:59Z"
  },
  "config": {
    "initial_capital": 10000.0,
    "transaction_cost": 0.001,
    "frequency": "1H"
  },
  "metrics": {
    "total_return_pct": 32.5,
    "final_portfolio_value": 13250.0,
    "sharpe_ratio": 1.85,
    "win_rate_pct": 68.0,
    "max_drawdown_pct": -8.4,
    "num_trades": 487,
    "num_winning_trades": 331,
    "num_losing_trades": 156,
    "avg_win_pct": 2.3,
    "avg_loss_pct": -1.1,
    "profit_factor": 2.09,
    "has_trades": true
  },
  "trades": [
    {
      "timestamp": "2026-06-20T10:30:00Z",
      "action": "BUY",
      "price": 175.50,
      "shares": 57.0,
      "total": 10003.50,
      "cash_after": 0.0,
      "shares_after": 57.0
    },
    {
      "timestamp": "2026-06-22T14:15:00Z",
      "action": "SELL",
      "price": 179.80,
      "shares": 57.0,
      "total": 10248.60,
      "cash_after": 10248.60,
      "shares_after": 0.0,
      "pnl": 245.10,
      "pnl_pct": 2.45
    }
  ],
  "equity_curve": [
    {"timestamp": "2026-06-19T00:00:00Z", "value": 10000.0},
    {"timestamp": "2026-06-20T01:00:00Z", "value": 10050.0},
    {"timestamp": "2026-06-20T02:00:00Z", "value": 10125.0}
  ],
  "num_candles_processed": 720,
  "execution_time_seconds": 12.5
}
```

**Use Case:** Display on results detail page with all information.

**Error (404 Not Found):**
```json
{
  "error": "not_found",
  "message": "Backtest result not found or expired",
  "backtest_id": "invalid-id"
}
```

**Error (410 Gone):**
```json
{
  "error": "result_expired",
  "message": "Result was available but has been removed (TTL: 1 hour)",
  "backtest_id": "a1b2c3d4...",
  "finished_at": "2026-07-20T14:30:00Z"
}
```

---

### 2. Get Equity Curve (Optimized for Charts)

**Endpoint:** `GET /api/v1/backtest/{backtest_id}/equity-curve`

**Purpose:** Return equity curve in format optimized for frontend charting libraries.

**Query Parameters:**
- `sample_rate` (optional): Downsample for performance (e.g., `10` = every 10th point)
- `format` (optional): `timestamps` (ISO strings) or `unix` (milliseconds)

**Response (200 OK):**
```json
{
  "backtest_id": "a1b2c3d4...",
  "equity_curve": [
    {"t": "2026-06-19T00:00:00Z", "v": 10000.0},
    {"t": "2026-06-20T00:00:00Z", "v": 10050.0},
    {"t": "2026-06-21T00:00:00Z", "v": 10125.0}
  ],
  "total_points": 720,
  "sampled_points": 720,
  "initial_value": 10000.0,
  "final_value": 13250.0,
  "peak_value": 13890.0,
  "lowest_value": 9160.0
}
```

**With `format=unix`:**
```json
{
  "equity_curve": [
    {"t": 1750000000000, "v": 10000.0},
    {"t": 1750003600000, "v": 10050.0}
  ]
}
```

**Use Case:** Power Recharts/Chart.js line chart:
```javascript
<LineChart data={equity_curve}>
  <XAxis dataKey="t" />
  <YAxis dataKey="v" />
  <Line type="monotone" dataKey="v" stroke="#8884d8" />
</LineChart>
```

**Optimization:** If equity curve has 10,000 points, downsample to 1,000 for smooth rendering.

---

### 3. Get Trade History (Paginated)

**Endpoint:** `GET /api/v1/backtest/{backtest_id}/trades`

**Purpose:** Return paginated trade list for trade history table.

**Query Parameters:**
- `page` (default: 1): Page number
- `per_page` (default: 50, max: 200): Trades per page
- `sort` (optional): `timestamp_asc` or `timestamp_desc` or `pnl_desc`

**Response (200 OK):**
```json
{
  "backtest_id": "a1b2c3d4...",
  "trades": [
    {
      "trade_number": 1,
      "entry": {
        "timestamp": "2026-06-20T10:30:00Z",
        "price": 175.50,
        "shares": 57.0
      },
      "exit": {
        "timestamp": "2026-06-22T14:15:00Z",
        "price": 179.80,
        "shares": 57.0
      },
      "pnl": 245.10,
      "pnl_pct": 2.45,
      "duration_hours": 51.75,
      "type": "LONG"
    }
  ],
  "pagination": {
    "current_page": 1,
    "per_page": 50,
    "total_trades": 243,
    "total_pages": 5,
    "has_next": true,
    "has_prev": false
  }
}
```

**Use Case:** Display in paginated table, sorted by recent or by P/L.

---

### 4. Get Summary (Metrics Only)

**Endpoint:** `GET /api/v1/backtest/{backtest_id}/summary`

**Purpose:** Fast endpoint returning only metrics (no trades or equity curve).

**Response (200 OK):**
```json
{
  "backtest_id": "a1b2c3d4...",
  "strategy_name": "RSI",
  "symbol": "AAPL",
  "total_return_pct": 32.5,
  "sharpe_ratio": 1.85,
  "win_rate_pct": 68.0,
  "max_drawdown_pct": -8.4,
  "num_trades": 487,
  "final_portfolio_value": 13250.0
}
```

**Use Case:** 
- Populate summary cards quickly
- Leaderboards (fetch summaries for 50 backtests fast)
- Preview before loading full results

**Performance:** 10× faster than full results endpoint (no array serialization).

---

### 5. Compare Multiple Strategies

**Endpoint:** `POST /api/v1/backtest/compare`

**Purpose:** Side-by-side comparison of multiple strategies or symbols.

**Request Body:**
```json
{
  "backtest_ids": [
    "id1_RSI_AAPL",
    "id2_MACD_AAPL",
    "id3_MA_CROSSOVER_AAPL"
  ]
}
```

**Response (200 OK):**
```json
{
  "comparison": [
    {
      "backtest_id": "id1_RSI_AAPL",
      "strategy": "RSI",
      "symbol": "AAPL",
      "metrics": {
        "total_return_pct": 32.5,
        "sharpe_ratio": 1.85,
        "win_rate_pct": 68.0,
        "max_drawdown_pct": -8.4,
        "num_trades": 487
      }
    },
    {
      "backtest_id": "id2_MACD_AAPL",
      "strategy": "MACD",
      "symbol": "AAPL",
      "metrics": {
        "total_return_pct": 28.3,
        "sharpe_ratio": 1.45,
        "win_rate_pct": 62.0,
        "max_drawdown_pct": -12.1,
        "num_trades": 342
      }
    },
    {
      "backtest_id": "id3_MA_CROSSOVER_AAPL",
      "strategy": "MA_CROSSOVER",
      "symbol": "AAPL",
      "metrics": {
        "total_return_pct": 12.7,
        "sharpe_ratio": 0.92,
        "win_rate_pct": 55.0,
        "max_drawdown_pct": -15.3,
        "num_trades": 198
      }
    }
  ],
  "winner": {
    "by_return": "id1_RSI_AAPL",
    "by_sharpe": "id1_RSI_AAPL",
    "by_win_rate": "id1_RSI_AAPL",
    "by_drawdown": "id1_RSI_AAPL"
  },
  "summary": "RSI outperformed across all metrics with 32.5% return vs 28.3% (MACD) and 12.7% (MA_CROSSOVER)"
}
```

**Alternative Request (Run New Comparison):**
```json
{
  "strategies": ["RSI", "MACD", "MA_CROSSOVER"],
  "symbol": "AAPL",
  "start_date": "2026-06-20",
  "end_date": "2026-07-20",
  "initial_capital": 10000.0
}
```

**This runs batch backtest internally, then returns comparison.**

**Use Case:** 
- Strategy selection page
- A/B testing UI
- Performance heatmap

---

### 6. Export Results

**Endpoint:** `GET /api/v1/backtest/{backtest_id}/export?format=csv`

**Purpose:** Download results in CSV or JSON format.

**Query Parameters:**
- `format`: `csv` or `json` (default: json)
- `include`: `all` or `trades_only` or `metrics_only`

**Response (CSV):**
```csv
Trade Number,Entry Time,Entry Price,Exit Time,Exit Price,Shares,P/L,P/L %,Duration (hours)
1,2026-06-20T10:30:00Z,175.50,2026-06-22T14:15:00Z,179.80,57,245.10,2.45,51.75
2,2026-06-23T09:00:00Z,178.20,2026-06-25T16:30:00Z,182.50,56,240.80,2.41,55.50
```

**Response Headers:**
```
Content-Type: text/csv
Content-Disposition: attachment; filename="backtest_RSI_AAPL_2026-07-20.csv"
```

**Use Case:** 
- Excel analysis
- External tools (Python notebooks, R)
- Archival/backup

---

### 7. Get Performance Heatmap Data

**Endpoint:** `GET /api/v1/backtest/heatmap?period=30d`

**Purpose:** Matrix of all strategies × all symbols showing returns.

**Query Parameters:**
- `period`: `7d`, `30d`, `90d`, `1y`
- `metric`: `return` (default), `sharpe`, `win_rate`

**Response (200 OK):**
```json
{
  "period": "30d",
  "metric": "return",
  "heatmap": [
    {
      "strategy": "RSI",
      "results": {
        "AAPL": 32.5,
        "GOOGL": 28.3,
        "MSFT": 15.7,
        "BTC": -5.2,
        "ETH": 18.9
      }
    },
    {
      "strategy": "MACD",
      "results": {
        "AAPL": 28.3,
        "GOOGL": 22.1,
        "MSFT": 12.4,
        "BTC": -8.5,
        "ETH": 14.2
      }
    }
  ],
  "best_performer": {
    "strategy": "RSI",
    "symbol": "AAPL",
    "value": 32.5
  },
  "worst_performer": {
    "strategy": "MACD",
    "symbol": "BTC",
    "value": -8.5
  }
}
```

**Use Case:** 
- Strategy leaderboard page
- Dashboard overview
- Heatmap visualization (green = positive, red = negative)

---

## Response Optimization Techniques

### 1. Field Selection

**Allow frontend to specify which fields to include:**

**Endpoint:** `GET /api/v1/backtest/results/{id}?fields=metrics,trades`

**Response (only requested fields):**
```json
{
  "metrics": {...},
  "trades": [...]
  // No equity_curve (not requested)
}
```

**Implementation:**
```python
@app.get("/api/v1/backtest/results/{backtest_id}")
async def get_results(
    backtest_id: str,
    fields: Optional[str] = Query(None, description="Comma-separated: metrics,trades,equity_curve")
):
    result = get_backtest_result(backtest_id)
    
    if fields:
        requested_fields = fields.split(",")
        filtered_result = {}
        
        if "metrics" in requested_fields:
            filtered_result["metrics"] = result.metrics
        if "trades" in requested_fields:
            filtered_result["trades"] = result.trades
        if "equity_curve" in requested_fields:
            filtered_result["equity_curve"] = result.equity_curve
        
        return filtered_result
    
    return result  # Return everything
```

---

### 2. Pagination for Large Results

**Problem:** 5,000 trades → 5MB JSON response → slow

**Solution:** Paginate trades, stream equity curve

```python
from fastapi.responses import StreamingResponse
import json

@app.get("/api/v1/backtest/{backtest_id}/equity-curve/stream")
async def stream_equity_curve(backtest_id: str):
    """Stream equity curve as NDJSON (newline-delimited JSON)."""
    
    result = get_backtest_result(backtest_id)
    
    def generate():
        for point in result.equity_curve:
            yield json.dumps({
                "t": point.timestamp.isoformat(),
                "v": point.value
            }) + "\n"
    
    return StreamingResponse(
        generate(),
        media_type="application/x-ndjson"
    )
```

**Frontend reads stream:**
```javascript
const response = await fetch('/api/v1/backtest/id/equity-curve/stream');
const reader = response.body.getReader();
const decoder = new TextDecoder();

let data = [];
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  
  const chunk = decoder.decode(value);
  const lines = chunk.split('\n').filter(l => l);
  data.push(...lines.map(l => JSON.parse(l)));
}
```

---

### 3. Caching Expensive Computations

**Problem:** Computing summary statistics on 10,000 trades is slow

**Solution:** Cache computed values

```python
from functools import lru_cache

@lru_cache(maxsize=1000)
def compute_trade_statistics(backtest_id: str):
    """Cache trade statistics to avoid recomputation."""
    result = get_backtest_result(backtest_id)
    
    # Expensive computations
    avg_win = sum(t.pnl for t in result.trades if t.pnl > 0) / result.metrics.num_winning_trades
    avg_loss = sum(t.pnl for t in result.trades if t.pnl < 0) / result.metrics.num_losing_trades
    
    return {
        "avg_win": avg_win,
        "avg_loss": avg_loss,
        "risk_reward_ratio": abs(avg_win / avg_loss)
    }
```

---

## Frontend Data Transformation

### Equity Curve for Recharts

```javascript
// Backend returns
{
  "equity_curve": [
    {"t": "2026-06-19T00:00:00Z", "v": 10000.0},
    {"t": "2026-06-20T00:00:00Z", "v": 10050.0}
  ]
}

// Transform for Recharts
const chartData = response.equity_curve.map(point => ({
  timestamp: new Date(point.t).getTime(),
  portfolioValue: point.v
}));

<ResponsiveContainer width="100%" height={400}>
  <LineChart data={chartData}>
    <CartesianGrid strokeDasharray="3 3" />
    <XAxis 
      dataKey="timestamp" 
      tickFormatter={(ts) => new Date(ts).toLocaleDateString()}
    />
    <YAxis 
      tickFormatter={(val) => `$${val.toLocaleString()}`}
    />
    <Tooltip 
      labelFormatter={(ts) => new Date(ts).toLocaleString()}
      formatter={(val) => [`$${val.toFixed(2)}`, 'Portfolio Value']}
    />
    <Line type="monotone" dataKey="portfolioValue" stroke="#8884d8" />
  </LineChart>
</ResponsiveContainer>
```

---

### Comparison Matrix for Heatmap

```javascript
// Backend returns
{
  "heatmap": [
    {"strategy": "RSI", "results": {"AAPL": 32.5, "GOOGL": 28.3}},
    {"strategy": "MACD", "results": {"AAPL": 28.3, "GOOGL": 22.1}}
  ]
}

// Transform to grid
const symbols = ["AAPL", "GOOGL", "MSFT"];
const strategies = response.heatmap.map(row => row.strategy);

const gridData = strategies.map(strategy => {
  const row = response.heatmap.find(r => r.strategy === strategy);
  return symbols.map(symbol => ({
    strategy,
    symbol,
    return: row.results[symbol] || null
  }));
}).flat();

// Render heatmap
<HeatMap
  data={gridData}
  xAccessor="symbol"
  yAccessor="strategy"
  colorAccessor="return"
  colorScale={d3.scaleSequential(d3.interpolateRdYlGn).domain([-20, 40])}
/>
```

---

## Error Handling

### Graceful Degradation

**Problem:** Equity curve has 10,000 points, browser freezes

**Solution:** Automatic downsampling with warning

```python
MAX_EQUITY_POINTS = 2000

@app.get("/api/v1/backtest/{backtest_id}/equity-curve")
async def get_equity_curve(backtest_id: str):
    result = get_backtest_result(backtest_id)
    equity_curve = result.equity_curve
    
    if len(equity_curve) > MAX_EQUITY_POINTS:
        # Downsample: take every Nth point
        step = len(equity_curve) // MAX_EQUITY_POINTS
        equity_curve = equity_curve[::step]
        
        warning = f"Downsampled from {len(result.equity_curve)} to {len(equity_curve)} points"
    else:
        warning = None
    
    return {
        "equity_curve": equity_curve,
        "total_points": len(result.equity_curve),
        "returned_points": len(equity_curve),
        "warning": warning
    }
```

---

## Testing Strategy

### Unit Tests

```python
def test_get_results_success():
    """Test retrieving valid backtest results."""
    # Setup: Run backtest
    backtest_id = submit_backtest(strategy="RSI", symbol="AAPL")
    wait_for_completion(backtest_id)
    
    # Test: Get results
    response = client.get(f"/api/v1/backtest/results/{backtest_id}")
    
    assert response.status_code == 200
    data = response.json()
    assert "metrics" in data
    assert "trades" in data
    assert "equity_curve" in data
    assert data["strategy_name"] == "RSI"

def test_get_results_not_found():
    """Test 404 for non-existent backtest."""
    response = client.get("/api/v1/backtest/results/invalid-id")
    
    assert response.status_code == 404
    assert "not_found" in response.json()["error"]

def test_equity_curve_downsampling():
    """Test equity curve is downsampled for large results."""
    # Create backtest with 10,000 candles
    backtest_id = submit_backtest(
        strategy="RSI",
        symbol="AAPL",
        start_date="2020-01-01",
        end_date="2021-12-31"  # 2 years = ~17,520 hourly candles
    )
    wait_for_completion(backtest_id)
    
    # Get equity curve
    response = client.get(f"/api/v1/backtest/{backtest_id}/equity-curve")
    data = response.json()
    
    # Should be downsampled
    assert data["returned_points"] <= 2000
    assert data["warning"] is not None
```

### Integration Tests

```python
def test_compare_multiple_strategies():
    """Test strategy comparison endpoint."""
    # Run 3 backtests
    id1 = submit_and_wait(strategy="RSI", symbol="AAPL")
    id2 = submit_and_wait(strategy="MACD", symbol="AAPL")
    id3 = submit_and_wait(strategy="MA_CROSSOVER", symbol="AAPL")
    
    # Compare
    response = client.post("/api/v1/backtest/compare", json={
        "backtest_ids": [id1, id2, id3]
    })
    
    assert response.status_code == 200
    data = response.json()
    
    assert len(data["comparison"]) == 3
    assert "winner" in data
    assert data["winner"]["by_return"] in [id1, id2, id3]
```

---

## Performance Benchmarks

### Response Time Targets

| Endpoint | Target | Max Acceptable |
|----------|--------|----------------|
| GET /results/{id} | < 100ms | < 500ms |
| GET /{id}/summary | < 50ms | < 200ms |
| GET /{id}/equity-curve | < 200ms | < 1s |
| GET /{id}/trades (page 1) | < 100ms | < 500ms |
| POST /compare (3 strategies) | < 300ms | < 2s |
| GET /heatmap | < 500ms | < 3s |

### Optimization Checklist

- [ ] Use FastAPI's `response_model` for serialization optimization
- [ ] Implement field selection to reduce payload size
- [ ] Cache frequently accessed results (LRU cache)
- [ ] Paginate large lists (trades, equity curve)
- [ ] Downsample large datasets automatically
- [ ] Use NDJSON streaming for huge responses
- [ ] Add ETag headers for client-side caching
- [ ] Compress responses with gzip (FastAPI middleware)

---

## Frontend Usage Examples

### Results Detail Page

```javascript
// Fetch complete results
const { data } = await axios.get(`/api/v1/backtest/results/${backtestId}`);

// Display summary metrics
<MetricsCard>
  <Metric label="Total Return" value={`${data.metrics.total_return_pct}%`} />
  <Metric label="Sharpe Ratio" value={data.metrics.sharpe_ratio} />
  <Metric label="Win Rate" value={`${data.metrics.win_rate_pct}%`} />
  <Metric label="Max Drawdown" value={`${data.metrics.max_drawdown_pct}%`} />
</MetricsCard>

// Display equity curve
<EquityCurveChart data={data.equity_curve} />

// Display trade history
<TradeHistoryTable trades={data.trades} />
```

### Strategy Comparison

```javascript
// Run comparison
const { data } = await axios.post('/api/v1/backtest/compare', {
  strategies: ['RSI', 'MACD', 'MA_CROSSOVER'],
  symbol: 'AAPL',
  start_date: '2026-06-20',
  end_date: '2026-07-20'
});

// Display comparison table
<ComparisonTable>
  <thead>
    <tr>
      <th>Strategy</th>
      <th>Return</th>
      <th>Sharpe</th>
      <th>Win Rate</th>
      <th>Max DD</th>
    </tr>
  </thead>
  <tbody>
    {data.comparison.map(c => (
      <tr key={c.backtest_id}>
        <td>{c.strategy}</td>
        <td className={c.metrics.total_return_pct > 0 ? 'positive' : 'negative'}>
          {c.metrics.total_return_pct}%
        </td>
        <td>{c.metrics.sharpe_ratio.toFixed(2)}</td>
        <td>{c.metrics.win_rate_pct}%</td>
        <td>{c.metrics.max_drawdown_pct}%</td>
      </tr>
    ))}
  </tbody>
</ComparisonTable>

// Show winner
<Alert type="success">
  🏆 {data.summary}
</Alert>
```

---

## API Documentation (OpenAPI/Swagger)

FastAPI automatically generates interactive docs at `/docs`.

**Additional Documentation:**

```python
@app.get(
    "/api/v1/backtest/results/{backtest_id}",
    response_model=BacktestResult,
    summary="Get complete backtest results",
    description="""
    Retrieve full backtest results including:
    - Performance metrics (return, Sharpe, win rate, etc.)
    - Complete trade history
    - Equity curve (portfolio value over time)
    - Configuration used
    
    **Note:** Results expire after 1 hour. Download if needed long-term.
    """,
    responses={
        200: {"description": "Backtest results retrieved successfully"},
        404: {"description": "Backtest not found or expired"},
    },
    tags=["Results"]
)
async def get_backtest_results(backtest_id: str):
    ...
```

---

## Security & Privacy

### Rate Limiting Results Access

```python
from slowapi import Limiter
from slowapi.util import get_remote_address

limiter = Limiter(key_func=get_remote_address)

@app.get("/api/v1/backtest/results/{backtest_id}")
@limiter.limit("100/minute")  # Max 100 result fetches per minute
async def get_results(backtest_id: str, request: Request):
    ...
```

### Result Ownership (Future: Auth)

```python
# When authentication is added
@app.get("/api/v1/backtest/results/{backtest_id}")
async def get_results(
    backtest_id: str,
    current_user: User = Depends(get_current_user)
):
    result = get_backtest_result(backtest_id)
    
    # Verify ownership
    if result.user_id != current_user.id:
        raise HTTPException(
            status_code=403,
            detail="You don't have permission to access this backtest"
        )
    
    return result
```

---

## Deployment Checklist

- [ ] Set appropriate cache TTL (1 hour default)
- [ ] Configure max response size limit
- [ ] Enable response compression (gzip)
- [ ] Add request ID for debugging (middleware)
- [ ] Log all result accesses
- [ ] Monitor response times (Prometheus)
- [ ] Set up alerts for slow queries (> 2s)
- [ ] Document pagination limits
- [ ] Test with large datasets (10K+ trades)
- [ ] Validate CSV export formatting

---

## Next Steps

After Tasks 10 & 11 are complete:
- **Task 12:** Configuration management (save/load backtest configs)
- **Task 13:** Performance optimization (parallel execution, caching)

Then proceed to:
- **Phase 4:** React frontend to consume these APIs
- **Phase 5:** Real-time WebSocket for live backtest progress

---

**Document Status:** Implementation Guide  
**Last Updated:** 2026-07-20  
**Version:** 1.0
