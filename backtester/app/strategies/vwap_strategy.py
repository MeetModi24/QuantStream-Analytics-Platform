"""
VWAP Mean Reversion Strategy

Buys when price crosses below VWAP with sufficient deviation.
Sells when price crosses above VWAP with sufficient deviation.

Matches Phase 2: VwapStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_vwap


class VwapStrategy(BaseStrategy):
    """
    VWAP Mean Reversion Strategy.

    Parameters:
        - VWAP Period: 50
        - Min Deviation: 0.5% (filters noise)

    Logic:
        - BUY: Price crosses below VWAP with >= 0.5% deviation
        - SELL: Price crosses above VWAP with >= 0.5% deviation

    Confidence:
        - Based on deviation magnitude
        - Range: 0.75 to 0.90

    Note:
        - Requires volume data
        - Uses volume-weighted average price
    """

    VWAP_PERIOD = 50
    MIN_DEVIATION = 0.005  # 0.5%

    def __init__(self):
        super().__init__(name="VWAP")

    def reset_state(self):
        """Reset price position flag."""
        self.was_above_vwap = True  # Initialize as True (default state from Phase 2)

    def get_required_candles(self) -> int:
        """Need 50 candles for VWAP calculation."""
        return self.VWAP_PERIOD

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on VWAP crossover with deviation filter.

        Args:
            df: DataFrame with 'close' and 'volume' columns

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate VWAP (requires price AND volume)
        df_with_vwap = calculate_vwap(df.copy(), period=self.VWAP_PERIOD)

        vwap = df_with_vwap['vwap'].iloc[-1]
        current_price = df['close'].iloc[-1]

        # Calculate deviation
        if vwap == 0:
            deviation = 0
        else:
            deviation = abs(current_price - vwap) / vwap

        signal = None

        # BUY: Price crosses below VWAP with sufficient deviation
        if (current_price < vwap and self.was_above_vwap and
            deviation >= self.MIN_DEVIATION):
            self.was_above_vwap = False
            confidence = self._calculate_confidence(deviation)
            signal = Signal(action="BUY", confidence=confidence)

        # SELL: Price crosses above VWAP with sufficient deviation
        elif (current_price > vwap and not self.was_above_vwap and
              deviation >= self.MIN_DEVIATION):
            self.was_above_vwap = True
            confidence = self._calculate_confidence(deviation)
            signal = Signal(action="SELL", confidence=confidence)

        # Update state if no signal (track price position for crossover detection)
        if signal is None:
            self.was_above_vwap = current_price > vwap

        return signal

    def _calculate_confidence(self, deviation: float) -> float:
        """
        Calculate confidence based on deviation magnitude.

        Larger deviation = higher confidence.

        Args:
            deviation: Price deviation from VWAP (as decimal, e.g., 0.015 = 1.5%)

        Returns:
            Confidence between 0.75 and 0.90
        """
        # Bonus based on deviation beyond minimum threshold
        bonus = min(0.15, (deviation - self.MIN_DEVIATION) / 0.02 * 0.15)
        return min(0.90, 0.75 + bonus)
