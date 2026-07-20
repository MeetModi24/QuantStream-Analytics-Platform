"""
Donchian Channel Breakout Strategy

Buys when price breaks above upper channel.
Sells when price breaks below lower channel.

Matches Phase 2: DonchianChannelStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_donchian_channel


class DonchianChannelStrategy(BaseStrategy):
    """
    Donchian Channel Breakout Strategy.

    Parameters:
        - Channel Period: 20

    Logic:
        - BUY: Price breaks above upper channel
        - SELL: Price breaks below lower channel

    Confidence:
        - Based on breakout strength and channel width
        - Range: 0.75 to 0.90
    """

    CHANNEL_PERIOD = 20

    def __init__(self):
        super().__init__(name="Donchian Channel")

    def reset_state(self):
        """Reset channel breakout flags."""
        self.was_above_upper = False
        self.was_below_lower = False

    def get_required_candles(self) -> int:
        """Need 20 candles for Donchian Channel."""
        return self.CHANNEL_PERIOD

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on channel breakout.

        Args:
            df: DataFrame with 'high', 'low' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate Donchian Channel
        df_with_dc = calculate_donchian_channel(df.copy(), period=self.CHANNEL_PERIOD)

        upper = df_with_dc['dc_upper'].iloc[-1]
        lower = df_with_dc['dc_lower'].iloc[-1]
        current_price = df['close'].iloc[-1]
        signal = None

        # BUY: Price breaks above upper channel
        if current_price > upper and not self.was_above_upper:
            self.was_above_upper = True
            confidence = self._calculate_confidence(current_price, upper, lower, is_buy=True)
            signal = Signal(action="BUY", confidence=confidence)

        # Reset flag when price falls back
        if current_price <= upper:
            self.was_above_upper = False

        # SELL: Price breaks below lower channel
        if current_price < lower and not self.was_below_lower:
            self.was_below_lower = True
            confidence = self._calculate_confidence(current_price, upper, lower, is_buy=False)
            signal = Signal(action="SELL", confidence=confidence)

        # Reset flag when price rises back
        if current_price >= lower:
            self.was_below_lower = False

        return signal

    def _calculate_confidence(self, price: float, upper: float,
                            lower: float, is_buy: bool) -> float:
        """
        Calculate confidence based on breakout strength and channel width.

        Args:
            price: Current price
            upper: Upper channel value
            lower: Lower channel value
            is_buy: True for BUY signal, False for SELL

        Returns:
            Confidence between 0.75 and 0.90
        """
        channel_width = upper - lower
        if channel_width == 0:
            return 0.75

        if is_buy:
            # Distance above upper channel
            breakout_strength = (price - upper) / channel_width
        else:
            # Distance below lower channel
            breakout_strength = (lower - price) / channel_width

        bonus = min(0.15, breakout_strength * 0.5)
        return min(0.90, 0.75 + bonus)
