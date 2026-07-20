# Task 10: REST API - Backtest Endpoints

**Guide for implementing backtest execution API endpoints**

---

## Overview

This guide covers the implementation of REST API endpoints for running backtests. These endpoints allow users to:
- Run a single backtest on one strategy/symbol combination
- Run batch backtests across multiple strategies or symbols in parallel
- Check the status of running backtests (async execution)
- Cancel long-running backtests

**Technology:** FastAPI (async Python web framework)

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Frontend (React)                      │
│  User selects: Strategy, Symbol, Date Range, Capital    │
└────────────────────┬────────────────────────────────────┘
                     │ HTTP POST
                     ▼
┌─────────────────────────────────────────────────────────┐
│           FastAPI Router (/api/v1/backtest)             │
│  - Validates request (dates, capital, strategy exists)  │
│  - Generates unique backtest_id                         │
│  - Submits to async task queue                          │
│  - Returns 202 Accepted with backtest_id               │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              Background Task Executor                    │
│  - Executes BacktestEngine.run()                        │
│  - Updates status: pending → running → completed/failed │
│  - Stores result in cache/memory                        │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│               Result Cache (In-Memory)                   │
│  Key: backtest_id                                       │
│  Value: {status, result, error, started_at, finished_at}│
│  TTL: 1 hour (configurable)                            │
└─────────────────────────────────────────────────────────┘
```

**Design Decisions:**

1. **Async Execution:** Backtests can take 5-30 seconds → don't block HTTP request
2. **Status Tracking:** Frontend polls `GET /status/{id}` until complete
3. **In-Memory Cache:** Fast access, no DB writes needed (results ephemeral)
4. **Unique IDs:** UUID4 for each backtest to prevent collisions

---

## API Endpoints

### 1. Run Single Backtest

**Endpoint:** `POST /api/v1/backtest/run`

**Purpose:** Execute a backtest for one strategy on one symbol.

**Request Body:**
```json
{
  "strategy": "RSI",
  "symbol": "AAPL",
  "start_date": "2026-06-20",
  "end_date": "2026-07-20",
  "initial_capital": 10000.0,
  "transaction_cost": 0.001,
  "frequency": "1H",
  "parameters": {
    "rsi_period": 14,
    "oversold": 30,
    "overbought": 70
  }
}
```

**Field Validation:**
- `strategy`: Must be one of 10 valid strategies (enum)
- `symbol`: Must exist in our token registry
- `start_date` < `end_date`: Date logic validation
- `end_date - start_date` ≤ 365 days: Prevent excessive queries
- `initial_capital` > 0: Must have starting capital
- `transaction_cost` ≥ 0 and < 0.1: Reasonable cost range (0-10%)
- `frequency`: One of ["1T", "5T", "15T", "1H", "4H", "1D"]
- `parameters`: Optional strategy-specific parameters (validated per strategy)

**Response (202 Accepted):**
```json
{
  "backtest_id": "a1b2c3d4-5678-90ab-cdef-1234567890ab",
  "status": "pending",
  "message": "Backtest queued successfully",
  "estimated_time_seconds": 15,
  "check_status_url": "/api/v1/backtest/status/a1b2c3d4-5678-90ab-cdef-1234567890ab"
}
```

**Error Responses:**

- **400 Bad Request:** Invalid parameters
  ```json
  {
    "error": "validation_error",
    "message": "start_date must be before end_date",
    "field": "start_date"
  }
  ```

- **404 Not Found:** Strategy or symbol doesn't exist
  ```json
  {
    "error": "not_found",
    "message": "Strategy 'INVALID_STRATEGY' not found. Available: RSI, MACD, ...",
    "available_strategies": ["RSI", "MACD", "MA_CROSSOVER", ...]
  }
  ```

- **429 Too Many Requests:** Rate limit exceeded
  ```json
  {
    "error": "rate_limit_exceeded",
    "message": "Maximum 10 concurrent backtests. Please wait.",
    "retry_after_seconds": 30
  }
  ```

---

### 2. Run Batch Backtests

**Endpoint:** `POST /api/v1/backtest/batch`

**Purpose:** Run multiple backtests in parallel (e.g., test 1 strategy across 10 symbols, or 10 strategies on 1 symbol).

**Request Body:**
```json
{
  "strategies": ["RSI", "MACD", "MA_CROSSOVER"],
  "symbols": ["AAPL", "GOOGL", "MSFT"],
  "start_date": "2026-06-20",
  "end_date": "2026-07-20",
  "initial_capital": 10000.0,
  "transaction_cost": 0.001,
  "frequency": "1H"
}
```

**Matrix Execution:**
- Creates: 3 strategies × 3 symbols = **9 backtests**
- All run in parallel (using asyncio)
- Each gets unique `backtest_id`

**Response (202 Accepted):**
```json
{
  "batch_id": "batch_xyz123",
  "total_backtests": 9,
  "backtest_ids": [
    "id1", "id2", "id3", "id4", "id5", "id6", "id7", "id8", "id9"
  ],
  "status": "pending",
  "check_status_url": "/api/v1/backtest/batch/batch_xyz123/status"
}
```

**Constraints:**
- Maximum batch size: 50 backtests (to prevent resource exhaustion)
- All backtests in batch share same date range and config
- If ANY validation fails, ENTIRE batch rejected (atomic)

---

### 3. Check Backtest Status

**Endpoint:** `GET /api/v1/backtest/status/{backtest_id}`

**Purpose:** Poll to check if async backtest has completed.

**Response (Status: Pending):**
```json
{
  "backtest_id": "a1b2c3d4...",
  "status": "pending",
  "message": "Backtest queued, waiting to start",
  "started_at": null,
  "estimated_completion": "2026-07-20T15:50:30Z"
}
```

**Response (Status: Running):**
```json
{
  "backtest_id": "a1b2c3d4...",
  "status": "running",
  "message": "Processing 720 candles...",
  "progress_pct": 45.5,
  "started_at": "2026-07-20T15:50:15Z",
  "estimated_completion": "2026-07-20T15:50:35Z"
}
```

**Response (Status: Completed):**
```json
{
  "backtest_id": "a1b2c3d4...",
  "status": "completed",
  "message": "Backtest completed successfully",
  "started_at": "2026-07-20T15:50:15Z",
  "finished_at": "2026-07-20T15:50:28Z",
  "duration_seconds": 13,
  "result_url": "/api/v1/backtest/results/a1b2c3d4..."
}
```

**Response (Status: Failed):**
```json
{
  "backtest_id": "a1b2c3d4...",
  "status": "failed",
  "error": "insufficient_data",
  "message": "Only 50 candles found, need at least 200 for MACD strategy",
  "started_at": "2026-07-20T15:50:15Z",
  "finished_at": "2026-07-20T15:50:18Z"
}
```

**Status Lifecycle:**
```
pending → running → completed
                 ↘ failed
                 ↘ cancelled
```

---

### 4. Cancel Running Backtest

**Endpoint:** `DELETE /api/v1/backtest/{backtest_id}`

**Purpose:** Cancel a long-running backtest (e.g., user navigates away from page).

**Response (200 OK):**
```json
{
  "backtest_id": "a1b2c3d4...",
  "status": "cancelled",
  "message": "Backtest cancelled successfully"
}
```

**Error (409 Conflict):**
```json
{
  "error": "cannot_cancel",
  "message": "Backtest already completed, cannot cancel",
  "status": "completed"
}
```

---

### 5. List Recent Backtests

**Endpoint:** `GET /api/v1/backtest/recent?limit=20`

**Purpose:** Show user's recent backtest history.

**Response:**
```json
{
  "backtests": [
    {
      "backtest_id": "id1",
      "strategy": "RSI",
      "symbol": "AAPL",
      "status": "completed",
      "total_return_pct": 32.5,
      "created_at": "2026-07-20T15:50:15Z"
    },
    {
      "backtest_id": "id2",
      "strategy": "MACD",
      "symbol": "GOOGL",
      "status": "running",
      "progress_pct": 60.0,
      "created_at": "2026-07-20T15:52:00Z"
    }
  ],
  "total": 2,
  "limit": 20
}
```

---

## Implementation Details

### Strategy Registry

**File:** `app/core/strategy_registry.py`

**Purpose:** Map strategy names to class instances.

```python
STRATEGY_REGISTRY = {
    "RSI": RsiStrategy,
    "MACD": MacdStrategy,
    "MA_CROSSOVER": MaCrossoverStrategy,
    "BOLLINGER_BANDS": BollingerBandsStrategy,
    "STOCHASTIC": StochasticStrategy,
    "WILLIAMS_R": WilliamsRStrategy,
    "ADX": AdxStrategy,
    "DONCHIAN": DonchianChannelStrategy,
    "ROC": RocStrategy,
    "VWAP": VwapStrategy,
}

def get_strategy(name: str, parameters: dict = None):
    """Get strategy instance by name with optional parameters."""
    if name not in STRATEGY_REGISTRY:
        raise ValueError(f"Unknown strategy: {name}")
    
    strategy_class = STRATEGY_REGISTRY[name]
    
    # If parameters provided, pass to constructor
    if parameters:
        return strategy_class(**parameters)
    else:
        return strategy_class()
```

---

### Async Task Manager

**File:** `app/core/task_manager.py`

**Purpose:** Execute backtests asynchronously without blocking API.

```python
import asyncio
from typing import Dict
from datetime import datetime
import uuid

# In-memory storage for backtest status
backtest_cache: Dict[str, dict] = {}

async def run_backtest_async(
    backtest_id: str,
    strategy: BaseStrategy,
    symbol: str,
    start_date: datetime,
    end_date: datetime,
    initial_capital: float,
    transaction_cost: float,
    frequency: str
):
    """
    Execute backtest asynchronously and store result.
    
    Updates backtest_cache with status progression:
    pending → running → completed/failed
    """
    # Update status to running
    backtest_cache[backtest_id]["status"] = "running"
    backtest_cache[backtest_id]["started_at"] = datetime.now()
    
    try:
        # Run backtest (blocking operation in thread pool)
        engine = BacktestEngine(QuestDBFetcher())
        result = await asyncio.to_thread(
            engine.run,
            strategy=strategy,
            symbol=symbol,
            start_date=start_date,
            end_date=end_date,
            initial_capital=initial_capital,
            transaction_cost=transaction_cost,
            frequency=frequency
        )
        
        # Update cache with completed result
        backtest_cache[backtest_id].update({
            "status": "completed",
            "result": result,
            "finished_at": datetime.now()
        })
        
    except Exception as e:
        # Update cache with error
        backtest_cache[backtest_id].update({
            "status": "failed",
            "error": str(e),
            "finished_at": datetime.now()
        })
```

**Key Techniques:**
- `asyncio.to_thread()`: Run CPU-bound backtest in thread pool (don't block event loop)
- In-memory dict: Fast lookup, no DB overhead
- TTL cleanup: Periodically remove old results (background task)

---

### Request Validation

**File:** `app/models/backtest_request.py`

**Pydantic Models:**

```python
from pydantic import BaseModel, Field, validator
from datetime import datetime, timedelta
from typing import Optional, Dict, List
from enum import Enum

class StrategyEnum(str, Enum):
    """Enum of available strategies."""
    RSI = "RSI"
    MACD = "MACD"
    MA_CROSSOVER = "MA_CROSSOVER"
    BOLLINGER_BANDS = "BOLLINGER_BANDS"
    STOCHASTIC = "STOCHASTIC"
    WILLIAMS_R = "WILLIAMS_R"
    ADX = "ADX"
    DONCHIAN = "DONCHIAN"
    ROC = "ROC"
    VWAP = "VWAP"

class FrequencyEnum(str, Enum):
    """Supported candle frequencies."""
    ONE_MINUTE = "1T"
    FIVE_MINUTES = "5T"
    FIFTEEN_MINUTES = "15T"
    ONE_HOUR = "1H"
    FOUR_HOURS = "4H"
    ONE_DAY = "1D"

class BacktestRequest(BaseModel):
    """Request model for running a single backtest."""
    
    strategy: StrategyEnum
    symbol: str = Field(..., min_length=1, max_length=10)
    start_date: datetime
    end_date: datetime
    initial_capital: float = Field(default=10000.0, gt=0, le=1_000_000)
    transaction_cost: float = Field(default=0.001, ge=0, lt=0.1)
    frequency: FrequencyEnum = Field(default=FrequencyEnum.ONE_HOUR)
    parameters: Optional[Dict[str, float]] = None
    
    @validator('end_date')
    def end_after_start(cls, v, values):
        """Ensure end_date is after start_date."""
        if 'start_date' in values and v <= values['start_date']:
            raise ValueError('end_date must be after start_date')
        return v
    
    @validator('end_date')
    def max_period(cls, v, values):
        """Limit backtest period to 365 days."""
        if 'start_date' in values:
            if (v - values['start_date']) > timedelta(days=365):
                raise ValueError('Maximum backtest period is 365 days')
        return v

class BatchBacktestRequest(BaseModel):
    """Request model for batch backtests."""
    
    strategies: List[StrategyEnum] = Field(..., min_items=1, max_items=10)
    symbols: List[str] = Field(..., min_items=1, max_items=10)
    start_date: datetime
    end_date: datetime
    initial_capital: float = Field(default=10000.0, gt=0, le=1_000_000)
    transaction_cost: float = Field(default=0.001, ge=0, lt=0.1)
    frequency: FrequencyEnum = Field(default=FrequencyEnum.ONE_HOUR)
    
    @validator('symbols')
    def max_batch_size(cls, v, values):
        """Limit total backtests to 50."""
        strategies_count = len(values.get('strategies', []))
        total = strategies_count * len(v)
        if total > 50:
            raise ValueError(f'Batch size {total} exceeds maximum of 50')
        return v
```

---

### Error Handling

**Standardized Error Response:**

```python
from fastapi import HTTPException

class BacktestError(HTTPException):
    """Base class for backtest-specific errors."""
    pass

class InsufficientDataError(BacktestError):
    """Raised when not enough historical data available."""
    def __init__(self, symbol: str, candles_found: int, candles_needed: int):
        super().__init__(
            status_code=400,
            detail={
                "error": "insufficient_data",
                "message": f"Only {candles_found} candles found for {symbol}, need {candles_needed}",
                "symbol": symbol,
                "candles_found": candles_found,
                "candles_needed": candles_needed
            }
        )

class StrategyNotFoundError(BacktestError):
    """Raised when strategy name is invalid."""
    def __init__(self, strategy: str, available: List[str]):
        super().__init__(
            status_code=404,
            detail={
                "error": "strategy_not_found",
                "message": f"Strategy '{strategy}' not found",
                "available_strategies": available
            }
        )
```

---

## Frontend Integration

### Polling Pattern

**Frontend should poll status every 2 seconds:**

```javascript
// Run backtest
const response = await fetch('/api/v1/backtest/run', {
  method: 'POST',
  body: JSON.stringify(backtestRequest)
});
const { backtest_id } = await response.json();

// Poll until complete
const pollInterval = setInterval(async () => {
  const statusRes = await fetch(`/api/v1/backtest/status/${backtest_id}`);
  const { status } = await statusRes.json();
  
  if (status === 'completed') {
    clearInterval(pollInterval);
    // Fetch results (Task 11)
    const resultsRes = await fetch(`/api/v1/backtest/results/${backtest_id}`);
    const results = await resultsRes.json();
    displayResults(results);
  } else if (status === 'failed') {
    clearInterval(pollInterval);
    showError();
  }
}, 2000); // Poll every 2 seconds
```

**Alternative: WebSocket (Future Enhancement)**
- Server pushes status updates
- No polling overhead
- Real-time progress tracking

---

## Testing Strategy

### Unit Tests

```python
# tests/test_backtest_api.py

@pytest.mark.asyncio
async def test_run_backtest_valid_request():
    """Test successful backtest submission."""
    client = TestClient(app)
    
    response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20",
        "end_date": "2026-07-20"
    })
    
    assert response.status_code == 202
    data = response.json()
    assert "backtest_id" in data
    assert data["status"] == "pending"

async def test_run_backtest_invalid_dates():
    """Test validation error for invalid dates."""
    client = TestClient(app)
    
    response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-07-20",  # After end_date
        "end_date": "2026-06-20"
    })
    
    assert response.status_code == 400
    data = response.json()
    assert "end_date must be after start_date" in data["detail"]
```

### Integration Tests

```python
@pytest.mark.asyncio
async def test_full_backtest_lifecycle():
    """Test complete flow: submit → poll → get results."""
    client = TestClient(app)
    
    # Submit backtest
    response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20",
        "end_date": "2026-07-20"
    })
    backtest_id = response.json()["backtest_id"]
    
    # Poll until complete (with timeout)
    max_attempts = 30
    for _ in range(max_attempts):
        status_response = client.get(f"/api/v1/backtest/status/{backtest_id}")
        status = status_response.json()["status"]
        
        if status == "completed":
            break
        
        await asyncio.sleep(1)
    
    assert status == "completed"
    
    # Verify result exists (tested in Task 11)
    results_response = client.get(f"/api/v1/backtest/results/{backtest_id}")
    assert results_response.status_code == 200
```

---

## Performance Considerations

### Concurrency Limits

**Problem:** 100 users submit backtests simultaneously → server crashes

**Solution:** Rate limiting + queue

```python
from fastapi import Request
from collections import defaultdict
import time

# Track active backtests per user (in production, use Redis)
active_backtests = defaultdict(int)
MAX_CONCURRENT_PER_USER = 5

@app.middleware("http")
async def rate_limit_middleware(request: Request, call_next):
    """Limit concurrent backtests per user."""
    if request.url.path.startswith("/api/v1/backtest/run"):
        user_id = request.client.host  # In production, use auth token
        
        if active_backtests[user_id] >= MAX_CONCURRENT_PER_USER:
            raise HTTPException(
                status_code=429,
                detail="Too many concurrent backtests. Please wait."
            )
        
        active_backtests[user_id] += 1
        response = await call_next(request)
        active_backtests[user_id] -= 1
        return response
    
    return await call_next(request)
```

### Cache Cleanup

**Problem:** backtest_cache grows indefinitely → memory leak

**Solution:** TTL-based cleanup

```python
import asyncio
from datetime import datetime, timedelta

async def cleanup_old_results():
    """Remove backtest results older than 1 hour."""
    while True:
        now = datetime.now()
        expired_ids = []
        
        for backtest_id, data in backtest_cache.items():
            finished_at = data.get("finished_at")
            if finished_at and (now - finished_at) > timedelta(hours=1):
                expired_ids.append(backtest_id)
        
        for backtest_id in expired_ids:
            del backtest_cache[backtest_id]
        
        await asyncio.sleep(300)  # Run every 5 minutes

# Start cleanup task on app startup
@app.on_event("startup")
async def startup_event():
    asyncio.create_task(cleanup_old_results())
```

---

## Security Considerations

### Input Validation
- ✅ Pydantic validates all inputs
- ✅ Date ranges limited to 365 days
- ✅ Capital capped at $1M (prevent overflow)
- ✅ Symbol length limited (prevent injection)

### Resource Limits
- ✅ Max 50 backtests per batch
- ✅ Max 5 concurrent backtests per user
- ✅ Backtest timeout: 60 seconds

### Error Messages
- ✅ Don't expose internal paths or stack traces
- ✅ Generic errors for production
- ✅ Detailed errors only in debug mode

---

## Deployment Checklist

- [ ] Set appropriate `max_backtest_days` in config
- [ ] Configure rate limiting (Redis in production)
- [ ] Set up monitoring (track backtest durations)
- [ ] Add logging for all backtest submissions
- [ ] Enable CORS for frontend domain
- [ ] Set cache TTL based on traffic patterns
- [ ] Add health check endpoint
- [ ] Document API in Swagger/OpenAPI

---

## Next Steps

After Task 10 is complete, Task 11 will add:
- `GET /api/v1/backtest/results/{id}` - Full backtest results
- `GET /api/v1/backtest/{id}/equity-curve` - Chart data
- `POST /api/v1/backtest/compare` - Compare multiple strategies
- Export functionality (CSV, JSON)

---

**Document Status:** Implementation Guide  
**Last Updated:** 2026-07-20  
**Version:** 1.0
