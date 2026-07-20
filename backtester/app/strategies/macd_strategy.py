"""
MACD Momentum Strategy

Buys when MACD line crosses above signal line.
Sells when MACD line crosses below signal line.

Matches Phase 2: MacdStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_macd


class MacdStrategy(BaseStrategy):
    """
    MACD Momentum Strategy.

    Parameters:
        - Fast EMA: 12
        - Slow EMA: 26
        - Signal EMA: 9
        - Min Prices: 35 (26 + 9)

    Logic:
        - BUY: MACD line crosses above signal line
        - SELL: MACD line crosses below signal line

    Confidence:
        - Based on crossover strength (gap between lines)
        - Range: 0.75 to 0.90
    """

    FAST_PERIOD = 12
    SLOW_PERIOD = 26
    SIGNAL_PERIOD = 9
    MIN_PRICES = 35

    def __init__(self):
        super().__init__(name="MACD")

    def reset_state(self):
        """Reset previous MACD and signal values."""
        self.prev_macd = None
        self.prev_signal = None

    def get_required_candles(self) -> int:
        """Need 35 candles (26 for MACD + 9 for signal)."""
        return self.MIN_PRICES

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on MACD crossover.

        Args:
            df: DataFrame with 'close' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate MACD
        df_with_macd = calculate_macd(
            df.copy(),
            fast=self.FAST_PERIOD,
            slow=self.SLOW_PERIOD,
            signal=self.SIGNAL_PERIOD
        )

        current_macd = df_with_macd['macd'].iloc[-1]
        current_signal = df_with_macd['macd_signal'].iloc[-1]

        # First run: initialize state
        if self.prev_macd is None:
            self.prev_macd = current_macd
            self.prev_signal = current_signal
            return None

        signal = None

        # BUY: MACD line crosses above signal line
        if current_macd > current_signal and self.prev_macd <= self.prev_signal:
            confidence = self._calculate_confidence(current_macd, current_signal)
            signal = Signal(action="BUY", confidence=confidence)

        # SELL: MACD line crosses below signal line
        elif current_macd < current_signal and self.prev_macd >= self.prev_signal:
            confidence = self._calculate_confidence(current_macd, current_signal)
            signal = Signal(action="SELL", confidence=confidence)

        # Update state for next call
        self.prev_macd = current_macd
        self.prev_signal = current_signal
        return signal

    def _calculate_confidence(self, macd: float, signal: float) -> float:
        """
        Calculate confidence based on crossover strength.

        Larger gap between MACD and signal = stronger signal.

        Args:
            macd: Current MACD line value
            signal: Current signal line value

        Returns:
            Confidence between 0.75 and 0.90
        """
        gap = abs(macd - signal)
        bonus = min(0.15, gap * 0.1)
        return min(0.90, 0.75 + bonus)
