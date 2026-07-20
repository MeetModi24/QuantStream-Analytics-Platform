"""
Bollinger Bands Mean Reversion Strategy

Buys when price crosses into lower band zone.
Sells when price crosses into upper band zone.

Matches Phase 2: BollingerBandsStrategy.java
"""

from typing import Optional
import pandas as pd
from app.strategies.base_strategy import BaseStrategy
from app.models.signal import Signal
from app.core.indicators import calculate_bollinger_bands


class BollingerBandsStrategy(BaseStrategy):
    """
    Bollinger Bands Mean Reversion Strategy.

    Parameters:
        - Period: 20
        - Standard Deviation: 2.0

    Logic:
        - BUY: Price crosses into lower band zone (touches/penetrates)
        - SELL: Price crosses into upper band zone (touches/penetrates)

    Confidence:
        - Based on penetration depth into band
        - Range: 0.75 to 0.90
    """

    BB_PERIOD = 20
    STD_DEV = 2.0

    def __init__(self):
        super().__init__(name="Bollinger Bands")

    def reset_state(self):
        """Reset band penetration flags."""
        self.was_above_upper = False
        self.was_below_lower = False

    def get_required_candles(self) -> int:
        """Need 20 candles for Bollinger Bands."""
        return self.BB_PERIOD

    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate signal based on Bollinger Band penetration.

        Args:
            df: DataFrame with 'close' prices

        Returns:
            Signal (BUY/SELL) or None
        """
        # Calculate Bollinger Bands
        df_with_bb = calculate_bollinger_bands(
            df.copy(),
            period=self.BB_PERIOD,
            std_dev=self.STD_DEV
        )

        upper = df_with_bb['bb_upper'].iloc[-1]
        middle = df_with_bb['bb_middle'].iloc[-1]
        lower = df_with_bb['bb_lower'].iloc[-1]
        current_price = df['close'].iloc[-1]
        signal = None

        # BUY: Price crosses into lower band zone
        if current_price < lower and not self.was_below_lower:
            self.was_below_lower = True
            confidence = self._calculate_confidence(current_price, lower, middle)
            signal = Signal(action="BUY", confidence=confidence)

        # Reset flag when price exits lower zone
        if current_price >= lower:
            self.was_below_lower = False

        # SELL: Price crosses into upper band zone
        if current_price > upper and not self.was_above_upper:
            self.was_above_upper = True
            confidence = self._calculate_confidence(current_price, upper, middle)
            signal = Signal(action="SELL", confidence=confidence)

        # Reset flag when price exits upper zone
        if current_price <= upper:
            self.was_above_upper = False

        return signal

    def _calculate_confidence(self, price: float, band: float, middle: float) -> float:
        """
        Calculate confidence based on band penetration depth.

        Deeper penetration = higher confidence.

        Args:
            price: Current price
            band: Band level (upper or lower)
            middle: Middle band (SMA)

        Returns:
            Confidence between 0.75 and 0.90
        """
        band_width = abs(band - middle)
        if band_width == 0:
            return 0.75

        distance = abs(price - band)
        penetration = distance / band_width
        bonus = min(0.15, penetration * 0.3)
        return min(0.90, 0.75 + bonus)
