"""
Base Strategy Abstract Class

All trading strategies must extend this base class.
"""

from abc import ABC, abstractmethod
from typing import Optional
import pandas as pd
from app.models.signal import Signal


class BaseStrategy(ABC):
    """
    Abstract base class for all trading strategies.

    Each strategy must implement:
    - reset_state(): Initialize/reset internal state variables
    - generate_signal(): Generate BUY/SELL signal from DataFrame
    - get_required_candles(): Return minimum candles needed

    Example:
        >>> class MyStrategy(BaseStrategy):
        ...     def reset_state(self):
        ...         self.prev_value = None
        ...     def generate_signal(self, df):
        ...         # Strategy logic here
        ...         return Signal(action="BUY", confidence=0.85)
        ...     def get_required_candles(self):
        ...         return 20
    """

    def __init__(self, name: str):
        """
        Initialize strategy with name.

        Args:
            name: Human-readable strategy name (e.g., "RSI Mean Reversion")
        """
        self.name = name
        self.reset_state()

    @abstractmethod
    def reset_state(self):
        """
        Reset internal state variables.

        This is called at the start of each backtest to ensure clean state.
        Each strategy should initialize all state variables (e.g., prev_rsi = None).
        """
        pass

    @abstractmethod
    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate trading signal based on historical data.

        Args:
            df: DataFrame with OHLCV data
                Columns: ['timestamp', 'open', 'high', 'low', 'close', 'volume']
                Sorted chronologically (oldest first)
                Contains sufficient lookback window for strategy calculation

        Returns:
            Signal object with action (BUY/SELL) and confidence, or None if no signal

        Note:
            - First call typically returns None (initializing state)
            - Uses previous state to detect crossovers
            - Always updates state before returning
        """
        pass

    @abstractmethod
    def get_required_candles(self) -> int:
        """
        Return minimum number of candles required for this strategy.

        Returns:
            Minimum candles needed (e.g., 50 for MA Crossover, 15 for RSI)

        Note:
            Backtest engine will ensure at least this many candles exist
            before calling generate_signal().
        """
        pass

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(name='{self.name}')"
