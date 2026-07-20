"""
Trading Strategies Module

Contains all 10 trading strategy implementations for backtesting.
"""

from app.strategies.base_strategy import BaseStrategy
from app.strategies.rsi_strategy import RsiStrategy
from app.strategies.ma_crossover_strategy import MaCrossoverStrategy
from app.strategies.macd_strategy import MacdStrategy
from app.strategies.bollinger_bands_strategy import BollingerBandsStrategy
from app.strategies.stochastic_strategy import StochasticStrategy
from app.strategies.williams_r_strategy import WilliamsRStrategy
from app.strategies.adx_strategy import AdxStrategy
from app.strategies.donchian_channel_strategy import DonchianChannelStrategy
from app.strategies.roc_strategy import RocStrategy
from app.strategies.vwap_strategy import VwapStrategy

__all__ = [
    "BaseStrategy",
    "RsiStrategy",
    "MaCrossoverStrategy",
    "MacdStrategy",
    "BollingerBandsStrategy",
    "StochasticStrategy",
    "WilliamsRStrategy",
    "AdxStrategy",
    "DonchianChannelStrategy",
    "RocStrategy",
    "VwapStrategy",
]
