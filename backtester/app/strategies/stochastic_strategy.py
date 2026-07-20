"""
Stochastic Oscillator Strategy

Buys when %K crosses above %D in oversold zone.
Sells when %K crosses below %D in overbought zone.

Matches Phase 2: StochasticStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_stochastic


class StochasticStrategy(BaseStrategy):
    """
    Stochastic Oscillator Strategy.

    Parameters:
        - %K Period: 14
        - %D Period: 3 (SMA of %K)
        - Oversold: 20
        - Overbought: 80

    Logic:
        - BUY: %K crosses above %D AND %K < 30 (oversold zone)
        - SELL: %K crosses below %D AND %K > 70 (overbought zone)

    Confidence:
        - Based on extremity (how oversold/overbought)
        - Range: 0.75 to 0.90
    """

    STOCH_PERIOD = 14
    D_PERIOD = 3
    OVERSOLD = 20.0
    OVERBOUGHT = 80.0

    def __init__(self):
        super().__init__(name="Stochastic")

    def reset_state(self):
        """Reset previous %K and %D values."""
        self.prev_k = None
        self.prev_d = None

    def get_required_candles(self) -> int:
        """Need 14 candles for stochastic calculation."""
        return self.STOCH_PERIOD

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on Stochastic crossover in extreme zones.

        Args:
            df: DataFrame with 'high', 'low', 'close' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate Stochastic
        df_with_stoch = calculate_stochastic(
            df.copy(),
            k_period=self.STOCH_PERIOD,
            d_period=self.D_PERIOD
        )

        current_k = df_with_stoch['stoch_k'].iloc[-1]
        current_d = df_with_stoch['stoch_d'].iloc[-1]

        # First run: initialize state
        if self.prev_k is None:
            self.prev_k = current_k
            self.prev_d = current_d
            return None

        signal = None

        # BUY: %K crosses above %D in oversold zone
        # Use 30 threshold (OVERSOLD + 10 buffer) as in Phase 2
        if (current_k > current_d and self.prev_k <= self.prev_d and
            current_k < 30):
            confidence = self._calculate_confidence(current_k, is_buy=True)
            signal = Signal(action="BUY", confidence=confidence)

        # SELL: %K crosses below %D in overbought zone
        # Use 70 threshold (OVERBOUGHT - 10 buffer) as in Phase 2
        elif (current_k < current_d and self.prev_k >= self.prev_d and
              current_k > 70):
            confidence = self._calculate_confidence(current_k, is_buy=False)
            signal = Signal(action="SELL", confidence=confidence)

        # Update state for next call
        self.prev_k = current_k
        self.prev_d = current_d
        return signal

    def _calculate_confidence(self, k: float, is_buy: bool) -> float:
        """
        Calculate confidence based on extremity.

        More extreme %K = higher confidence.

        Args:
            k: Current %K value
            is_buy: True for BUY signal, False for SELL

        Returns:
            Confidence between 0.75 and 0.90
        """
        if is_buy:
            # More oversold (lower %K) = higher confidence
            bonus = (30 - k) / 30 * 0.15
        else:
            # More overbought (higher %K) = higher confidence
            bonus = (k - 70) / 30 * 0.15

        return min(0.90, 0.75 + bonus)
