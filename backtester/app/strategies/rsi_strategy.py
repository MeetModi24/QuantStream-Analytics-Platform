"""
RSI Mean Reversion Strategy

Buys when RSI crosses above oversold threshold (30).
Sells when RSI crosses below overbought threshold (70).

Matches Phase 2: RsiStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_rsi


class RsiStrategy(BaseStrategy):
    """
    RSI Mean Reversion Strategy.

    Parameters:
        - RSI Period: 14
        - Oversold: 30
        - Overbought: 70

    Logic:
        - BUY: RSI crosses ABOVE 30 (was <= 30, now > 30)
        - SELL: RSI crosses BELOW 70 (was >= 70, now < 70)

    Confidence:
        - Higher when RSI is more extreme (further from threshold)
        - Range: 0.75 to 0.90
    """

    RSI_PERIOD = 14
    OVERSOLD = 30.0
    OVERBOUGHT = 70.0

    def __init__(self):
        super().__init__(name="RSI Mean Reversion")

    def reset_state(self):
        """Reset previous RSI value."""
        self.prev_rsi = None

    def get_required_candles(self) -> int:
        """Need RSI_PERIOD + 1 for calculation."""
        return self.RSI_PERIOD + 1

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on RSI crossover.

        Args:
            df: DataFrame with 'close' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate current RSI (pass DataFrame, extract last value)
        df_with_rsi = calculate_rsi(df.copy(), period=self.RSI_PERIOD)
        current_rsi = df_with_rsi['rsi'].iloc[-1]

        # First run: initialize state
        if self.prev_rsi is None:
            self.prev_rsi = current_rsi
            return None

        signal = None

        # BUY: RSI crosses above oversold threshold
        if current_rsi > self.OVERSOLD and self.prev_rsi <= self.OVERSOLD:
            confidence = self._calculate_buy_confidence(current_rsi)
            signal = Signal(action="BUY", confidence=confidence)

        # SELL: RSI crosses below overbought threshold
        elif current_rsi < self.OVERBOUGHT and self.prev_rsi >= self.OVERBOUGHT:
            confidence = self._calculate_sell_confidence(current_rsi)
            signal = Signal(action="SELL", confidence=confidence)

        # Update state for next call
        self.prev_rsi = current_rsi
        return signal

    def _calculate_buy_confidence(self, rsi: float) -> float:
        """
        Calculate BUY confidence.

        More oversold (lower RSI) = higher confidence.

        Args:
            rsi: Current RSI value

        Returns:
            Confidence between 0.75 and 0.90
        """
        # Bonus based on how oversold it was
        bonus = (self.OVERSOLD - rsi) / self.OVERSOLD * 0.15
        return min(0.90, 0.75 + bonus)

    def _calculate_sell_confidence(self, rsi: float) -> float:
        """
        Calculate SELL confidence.

        More overbought (higher RSI) = higher confidence.

        Args:
            rsi: Current RSI value

        Returns:
            Confidence between 0.75 and 0.90
        """
        # Bonus based on how overbought it was
        bonus = (rsi - self.OVERBOUGHT) / (100 - self.OVERBOUGHT) * 0.15
        return min(0.90, 0.75 + bonus)
