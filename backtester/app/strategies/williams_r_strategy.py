"""
Williams %R Momentum Strategy

Buys when %R crosses above -80 (oversold).
Sells when %R crosses below -20 (overbought).

Matches Phase 2: WilliamsRStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_williams_r


class WilliamsRStrategy(BaseStrategy):
    """
    Williams %R Momentum Strategy.

    Parameters:
        - Period: 14
        - Oversold: -80
        - Overbought: -20
        - Range: -100 to 0 (negative scale)

    Logic:
        - BUY: %R crosses above -80 (was <= -80, now > -80)
        - SELL: %R crosses below -20 (was >= -20, now < -20)

    Confidence:
        - Based on extremity
        - Range: 0.75 to 0.90
    """

    WILLIAMS_PERIOD = 14
    OVERSOLD = -80.0
    OVERBOUGHT = -20.0

    def __init__(self):
        super().__init__(name="Williams %R")

    def reset_state(self):
        """Reset previous Williams %R value."""
        self.prev_r = None

    def get_required_candles(self) -> int:
        """Need 14 candles for Williams %R."""
        return self.WILLIAMS_PERIOD

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on Williams %R crossover.

        Args:
            df: DataFrame with 'high', 'low', 'close' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate Williams %R
        df_with_wr = calculate_williams_r(df.copy(), period=self.WILLIAMS_PERIOD)
        current_r = df_with_wr['williams_r'].iloc[-1]

        # First run: initialize state
        if self.prev_r is None:
            self.prev_r = current_r
            return None

        signal = None

        # BUY: %R crosses above -80 (oversold)
        if current_r > self.OVERSOLD and self.prev_r <= self.OVERSOLD:
            confidence = self._calculate_confidence(current_r, is_buy=True)
            signal = Signal(action="BUY", confidence=confidence)

        # SELL: %R crosses below -20 (overbought)
        elif current_r < self.OVERBOUGHT and self.prev_r >= self.OVERBOUGHT:
            confidence = self._calculate_confidence(current_r, is_buy=False)
            signal = Signal(action="SELL", confidence=confidence)

        # Update state for next call
        self.prev_r = current_r
        return signal

    def _calculate_confidence(self, r: float, is_buy: bool) -> float:
        """
        Calculate confidence based on extremity.

        Args:
            r: Current Williams %R value
            is_buy: True for BUY signal, False for SELL

        Returns:
            Confidence between 0.75 and 0.90
        """
        if is_buy:
            # More oversold (more negative) = higher confidence
            bonus = (self.OVERSOLD - r) / abs(self.OVERSOLD) * 0.15
        else:
            # More overbought (less negative) = higher confidence
            bonus = (r - self.OVERBOUGHT) / abs(self.OVERBOUGHT) * 0.15

        return min(0.90, 0.75 + bonus)
