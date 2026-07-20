"""
ADX Trend Strength Strategy

Trades only in strong trends (ADX > 25).
Buys when +DI crosses above -DI.
Sells when -DI crosses above +DI.

Matches Phase 2: AdxStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_adx


class AdxStrategy(BaseStrategy):
    """
    ADX Trend Strength Strategy.

    Parameters:
        - ADX Period: 14
        - Min Trend Strength: 25

    Logic:
        - Filter: Only trade when ADX > 25 (strong trend)
        - BUY: +DI crosses above -DI (in strong trend)
        - SELL: -DI crosses above +DI (in strong trend)

    Confidence:
        - Based on ADX strength and DI separation
        - Range: 0.75 to 0.90
    """

    ADX_PERIOD = 14
    MIN_TREND_STRENGTH = 25.0

    def __init__(self):
        super().__init__(name="ADX")

    def reset_state(self):
        """Reset previous +DI and -DI values."""
        self.prev_plus_di = None
        self.prev_minus_di = None

    def get_required_candles(self) -> int:
        """
        Need ~2x ADX_PERIOD for calculation.

        ADX uses two rolling windows:
        - First 14-period rolling for +DI/-DI calculation
        - Second 14-period rolling on DX to get ADX
        Total: ~28 candles needed for non-NaN ADX
        """
        return (self.ADX_PERIOD * 2) + 1  # 29 candles

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on DI crossover in strong trends.

        Args:
            df: DataFrame with 'high', 'low', 'close' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate ADX and directional indicators
        df_with_adx = calculate_adx(df.copy(), period=self.ADX_PERIOD)

        adx = df_with_adx['adx'].iloc[-1]
        plus_di = df_with_adx['plus_di'].iloc[-1]
        minus_di = df_with_adx['minus_di'].iloc[-1]

        # Filter: Only trade in strong trends
        if adx <= self.MIN_TREND_STRENGTH:
            # Update state even if no signal (important for crossover detection)
            self.prev_plus_di = plus_di
            self.prev_minus_di = minus_di
            return None

        # First run: initialize state
        if self.prev_plus_di is None:
            self.prev_plus_di = plus_di
            self.prev_minus_di = minus_di
            return None

        signal = None

        # BUY: +DI crosses above -DI in strong trend
        if plus_di > minus_di and self.prev_plus_di <= self.prev_minus_di:
            confidence = self._calculate_confidence(adx, plus_di, minus_di)
            signal = Signal(action="BUY", confidence=confidence)

        # SELL: -DI crosses above +DI in strong trend
        elif minus_di > plus_di and self.prev_minus_di <= self.prev_plus_di:
            confidence = self._calculate_confidence(adx, plus_di, minus_di)
            signal = Signal(action="SELL", confidence=confidence)

        # Update state for next call
        self.prev_plus_di = plus_di
        self.prev_minus_di = minus_di
        return signal

    def _calculate_confidence(self, adx: float, plus_di: float, minus_di: float) -> float:
        """
        Calculate confidence based on trend strength and DI separation.

        Args:
            adx: Current ADX value
            plus_di: Current +DI value
            minus_di: Current -DI value

        Returns:
            Confidence between 0.75 and 0.90
        """
        # Stronger trend = higher confidence
        trend_bonus = min(0.10, (adx - 25) / 75 * 0.10)

        # Larger DI gap = higher confidence
        di_gap = abs(plus_di - minus_di)
        gap_bonus = min(0.05, di_gap / 100 * 0.05)

        return min(0.90, 0.75 + trend_bonus + gap_bonus)
