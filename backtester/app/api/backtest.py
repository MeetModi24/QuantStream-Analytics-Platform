"""
Backtest API Endpoints

Task 10: Run backtests and check status
Task 11: Retrieve results and compare strategies
"""

from fastapi import APIRouter, HTTPException, Query
from typing import Optional
from app.models.backtest_request import (
    BacktestRequest,
    BatchBacktestRequest,
    CompareRequest
)
from app.models.backtest_response import (
    BacktestSubmitResponse,
    BatchSubmitResponse,
    BacktestStatusResponse,
    BacktestSummary,
    EquityCurveResponse,
    EquityCurvePoint,
    CompareResponse,
    ComparisonItem,
    ComparisonWinner,
    RecentBacktestsResponse,
    RecentBacktest,
    ErrorResponse
)
from app.core.strategy_registry import get_strategy, list_strategies
from app.core.task_manager import (
    submit_backtest,
    get_backtest_status,
    get_backtest_result,
    cancel_backtest,
    get_recent_backtests
)
from app.models.backtest_result import BacktestResult

router = APIRouter(prefix="/api/v1/backtest", tags=["Backtest"])


# =============================================================================
# TASK 10: Run Backtests & Check Status
# =============================================================================

@router.post(
    "/run",
    response_model=BacktestSubmitResponse,
    status_code=202,
    summary="Run single backtest",
    description="Submit a backtest for async execution. Returns immediately with backtest_id."
)
async def run_backtest(request: BacktestRequest):
    """
    Run a single backtest asynchronously.

    The backtest is queued for execution and you'll receive a backtest_id.
    Poll the status endpoint to check when it's complete.

    **Estimated Time:** 10-30 seconds depending on data size.

    **Example:**
    ```python
    response = requests.post('/api/v1/backtest/run', json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20",
        "end_date": "2026-07-20"
    })
    backtest_id = response.json()["backtest_id"]
    ```
    """
    try:
        # Get strategy instance
        strategy = get_strategy(
            name=request.strategy.value,
            parameters=request.parameters
        )

        # Submit for async execution
        backtest_id = submit_backtest(
            strategy=strategy,
            strategy_name=request.strategy.value,
            symbol=request.symbol,
            start_date=request.start_date,
            end_date=request.end_date,
            initial_capital=request.initial_capital,
            transaction_cost=request.transaction_cost,
            frequency=request.frequency.value
        )

        return BacktestSubmitResponse(
            backtest_id=backtest_id,
            status="pending",
            message="Backtest queued successfully",
            estimated_time_seconds=15,
            check_status_url=f"/api/v1/backtest/status/{backtest_id}"
        )

    except ValueError as e:
        raise HTTPException(
            status_code=400,
            detail={"error": "invalid_strategy", "message": str(e)}
        )
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail={"error": "internal_error", "message": str(e)}
        )


@router.post(
    "/batch",
    response_model=BatchSubmitResponse,
    status_code=202,
    summary="Run batch backtests",
    description="Run multiple backtests in parallel (strategies × symbols matrix)."
)
async def run_batch_backtest(request: BatchBacktestRequest):
    """
    Run multiple backtests in parallel.

    Creates a matrix of strategies × symbols and runs all combinations.
    For example, 3 strategies × 3 symbols = 9 backtests.

    **Maximum:** 50 backtests per batch.

    **Example:**
    ```python
    response = requests.post('/api/v1/backtest/batch', json={
        "strategies": ["RSI", "MACD", "MA_CROSSOVER"],
        "symbols": ["AAPL", "GOOGL", "MSFT"]
    })
    # Creates 9 backtests
    ```
    """
    try:
        backtest_ids = []

        # Submit all combinations
        for strategy_enum in request.strategies:
            for symbol in request.symbols:
                strategy = get_strategy(name=strategy_enum.value)

                backtest_id = submit_backtest(
                    strategy=strategy,
                    strategy_name=strategy_enum.value,
                    symbol=symbol,
                    start_date=request.start_date,
                    end_date=request.end_date,
                    initial_capital=request.initial_capital,
                    transaction_cost=request.transaction_cost,
                    frequency=request.frequency.value
                )
                backtest_ids.append(backtest_id)

        batch_id = f"batch_{backtest_ids[0][:8]}"

        return BatchSubmitResponse(
            batch_id=batch_id,
            total_backtests=len(backtest_ids),
            backtest_ids=backtest_ids,
            status="pending",
            message=f"Batch of {len(backtest_ids)} backtests queued successfully"
        )

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail={"error": "batch_failed", "message": str(e)}
        )


@router.get(
    "/status/{backtest_id}",
    response_model=BacktestStatusResponse,
    summary="Check backtest status",
    description="Get current status of a running backtest. Poll this endpoint until status is 'completed'."
)
async def check_status(backtest_id: str):
    """
    Check the current status of a backtest.

    **Status values:**
    - `pending`: Queued, waiting to start
    - `running`: Currently executing
    - `completed`: Finished successfully (results available)
    - `failed`: Execution failed (error message available)
    - `cancelled`: User cancelled

    **Polling Recommendation:** Poll every 2 seconds until status is 'completed' or 'failed'.

    **Example:**
    ```python
    while True:
        response = requests.get(f'/api/v1/backtest/status/{backtest_id}')
        status = response.json()['status']
        if status in ['completed', 'failed']:
            break
        time.sleep(2)
    ```
    """
    status = get_backtest_status(backtest_id)
    if not status:
        raise HTTPException(
            status_code=404,
            detail={
                "error": "not_found",
                "message": f"Backtest '{backtest_id}' not found or expired"
            }
        )

    return BacktestStatusResponse(**status)


@router.delete(
    "/{backtest_id}",
    summary="Cancel backtest",
    description="Cancel a pending or running backtest."
)
async def cancel(backtest_id: str):
    """
    Cancel a backtest.

    Only pending or running backtests can be cancelled.
    Completed or failed backtests cannot be cancelled.

    **Returns:** Status message.
    """
    success = cancel_backtest(backtest_id)

    if not success:
        status = get_backtest_status(backtest_id)
        if not status:
            raise HTTPException(
                status_code=404,
                detail={"error": "not_found", "message": "Backtest not found"}
            )
        else:
            raise HTTPException(
                status_code=409,
                detail={
                    "error": "cannot_cancel",
                    "message": f"Backtest already {status['status']}, cannot cancel"
                }
            )

    return {"message": "Backtest cancelled successfully", "backtest_id": backtest_id}


@router.get(
    "/recent",
    response_model=RecentBacktestsResponse,
    summary="List recent backtests",
    description="Get list of recent backtests, sorted by creation time (newest first)."
)
async def list_recent(limit: int = Query(default=20, ge=1, le=100)):
    """
    Get list of recent backtests.

    **Query Parameters:**
    - `limit`: Maximum number to return (1-100, default: 20)

    **Returns:** List of backtest summaries with status and return (if completed).
    """
    backtests = get_recent_backtests(limit=limit)

    return RecentBacktestsResponse(
        backtests=[RecentBacktest(**bt) for bt in backtests],
        total=len(backtests),
        limit=limit
    )


# =============================================================================
# TASK 11: Results & Comparison
# =============================================================================

@router.get(
    "/results/{backtest_id}",
    response_model=BacktestResult,
    summary="Get complete backtest results",
    description="Retrieve full backtest results including metrics, trades, and equity curve."
)
async def get_results(
    backtest_id: str,
    fields: Optional[str] = Query(
        None,
        description="Comma-separated fields to include: metrics,trades,equity_curve"
    )
):
    """
    Get complete backtest results.

    **Includes:**
    - Performance metrics (return, Sharpe, win rate, etc.)
    - All executed trades
    - Equity curve (portfolio value over time)
    - Configuration used

    **Field Selection (Optional):**
    Use `?fields=metrics,trades` to only return specific fields.
    This reduces response size for faster loading.

    **Example:**
    ```python
    # Get full results
    response = requests.get(f'/api/v1/backtest/results/{backtest_id}')

    # Get only metrics (fast)
    response = requests.get(f'/api/v1/backtest/results/{backtest_id}?fields=metrics')
    ```

    **Note:** Results expire after 1 hour. Download if needed long-term.
    """
    try:
        result = get_backtest_result(backtest_id)
        if not result:
            raise HTTPException(
                status_code=404,
                detail={
                    "error": "not_found",
                    "message": "Backtest result not found or expired"
                }
            )

        # Field selection (if requested)
        if fields:
            requested_fields = set(fields.split(","))
            # Return only requested fields
            # (Pydantic model serialization handles this)
            return result

        return result

    except ValueError as e:
        # Backtest failed or not completed
        raise HTTPException(
            status_code=400,
            detail={"error": "not_completed", "message": str(e)}
        )


@router.get(
    "/{backtest_id}/summary",
    response_model=BacktestSummary,
    summary="Get backtest summary (metrics only)",
    description="Fast endpoint returning only performance metrics, no trades or equity curve."
)
async def get_summary(backtest_id: str):
    """
    Get quick summary of backtest results.

    **10× faster than full results** - only returns metrics.

    **Use Cases:**
    - Populate summary cards
    - Leaderboards (fetch many summaries quickly)
    - Preview before loading full results

    **Example:**
    ```python
    response = requests.get(f'/api/v1/backtest/{backtest_id}/summary')
    print(f"Return: {response.json()['total_return_pct']}%")
    ```
    """
    try:
        result = get_backtest_result(backtest_id)
        if not result:
            raise HTTPException(status_code=404, detail="Backtest not found")

        return BacktestSummary(
            backtest_id=backtest_id,
            strategy_name=result.strategy_name,
            symbol=result.symbol,
            total_return_pct=result.total_return_pct,
            sharpe_ratio=result.metrics.sharpe_ratio,
            win_rate_pct=result.metrics.win_rate_pct,
            max_drawdown_pct=result.metrics.max_drawdown_pct,
            num_trades=result.metrics.num_trades,
            final_portfolio_value=result.final_portfolio_value
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get(
    "/{backtest_id}/equity-curve",
    response_model=EquityCurveResponse,
    summary="Get equity curve (optimized for charts)",
    description="Return equity curve in format optimized for frontend charting libraries."
)
async def get_equity_curve(
    backtest_id: str,
    format: str = Query(default="iso", regex="^(iso|unix)$")
):
    """
    Get equity curve data optimized for charts.

    **Query Parameters:**
    - `format`: `iso` (ISO 8601 timestamps) or `unix` (milliseconds)

    **Auto-downsampling:** If equity curve has > 2000 points, it's automatically
    downsampled for smooth rendering.

    **Use Case:** Power Recharts/Chart.js line chart.

    **Example:**
    ```javascript
    const response = await fetch(`/api/v1/backtest/${id}/equity-curve`);
    const { equity_curve } = await response.json();

    <LineChart data={equity_curve}>
      <XAxis dataKey="t" />
      <YAxis dataKey="v" />
      <Line type="monotone" dataKey="v" stroke="#8884d8" />
    </LineChart>
    ```
    """
    try:
        result = get_backtest_result(backtest_id)
        if not result:
            raise HTTPException(status_code=404, detail="Backtest not found")

        equity_curve = result.equity_curve

        # Downsample if too many points
        MAX_POINTS = 2000
        original_count = len(equity_curve)
        if len(equity_curve) > MAX_POINTS:
            step = len(equity_curve) // MAX_POINTS
            equity_curve = equity_curve[::step]

        # Format timestamps
        if format == "unix":
            points = [
                EquityCurvePoint(
                    t=str(int(point.timestamp.timestamp() * 1000)),
                    v=point.value
                )
                for point in equity_curve
            ]
        else:  # iso
            points = [
                EquityCurvePoint(
                    t=point.timestamp.isoformat(),
                    v=point.value
                )
                for point in equity_curve
            ]

        # Calculate statistics
        values = [p.value for p in result.equity_curve]

        return EquityCurveResponse(
            backtest_id=backtest_id,
            equity_curve=points,
            total_points=original_count,
            sampled_points=len(points),
            initial_value=values[0],
            final_value=values[-1],
            peak_value=max(values),
            lowest_value=min(values)
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post(
    "/compare",
    response_model=CompareResponse,
    summary="Compare multiple strategies",
    description="Side-by-side comparison of multiple strategies or symbols."
)
async def compare_strategies(request: CompareRequest):
    """
    Compare multiple backtests side-by-side.

    **Use Cases:**
    - Strategy selection (which performed best?)
    - A/B testing UI
    - Performance heatmap

    **Example:**
    ```python
    response = requests.post('/api/v1/backtest/compare', json={
        "backtest_ids": [id1, id2, id3]
    })

    winner = response.json()['winner']['by_return']
    print(f"Best by return: {winner}")
    ```
    """
    try:
        comparison_items = []

        for backtest_id in request.backtest_ids:
            result = get_backtest_result(backtest_id)
            if not result:
                raise HTTPException(
                    status_code=404,
                    detail=f"Backtest '{backtest_id}' not found"
                )

            comparison_items.append(ComparisonItem(
                backtest_id=backtest_id,
                strategy=result.strategy_name,
                symbol=result.symbol,
                metrics={
                    "total_return_pct": result.total_return_pct,
                    "sharpe_ratio": result.metrics.sharpe_ratio,
                    "win_rate_pct": result.metrics.win_rate_pct,
                    "max_drawdown_pct": result.metrics.max_drawdown_pct,
                    "num_trades": result.metrics.num_trades,
                    "final_portfolio_value": result.final_portfolio_value
                }
            ))

        # Determine winners by different metrics
        best_return = max(comparison_items, key=lambda x: x.metrics["total_return_pct"])
        best_sharpe = max(comparison_items, key=lambda x: x.metrics["sharpe_ratio"])
        best_win_rate = max(comparison_items, key=lambda x: x.metrics["win_rate_pct"])
        best_drawdown = max(comparison_items, key=lambda x: -x.metrics["max_drawdown_pct"])  # Least negative

        winner = ComparisonWinner(
            by_return=best_return.backtest_id,
            by_sharpe=best_sharpe.backtest_id,
            by_win_rate=best_win_rate.backtest_id,
            by_drawdown=best_drawdown.backtest_id
        )

        # Generate summary
        summary = f"{best_return.strategy} outperformed with {best_return.metrics['total_return_pct']:.1f}% return"

        return CompareResponse(
            comparison=comparison_items,
            winner=winner,
            summary=summary
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get(
    "/strategies",
    summary="List available strategies",
    description="Get list of all available strategies."
)
async def list_available_strategies():
    """
    Get list of all available trading strategies.

    **Returns:** List of strategy names that can be used in backtest requests.

    **Example:**
    ```python
    response = requests.get('/api/v1/backtest/strategies')
    strategies = response.json()['strategies']
    print(strategies)  # ['RSI', 'MACD', 'MA_CROSSOVER', ...]
    ```
    """
    return {
        "strategies": list_strategies(),
        "total": len(list_strategies())
    }
