"""
Strategy Registry

Maps strategy names to class constructors for dynamic instantiation.
"""

from typing import Dict, Type, Optional, List
from app.strategies.base_strategy import BaseStrategy
from app.strategies import (
    RsiStrategy,
    MaCrossoverStrategy,
    MacdStrategy,
    BollingerBandsStrategy,
    StochasticStrategy,
    WilliamsRStrategy,
    AdxStrategy,
    DonchianChannelStrategy,
    RocStrategy,
    VwapStrategy,
)


# Registry mapping strategy names to classes
STRATEGY_REGISTRY: Dict[str, Type[BaseStrategy]] = {
    "RSI": RsiStrategy,
    "MACD": MacdStrategy,
    "MA_CROSSOVER": MaCrossoverStrategy,
    "BOLLINGER_BANDS": BollingerBandsStrategy,
    "STOCHASTIC": StochasticStrategy,
    "WILLIAMS_R": WilliamsRStrategy,
    "ADX": AdxStrategy,
    "DONCHIAN": DonchianChannelStrategy,
    "ROC": RocStrategy,
    "VWAP": VwapStrategy,
}


def get_strategy(name: str, parameters: Optional[Dict] = None) -> BaseStrategy:
    """
    Get strategy instance by name.

    Args:
        name: Strategy name (e.g., "RSI", "MACD")
        parameters: Optional parameters for strategy constructor

    Returns:
        Strategy instance

    Raises:
        ValueError: If strategy name is invalid

    Example:
        >>> strategy = get_strategy("RSI")
        >>> strategy_with_params = get_strategy("RSI", {"period": 14, "oversold": 30})
    """
    if name not in STRATEGY_REGISTRY:
        available = list(STRATEGY_REGISTRY.keys())
        raise ValueError(
            f"Unknown strategy: '{name}'. Available strategies: {', '.join(available)}"
        )

    strategy_class = STRATEGY_REGISTRY[name]

    # Instantiate with or without parameters
    if parameters:
        return strategy_class(**parameters)
    else:
        return strategy_class()


def list_strategies() -> List[str]:
    """
    Get list of all available strategy names.

    Returns:
        List of strategy names

    Example:
        >>> list_strategies()
        ['RSI', 'MACD', 'MA_CROSSOVER', ...]
    """
    return list(STRATEGY_REGISTRY.keys())


def is_valid_strategy(name: str) -> bool:
    """
    Check if strategy name is valid.

    Args:
        name: Strategy name to check

    Returns:
        True if valid, False otherwise

    Example:
        >>> is_valid_strategy("RSI")
        True
        >>> is_valid_strategy("INVALID")
        False
    """
    return name in STRATEGY_REGISTRY
