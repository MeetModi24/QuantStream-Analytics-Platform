"""
Response models for API endpoints.

Separate from backtest models to keep concerns separated.
"""

from pydantic import BaseModel, Field
from typing import List, Dict, Optional
from datetime import datetime


# ============================================================================
# Market Data Models
# ============================================================================

class TokenSignalCount(BaseModel):
    """Signal counts by action for a token."""
    buy: int = 0
    sell: int = 0
    hold: int = 0


class TokenInfo(BaseModel):
    """Token information with latest price."""
    symbol: str
    current_price: float
    volume: int
    last_updated: Optional[str] = None


class TokenListResponse(BaseModel):
    """Response for GET /tokens"""
    tokens: List[TokenInfo]
    total: int
    last_update: str


class TokenDetail(BaseModel):
    """Detailed token information with 24h stats."""
    symbol: str
    current_price: float
    change_24h_pct: float
    high_24h: float
    low_24h: float
    volume_24h: int
    open_24h: float
    last_updated: Optional[str] = None


class Candle(BaseModel):
    """Single OHLC candle."""
    timestamp: str
    open: float
    high: float
    low: float
    close: float
    volume: int


class CandlesResponse(BaseModel):
    """Response for GET /tokens/{symbol}/candles"""
    symbol: str
    interval: str
    candles: List[Candle]
    count: int


class TickResponse(BaseModel):
    """Response for GET /tokens/{symbol}/tick"""
    symbol: str
    price: float
    volume: int
    timestamp: str


# ============================================================================
# Strategy Models
# ============================================================================

class StrategyInfo(BaseModel):
    """Basic strategy information."""
    name: str
    display_name: str
    type: str
    description: str
    parameters: Dict
    active: bool


class StrategyListResponse(BaseModel):
    """Response for GET /strategies"""
    strategies: List[StrategyInfo]
    total: int


class StrategyStatistics(BaseModel):
    """Strategy execution statistics."""
    total_signals_24h: int
    buy_signals_24h: int
    sell_signals_24h: int
    hold_signals_24h: int
    avg_confidence: float


class StrategyDetail(BaseModel):
    """Detailed strategy information."""
    name: str
    display_name: str
    type: str
    description: str
    parameters: Dict
    active: bool
    statistics: Optional[StrategyStatistics] = None


class LeaderboardEntry(BaseModel):
    """Single entry in strategy leaderboard."""
    rank: int
    strategy_name: str
    display_name: str
    total_return_pct: float
    sharpe_ratio: float
    win_rate_pct: float
    max_drawdown_pct: float
    total_signals: int
    profitable_trades: int


class LeaderboardResponse(BaseModel):
    """Response for GET /strategies/leaderboard"""
    leaderboard: List[LeaderboardEntry]
    period: str
    sorted_by: str
    total_strategies: int


class PerformanceMetrics(BaseModel):
    """Strategy performance metrics."""
    total_return_pct: float
    sharpe_ratio: float
    sortino_ratio: Optional[float] = None
    win_rate_pct: float
    max_drawdown_pct: float
    avg_win_pct: float
    avg_loss_pct: float
    risk_reward_ratio: float
    total_signals: int
    profitable_trades: int
    losing_trades: int


class PerformanceResponse(BaseModel):
    """Response for GET /strategies/{name}/performance"""
    strategy_name: str
    display_name: str
    period: str
    metrics: PerformanceMetrics
    equity_curve: Optional[List[Dict]] = None
    by_symbol: Optional[Dict[str, Dict]] = None


# ============================================================================
# Signal Models
# ============================================================================

class SignalInfo(BaseModel):
    """Trading signal information."""
    timestamp: str
    symbol: str
    strategy_name: str
    action: str
    price: float
    confidence: float


class SignalListResponse(BaseModel):
    """Response for GET /signals/recent and /signals/symbol/{symbol}"""
    signals: List[SignalInfo]
    count: int
    last_updated: str


class SignalStatistics(BaseModel):
    """Aggregated signal statistics."""
    period_hours: int
    total_signals: int
    by_action: Dict[str, int]
    by_strategy: Dict[str, int]
    by_symbol: Dict[str, int]
    avg_confidence: float


class SignalStatisticsResponse(BaseModel):
    """Response for GET /signals/statistics"""
    statistics: SignalStatistics


# ============================================================================
# Health Models
# ============================================================================

class ServiceHealth(BaseModel):
    """Health status of a service."""
    service: str
    status: str  # healthy, unhealthy, unknown
    uptime_seconds: Optional[int] = None
    last_check: str


class HealthResponse(BaseModel):
    """Response for GET /health"""
    status: str  # healthy, degraded, unhealthy
    timestamp: str
    services: Dict[str, str]  # service_name -> status


class ServiceStatusResponse(BaseModel):
    """Response for GET /services/{service_name}/status"""
    service: str
    status: str
    uptime_seconds: Optional[int] = None
    messages_processed: Optional[int] = None
    last_processed: Optional[str] = None
    error_count: int = 0
