# QuantStream Backtesting API

REST API for backtesting trading strategies on historical market data.

## Quick Start

### 1. Start Server

```bash
source venv/bin/activate
uvicorn app.main:app --host 0.0.0.0 --port 8085 --reload
```

### 2. Access Documentation

- **Swagger UI:** http://localhost:8085/docs
- **ReDoc:** http://localhost:8085/redoc

### 3. Run Your First Backtest

```bash
# Submit backtest
curl -X POST http://localhost:8085/api/v1/backtest/run \
  -H "Content-Type: application/json" \
  -d '{
    "strategy": "RSI",
    "symbol": "AAPL",
    "start_date": "2026-06-20T00:00:00",
    "end_date": "2026-07-20T00:00:00"
  }'

# Save the backtest_id from response
BACKTEST_ID="<your-id-here>"

# Check status (poll every 2 seconds)
curl http://localhost:8085/api/v1/backtest/status/$BACKTEST_ID

# Get results (when status is "completed")
curl http://localhost:8085/api/v1/backtest/results/$BACKTEST_ID
```

## Available Strategies

- `RSI` - RSI Mean Reversion
- `MACD` - MACD Crossover
- `MA_CROSSOVER` - Moving Average Crossover
- `BOLLINGER_BANDS` - Bollinger Bands
- `STOCHASTIC` - Stochastic Oscillator
- `WILLIAMS_R` - Williams %R
- `ADX` - Average Directional Index
- `DONCHIAN` - Donchian Channel
- `ROC` - Rate of Change
- `VWAP` - Volume Weighted Average Price

## Key Endpoints

### Run Backtest

```bash
POST /api/v1/backtest/run
```

**Request:**
```json
{
  "strategy": "RSI",
  "symbol": "AAPL",
  "start_date": "2026-06-20T00:00:00",
  "end_date": "2026-07-20T00:00:00",
  "initial_capital": 10000.0,
  "transaction_cost": 0.001,
  "frequency": "1H"
}
```

**Response (202 Accepted):**
```json
{
  "backtest_id": "abc-123",
  "status": "pending",
  "check_status_url": "/api/v1/backtest/status/abc-123"
}
```

### Check Status

```bash
GET /api/v1/backtest/status/{backtest_id}
```

**Response:**
```json
{
  "status": "completed",
  "duration_seconds": 12.5
}
```

Status values: `pending`, `running`, `completed`, `failed`, `cancelled`

### Get Results

```bash
GET /api/v1/backtest/results/{backtest_id}
```

**Response:**
```json
{
  "strategy_name": "RSI",
  "symbol": "AAPL",
  "metrics": {
    "total_return_pct": 32.5,
    "sharpe_ratio": 1.85,
    "win_rate_pct": 68.0,
    "max_drawdown_pct": -8.4,
    "num_trades": 487
  },
  "trades": [...],
  "equity_curve": [...]
}
```

### Compare Strategies

```bash
POST /api/v1/backtest/compare
```

**Request:**
```json
{
  "backtest_ids": ["id1", "id2", "id3"]
}
```

**Response:**
```json
{
  "comparison": [...],
  "winner": {
    "by_return": "id1",
    "by_sharpe": "id1"
  },
  "summary": "RSI outperformed with 32.5% return"
}
```

## Testing

```bash
# Run all tests
pytest tests/test_api_endpoints.py -v

# Run specific test
pytest tests/test_api_endpoints.py::test_run_backtest_valid -v
```

## Configuration

Environment variables (`.env` file):

```bash
QUESTDB_HOST=localhost
QUESTDB_PORT=8812
QUESTDB_USER=admin
QUESTDB_PASSWORD=quest
DEBUG=false
```

## Documentation

- **Implementation Guides:** `/docs/phase-3/guides/`
  - Task 10: Backtest endpoints
  - Task 11: Results & comparison
- **Architecture:** `/docs/phase-3/architecture/`
- **Overview:** `/docs/phase-3/PHASE-3-OVERVIEW.md`

## Support

- API Docs: http://localhost:8085/docs
- Issues: Report bugs in project repository
- Guides: See `/docs/phase-3/guides/` directory

---

**Version:** 1.0.0  
**Port:** 8085  
**Status:** Production Ready ✅
