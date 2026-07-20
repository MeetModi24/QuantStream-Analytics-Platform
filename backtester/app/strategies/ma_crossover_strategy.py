"""
Moving Average Crossover Strategy

Golden Cross: MA10 crosses above MA50 (BUY)
Death Cross: MA10 crosses below MA50 (SELL)

Matches Phase 2: MaCrossoverStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_sma


class MaCrossoverStrategy(BaseStrategy):
    """
    Moving Average Crossover Strategy.

    Parameters:
        - MA10 Period: 10
        - MA50 Period: 50

    Logic:
        - BUY: Golden Cross (MA10 crosses above MA50)
        - SELL: Death Cross (MA10 crosses below MA50)

    Confidence:
        - Based on gap size between MAs
        - Bonus if price confirms direction
        - Range: 0.70 to 0.90
    """

    MA10_PERIOD = 10
    MA50_PERIOD = 50

    def __init__(self):
        super().__init__(name="MA Crossover")

    def reset_state(self):
        """Reset previous MA values."""
        self.prev_ma10 = None
        self.prev_ma50 = None

    def get_required_candles(self) -> int:
        """Need 50 candles for MA50 calculation."""
        return self.MA50_PERIOD

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on MA crossover.

        Args:
            df: DataFrame with 'close' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate current MAs
        df_with_sma = calculate_sma(df.copy(), period=self.MA10_PERIOD)
        df_with_sma = calculate_sma(df_with_sma, period=self.MA50_PERIOD)
        current_ma10 = df_with_sma[f'sma_{self.MA10_PERIOD}'].iloc[-1]
        current_ma50 = df_with_sma[f'sma_{self.MA50_PERIOD}'].iloc[-1]
        current_price = df['close'].iloc[-1]

        # First run: initialize state
        if self.prev_ma10 is None:
            self.prev_ma10 = current_ma10
            self.prev_ma50 = current_ma50
            return None

        signal = None

        # Golden Cross: MA10 crosses above MA50
        if current_ma10 > current_ma50 and self.prev_ma10 <= self.prev_ma50:
            confidence = self._calculate_confidence(
                current_ma10, current_ma50, current_price, is_buy=True
            )
            signal = Signal(action="BUY", confidence=confidence)

        # Death Cross: MA10 crosses below MA50
        elif current_ma10 < current_ma50 and self.prev_ma10 >= self.prev_ma50:
            confidence = self._calculate_confidence(
                current_ma10, current_ma50, current_price, is_buy=False
            )
            signal = Signal(action="SELL", confidence=confidence)

        # Update state for next call
        self.prev_ma10 = current_ma10
        self.prev_ma50 = current_ma50
        return signal

    def _calculate_confidence(self, ma10: float, ma50: float,
                            price: float, is_buy: bool) -> float:
        """
        Calculate confidence based on gap size and price alignment.

        Args:
            ma10: Current 10-period MA
            ma50: Current 50-period MA
            price: Current price
            is_buy: True for BUY signal, False for SELL

        Returns:
            Confidence between 0.70 and 0.90
        """
        # Gap size between MAs (relative to MA50)
        gap = abs(ma10 - ma50) / ma50
        gap_bonus = gap * 10

        # Price alignment bonus
        if is_buy:
            # For BUY: prefer price above MA10 (confirms uptrend)
            price_bonus = 0.05 if price > ma10 else 0.0
        else:
            # For SELL: prefer price below MA10 (confirms downtrend)
            price_bonus = 0.05 if price < ma10 else 0.0

        return min(0.90, 0.70 + gap_bonus + price_bonus)
