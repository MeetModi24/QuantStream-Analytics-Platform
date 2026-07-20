"""
Backtest Request Models

Pydantic models for validating API requests.
"""

from pydantic import BaseModel, Field, field_validator
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

    strategy: StrategyEnum = Field(
        ...,
        description="Strategy to backtest"
    )
    symbol: str = Field(
        ...,
        min_length=1,
        max_length=10,
        description="Trading symbol (e.g., AAPL, BTC)"
    )
    start_date: datetime = Field(
        ...,
        description="Backtest start date (ISO format)"
    )
    end_date: datetime = Field(
        ...,
        description="Backtest end date (ISO format)"
    )
    initial_capital: float = Field(
        default=10000.0,
        gt=0,
        le=1_000_000,
        description="Starting capital in dollars"
    )
    transaction_cost: float = Field(
        default=0.001,
        ge=0,
        lt=0.1,
        description="Transaction cost as decimal (e.g., 0.001 = 0.1%)"
    )
    frequency: FrequencyEnum = Field(
        default=FrequencyEnum.ONE_HOUR,
        description="Candle frequency"
    )
    parameters: Optional[Dict[str, float]] = Field(
        default=None,
        description="Optional strategy-specific parameters"
    )

    @field_validator('end_date')
    @classmethod
    def end_after_start(cls, v, info):
        """Ensure end_date is after start_date."""
        if 'start_date' in info.data and v <= info.data['start_date']:
            raise ValueError('end_date must be after start_date')
        return v

    @field_validator('end_date')
    @classmethod
    def max_period(cls, v, info):
        """Limit backtest period to 365 days."""
        if 'start_date' in info.data:
            delta = v - info.data['start_date']
            if delta > timedelta(days=365):
                raise ValueError('Maximum backtest period is 365 days')
        return v

    @field_validator('symbol')
    @classmethod
    def uppercase_symbol(cls, v):
        """Convert symbol to uppercase."""
        return v.upper()

    class Config:
        json_schema_extra = {
            "example": {
                "strategy": "RSI",
                "symbol": "AAPL",
                "start_date": "2026-06-20T00:00:00Z",
                "end_date": "2026-07-20T23:59:59Z",
                "initial_capital": 10000.0,
                "transaction_cost": 0.001,
                "frequency": "1H",
                "parameters": {
                    "rsi_period": 14,
                    "oversold": 30,
                    "overbought": 70
                }
            }
        }


class BatchBacktestRequest(BaseModel):
    """Request model for batch backtests."""

    strategies: List[StrategyEnum] = Field(
        ...,
        min_length=1,
        max_length=10,
        description="List of strategies to test"
    )
    symbols: List[str] = Field(
        ...,
        min_length=1,
        max_length=10,
        description="List of symbols to test"
    )
    start_date: datetime = Field(
        ...,
        description="Backtest start date"
    )
    end_date: datetime = Field(
        ...,
        description="Backtest end date"
    )
    initial_capital: float = Field(
        default=10000.0,
        gt=0,
        le=1_000_000,
        description="Starting capital"
    )
    transaction_cost: float = Field(
        default=0.001,
        ge=0,
        lt=0.1,
        description="Transaction cost"
    )
    frequency: FrequencyEnum = Field(
        default=FrequencyEnum.ONE_HOUR,
        description="Candle frequency"
    )

    @field_validator('symbols')
    @classmethod
    def max_batch_size(cls, v, info):
        """Limit total backtests to 50."""
        strategies_count = len(info.data.get('strategies', []))
        total = strategies_count * len(v)
        if total > 50:
            raise ValueError(f'Batch size {total} exceeds maximum of 50')
        return v

    @field_validator('symbols')
    @classmethod
    def uppercase_symbols(cls, v):
        """Convert all symbols to uppercase."""
        return [s.upper() for s in v]

    @field_validator('end_date')
    @classmethod
    def validate_end_date(cls, v, info):
        """Validate end_date is after start_date."""
        if 'start_date' in info.data and v <= info.data['start_date']:
            raise ValueError('end_date must be after start_date')
        return v

    class Config:
        json_schema_extra = {
            "example": {
                "strategies": ["RSI", "MACD", "MA_CROSSOVER"],
                "symbols": ["AAPL", "GOOGL", "MSFT"],
                "start_date": "2026-06-20T00:00:00Z",
                "end_date": "2026-07-20T23:59:59Z",
                "initial_capital": 10000.0,
                "transaction_cost": 0.001,
                "frequency": "1H"
            }
        }


class CompareRequest(BaseModel):
    """Request model for comparing backtest results."""

    backtest_ids: List[str] = Field(
        ...,
        min_length=2,
        max_length=10,
        description="List of backtest IDs to compare"
    )

    class Config:
        json_schema_extra = {
            "example": {
                "backtest_ids": [
                    "a1b2c3d4-5678-90ab-cdef-1234567890ab",
                    "b2c3d4e5-6789-01bc-def0-2345678901bc",
                    "c3d4e5f6-7890-12cd-ef01-3456789012cd"
                ]
            }
        }
