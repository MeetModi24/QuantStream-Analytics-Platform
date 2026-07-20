"""
Task Manager

Manages async execution of backtests with status tracking.
"""

import asyncio
import uuid
from typing import Dict, Optional
from datetime import datetime, timedelta
from enum import Enum
from app.core.backtest_engine import BacktestEngine
from app.core.data_fetcher import QuestDBFetcher
from app.strategies.base_strategy import BaseStrategy
from app.models.backtest_result import BacktestResult


class BacktestStatus(str, Enum):
    """Backtest execution status."""
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class BacktestTask:
    """Container for backtest task information."""

    def __init__(
        self,
        backtest_id: str,
        strategy_name: str,
        symbol: str,
        start_date: datetime,
        end_date: datetime
    ):
        self.backtest_id = backtest_id
        self.strategy_name = strategy_name
        self.symbol = symbol
        self.start_date = start_date
        self.end_date = end_date
        self.status = BacktestStatus.PENDING
        self.result: Optional[BacktestResult] = None
        self.error: Optional[str] = None
        self.created_at = datetime.now()
        self.started_at: Optional[datetime] = None
        self.finished_at: Optional[datetime] = None


# In-memory storage for backtest tasks
_backtest_cache: Dict[str, BacktestTask] = {}


def generate_backtest_id() -> str:
    """
    Generate unique backtest ID.

    Returns:
        UUID4 string
    """
    return str(uuid.uuid4())


async def run_backtest_async(
    backtest_id: str,
    strategy: BaseStrategy,
    symbol: str,
    start_date: datetime,
    end_date: datetime,
    initial_capital: float = 10000.0,
    transaction_cost: float = 0.001,
    frequency: str = "1H"
) -> None:
    """
    Execute backtest asynchronously and store result.

    Updates task status throughout execution:
    pending → running → completed/failed

    Args:
        backtest_id: Unique identifier for this backtest
        strategy: Strategy instance
        symbol: Trading symbol
        start_date: Start date
        end_date: End date
        initial_capital: Starting capital
        transaction_cost: Transaction cost as decimal
        frequency: Candle frequency

    Note:
        This function updates _backtest_cache directly.
        Use get_backtest_status() and get_backtest_result() to access.
    """
    task = _backtest_cache.get(backtest_id)
    if not task:
        return  # Task was deleted/cancelled

    # Update status to running
    task.status = BacktestStatus.RUNNING
    task.started_at = datetime.now()

    try:
        # Run backtest in thread pool (CPU-bound operation)
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

        # Update task with successful result
        task.status = BacktestStatus.COMPLETED
        task.result = result
        task.finished_at = datetime.now()

    except Exception as e:
        # Update task with error
        task.status = BacktestStatus.FAILED
        task.error = str(e)
        task.finished_at = datetime.now()


def submit_backtest(
    strategy: BaseStrategy,
    strategy_name: str,
    symbol: str,
    start_date: datetime,
    end_date: datetime,
    initial_capital: float = 10000.0,
    transaction_cost: float = 0.001,
    frequency: str = "1H"
) -> str:
    """
    Submit backtest for async execution.

    Args:
        strategy: Strategy instance
        strategy_name: Human-readable strategy name
        symbol: Trading symbol
        start_date: Start date
        end_date: End date
        initial_capital: Starting capital
        transaction_cost: Transaction cost
        frequency: Candle frequency

    Returns:
        backtest_id for tracking

    Example:
        >>> from app.strategies import RsiStrategy
        >>> backtest_id = submit_backtest(
        ...     strategy=RsiStrategy(),
        ...     strategy_name="RSI",
        ...     symbol="AAPL",
        ...     start_date=datetime(2026, 6, 20),
        ...     end_date=datetime(2026, 7, 20)
        ... )
    """
    # Generate unique ID
    backtest_id = generate_backtest_id()

    # Create task
    task = BacktestTask(
        backtest_id=backtest_id,
        strategy_name=strategy_name,
        symbol=symbol,
        start_date=start_date,
        end_date=end_date
    )
    _backtest_cache[backtest_id] = task

    # Start async execution (don't await - fire and forget)
    asyncio.create_task(
        run_backtest_async(
            backtest_id=backtest_id,
            strategy=strategy,
            symbol=symbol,
            start_date=start_date,
            end_date=end_date,
            initial_capital=initial_capital,
            transaction_cost=transaction_cost,
            frequency=frequency
        )
    )

    return backtest_id


def get_backtest_status(backtest_id: str) -> Optional[Dict]:
    """
    Get current status of backtest.

    Args:
        backtest_id: Backtest identifier

    Returns:
        Status dictionary or None if not found

    Example:
        >>> status = get_backtest_status("abc-123")
        >>> print(status["status"])  # "running"
    """
    task = _backtest_cache.get(backtest_id)
    if not task:
        return None

    status_dict = {
        "backtest_id": task.backtest_id,
        "status": task.status.value,
        "strategy": task.strategy_name,
        "symbol": task.symbol,
        "created_at": task.created_at.isoformat(),
        "started_at": task.started_at.isoformat() if task.started_at else None,
        "finished_at": task.finished_at.isoformat() if task.finished_at else None,
    }

    # Add duration if finished
    if task.started_at and task.finished_at:
        duration = (task.finished_at - task.started_at).total_seconds()
        status_dict["duration_seconds"] = round(duration, 2)

    # Add error if failed
    if task.status == BacktestStatus.FAILED:
        status_dict["error"] = task.error

    return status_dict


def get_backtest_result(backtest_id: str) -> Optional[BacktestResult]:
    """
    Get backtest result (only available if completed).

    Args:
        backtest_id: Backtest identifier

    Returns:
        BacktestResult or None if not completed

    Raises:
        ValueError: If backtest failed or still running

    Example:
        >>> result = get_backtest_result("abc-123")
        >>> if result:
        ...     print(f"Return: {result.total_return_pct}%")
    """
    task = _backtest_cache.get(backtest_id)
    if not task:
        return None

    if task.status == BacktestStatus.FAILED:
        raise ValueError(f"Backtest failed: {task.error}")

    if task.status != BacktestStatus.COMPLETED:
        raise ValueError(f"Backtest not completed yet (status: {task.status.value})")

    return task.result


def cancel_backtest(backtest_id: str) -> bool:
    """
    Cancel pending or running backtest.

    Args:
        backtest_id: Backtest identifier

    Returns:
        True if cancelled, False if already completed/failed

    Note:
        Currently cancellation just marks status.
        Actual task termination not implemented (requires more complex async management).
    """
    task = _backtest_cache.get(backtest_id)
    if not task:
        return False

    if task.status in [BacktestStatus.COMPLETED, BacktestStatus.FAILED]:
        return False  # Cannot cancel completed/failed

    task.status = BacktestStatus.CANCELLED
    task.finished_at = datetime.now()
    return True


def cleanup_expired_results(ttl_hours: int = 1):
    """
    Remove backtest results older than TTL.

    Args:
        ttl_hours: Time-to-live in hours (default: 1)

    Returns:
        Number of results removed

    Example:
        >>> cleanup_expired_results(ttl_hours=2)
        5  # Removed 5 expired results
    """
    now = datetime.now()
    expired_ids = []

    for backtest_id, task in _backtest_cache.items():
        if task.finished_at:
            age = now - task.finished_at
            if age > timedelta(hours=ttl_hours):
                expired_ids.append(backtest_id)

    for backtest_id in expired_ids:
        del _backtest_cache[backtest_id]

    return len(expired_ids)


def get_recent_backtests(limit: int = 20) -> list:
    """
    Get list of recent backtests.

    Args:
        limit: Maximum number to return

    Returns:
        List of backtest summaries, sorted by creation time (newest first)

    Example:
        >>> recent = get_recent_backtests(limit=10)
        >>> for bt in recent:
        ...     print(f"{bt['strategy']} on {bt['symbol']}: {bt['status']}")
    """
    tasks = sorted(
        _backtest_cache.values(),
        key=lambda t: t.created_at,
        reverse=True
    )[:limit]

    summaries = []
    for task in tasks:
        summary = {
            "backtest_id": task.backtest_id,
            "strategy": task.strategy_name,
            "symbol": task.symbol,
            "status": task.status.value,
            "created_at": task.created_at.isoformat(),
        }

        # Add return if completed
        if task.status == BacktestStatus.COMPLETED and task.result:
            summary["total_return_pct"] = task.result.total_return_pct

        summaries.append(summary)

    return summaries


# Background cleanup task
async def start_cleanup_task(interval_minutes: int = 30, ttl_hours: int = 1):
    """
    Start background task to clean up expired results periodically.

    Args:
        interval_minutes: How often to run cleanup
        ttl_hours: Result time-to-live

    Note:
        Call this on app startup:
        @app.on_event("startup")
        async def startup():
            asyncio.create_task(start_cleanup_task())
    """
    while True:
        await asyncio.sleep(interval_minutes * 60)
        removed = cleanup_expired_results(ttl_hours=ttl_hours)
        if removed > 0:
            print(f"Cleaned up {removed} expired backtest results")
