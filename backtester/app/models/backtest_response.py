"""
Backtest Response Models

Pydantic models for API responses.
"""

from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from datetime import datetime


class BacktestSubmitResponse(BaseModel):
    """Response when backtest is submitted."""

    backtest_id: str = Field(description="Unique backtest identifier")
    status: str = Field(description="Current status (pending)")
    message: str = Field(description="Human-readable message")
    estimated_time_seconds: int = Field(description="Estimated completion time")
    check_status_url: str = Field(description="URL to check status")

    class Config:
        json_schema_extra = {
            "example": {
                "backtest_id": "a1b2c3d4-5678-90ab-cdef-1234567890ab",
                "status": "pending",
                "message": "Backtest queued successfully",
                "estimated_time_seconds": 15,
                "check_status_url": "/api/v1/backtest/status/a1b2c3d4-5678-90ab-cdef-1234567890ab"
            }
        }


class BatchSubmitResponse(BaseModel):
    """Response when batch backtest is submitted."""

    batch_id: str = Field(description="Unique batch identifier")
    total_backtests: int = Field(description="Total number of backtests")
    backtest_ids: List[str] = Field(description="List of all backtest IDs")
    status: str = Field(description="Batch status")
    message: str = Field(description="Human-readable message")

    class Config:
        json_schema_extra = {
            "example": {
                "batch_id": "batch_xyz123",
                "total_backtests": 9,
                "backtest_ids": ["id1", "id2", "id3", "id4", "id5", "id6", "id7", "id8", "id9"],
                "status": "pending",
                "message": "Batch of 9 backtests queued successfully"
            }
        }


class BacktestStatusResponse(BaseModel):
    """Response for backtest status check."""

    backtest_id: str
    status: str
    strategy: str
    symbol: str
    created_at: str
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    duration_seconds: Optional[float] = None
    error: Optional[str] = None

    class Config:
        json_schema_extra = {
            "example": {
                "backtest_id": "a1b2c3d4-5678-90ab-cdef-1234567890ab",
                "status": "completed",
                "strategy": "RSI",
                "symbol": "AAPL",
                "created_at": "2026-07-20T15:50:15Z",
                "started_at": "2026-07-20T15:50:16Z",
                "finished_at": "2026-07-20T15:50:28Z",
                "duration_seconds": 12.5,
                "error": None
            }
        }


class BacktestSummary(BaseModel):
    """Summary of backtest (metrics only, no trades/equity curve)."""

    backtest_id: str
    strategy_name: str
    symbol: str
    total_return_pct: float
    sharpe_ratio: float
    win_rate_pct: float
    max_drawdown_pct: float
    num_trades: int
    final_portfolio_value: float

    class Config:
        json_schema_extra = {
            "example": {
                "backtest_id": "a1b2c3d4-5678-90ab-cdef-1234567890ab",
                "strategy_name": "RSI",
                "symbol": "AAPL",
                "total_return_pct": 32.5,
                "sharpe_ratio": 1.85,
                "win_rate_pct": 68.0,
                "max_drawdown_pct": -8.4,
                "num_trades": 487,
                "final_portfolio_value": 13250.0
            }
        }


class EquityCurvePoint(BaseModel):
    """Single point on equity curve."""
    t: str = Field(description="Timestamp (ISO or unix)")
    v: float = Field(description="Portfolio value")


class EquityCurveResponse(BaseModel):
    """Equity curve data optimized for charts."""

    backtest_id: str
    equity_curve: List[EquityCurvePoint]
    total_points: int
    sampled_points: int
    initial_value: float
    final_value: float
    peak_value: float
    lowest_value: float

    class Config:
        json_schema_extra = {
            "example": {
                "backtest_id": "a1b2c3d4-5678-90ab-cdef-1234567890ab",
                "equity_curve": [
                    {"t": "2026-06-19T00:00:00Z", "v": 10000.0},
                    {"t": "2026-06-20T00:00:00Z", "v": 10050.0}
                ],
                "total_points": 720,
                "sampled_points": 720,
                "initial_value": 10000.0,
                "final_value": 13250.0,
                "peak_value": 13890.0,
                "lowest_value": 9160.0
            }
        }


class ComparisonItem(BaseModel):
    """Single strategy in comparison."""

    backtest_id: str
    strategy: str
    symbol: str
    metrics: Dict[str, Any]


class ComparisonWinner(BaseModel):
    """Winners by different metrics."""

    by_return: str
    by_sharpe: str
    by_win_rate: str
    by_drawdown: str


class CompareResponse(BaseModel):
    """Response for strategy comparison."""

    comparison: List[ComparisonItem]
    winner: ComparisonWinner
    summary: str

    class Config:
        json_schema_extra = {
            "example": {
                "comparison": [
                    {
                        "backtest_id": "id1",
                        "strategy": "RSI",
                        "symbol": "AAPL",
                        "metrics": {
                            "total_return_pct": 32.5,
                            "sharpe_ratio": 1.85,
                            "win_rate_pct": 68.0
                        }
                    }
                ],
                "winner": {
                    "by_return": "id1",
                    "by_sharpe": "id1",
                    "by_win_rate": "id1",
                    "by_drawdown": "id1"
                },
                "summary": "RSI outperformed with 32.5% return"
            }
        }


class RecentBacktest(BaseModel):
    """Entry in recent backtests list."""

    backtest_id: str
    strategy: str
    symbol: str
    status: str
    total_return_pct: Optional[float] = None
    created_at: str


class RecentBacktestsResponse(BaseModel):
    """Response for recent backtests list."""

    backtests: List[RecentBacktest]
    total: int
    limit: int

    class Config:
        json_schema_extra = {
            "example": {
                "backtests": [
                    {
                        "backtest_id": "id1",
                        "strategy": "RSI",
                        "symbol": "AAPL",
                        "status": "completed",
                        "total_return_pct": 32.5,
                        "created_at": "2026-07-20T15:50:15Z"
                    }
                ],
                "total": 1,
                "limit": 20
            }
        }


class ErrorResponse(BaseModel):
    """Standard error response."""

    error: str = Field(description="Error code")
    message: str = Field(description="Human-readable error message")
    details: Optional[Dict[str, Any]] = Field(default=None, description="Additional error details")

    class Config:
        json_schema_extra = {
            "example": {
                "error": "validation_error",
                "message": "start_date must be before end_date",
                "details": {"field": "start_date"}
            }
        }
