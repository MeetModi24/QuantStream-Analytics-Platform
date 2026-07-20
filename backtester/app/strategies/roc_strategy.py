"""
Rate of Change (ROC) Momentum Strategy

Buys when ROC crosses above 0 with strong momentum.
Sells when ROC crosses below 0 with strong momentum.

Matches Phase 2: RocStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_roc


class RocStrategy(BaseStrategy):
    """
    Rate of Change (ROC) Momentum Strategy.

    Parameters:
        - ROC Period: 10
        - Min Threshold: 2.0% (filters weak signals)

    Logic:
        - BUY: ROC crosses above 0 AND ROC > 2.0
        - SELL: ROC crosses below 0 AND ROC < -2.0

    Confidence:
        - Based on ROC magnitude (stronger momentum = higher confidence)
        - Range: 0.75 to 0.90
    """

    ROC_PERIOD = 10
    MIN_THRESHOLD = 2.0

    def __init__(self):
        super().__init__(name="ROC")

    def reset_state(self):
        """Reset previous ROC value."""
        self.prev_roc = None

    def get_required_candles(self) -> int:
        """Need ROC_PERIOD + 1 for calculation."""
        return self.ROC_PERIOD + 1

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on ROC zero-line crossover with strength filter.

        Args:
            df: DataFrame with 'close' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate ROC
        df_with_roc = calculate_roc(df.copy(), period=self.ROC_PERIOD)
        current_roc = df_with_roc['roc'].iloc[-1]

        # First run: initialize state
        if self.prev_roc is None:
            self.prev_roc = current_roc
            return None

        signal = None

        # BUY: ROC crosses above 0 with strong momentum
        if (current_roc > 0 and self.prev_roc <= 0 and
            current_roc > self.MIN_THRESHOLD):
            confidence = self._calculate_confidence(current_roc)
            signal = Signal(action="BUY", confidence=confidence)

        # SELL: ROC crosses below 0 with strong momentum
        elif (current_roc < 0 and self.prev_roc >= 0 and
              current_roc < -self.MIN_THRESHOLD):
            confidence = self._calculate_confidence(current_roc)
            signal = Signal(action="SELL", confidence=confidence)

        # Update state for next call
        self.prev_roc = current_roc
        return signal

    def _calculate_confidence(self, roc: float) -> float:
        """
        Calculate confidence based on ROC magnitude.

        Stronger momentum (larger |ROC|) = higher confidence.

        Args:
            roc: Current ROC value

        Returns:
            Confidence between 0.75 and 0.90
        """
        magnitude = abs(roc)
        bonus = min(0.15, (magnitude - self.MIN_THRESHOLD) / 10 * 0.15)
        return min(0.90, 0.75 + bonus)
