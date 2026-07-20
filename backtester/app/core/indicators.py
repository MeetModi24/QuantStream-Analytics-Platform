"""
Technical Indicators Module

Implements all technical indicators needed for the 10 trading strategies.
No external dependencies - pure Pandas/NumPy implementation.

Indicators implemented:
1. SMA (Simple Moving Average) - MA Crossover Strategy
2. EMA (Exponential Moving Average) - MACD Strategy
3. RSI (Relative Strength Index) - RSI Strategy
4. MACD (Moving Average Convergence Divergence) - MACD Strategy
5. Bollinger Bands - Bollinger Strategy
6. Stochastic Oscillator - Stochastic Strategy
7. Williams %R - Williams %R Strategy
8. ADX (Average Directional Index) - ADX Strategy
9. Donchian Channel - Donchian Strategy
10. ROC (Rate of Change) - ROC Strategy
11. VWAP (Volume Weighted Average Price) - VWAP Strategy

All functions:
- Take a DataFrame as input
- Add new columns to the DataFrame
- Return the modified DataFrame
- Handle NaN values appropriately (warmup period)
"""

import pandas as pd
import numpy as np


def calculate_sma(df: pd.DataFrame, period: int, column: str = 'close') -> pd.DataFrame:
    """
    Calculate Simple Moving Average.

    Formula:
        SMA = Sum of prices over N periods / N

    Args:
        df: DataFrame with OHLC data
        period: Number of periods for average
        column: Column to calculate SMA on (default: 'close')

    Returns:
        DataFrame with new column 'sma_{period}'

    Example:
        >>> df = calculate_sma(df, period=10)
        >>> print(df['sma_10'].tail())

    Note:
        First (period - 1) rows will be NaN (warmup period)
    """
    column_name = f'sma_{period}'
    df[column_name] = df[column].rolling(window=period).mean()
    return df


def calculate_ema(df: pd.DataFrame, period: int, column: str = 'close') -> pd.DataFrame:
    """
    Calculate Exponential Moving Average.

    Formula:
        EMA = Price(t) × k + EMA(t-1) × (1 - k)
        where k = 2 / (period + 1)

    Args:
        df: DataFrame with OHLC data
        period: Number of periods
        column: Column to calculate EMA on (default: 'close')

    Returns:
        DataFrame with new column 'ema_{period}'

    Example:
        >>> df = calculate_ema(df, period=12)
        >>> print(df['ema_12'].tail())
    """
    column_name = f'ema_{period}'
    df[column_name] = df[column].ewm(span=period, adjust=False).mean()
    return df


def calculate_rsi(df: pd.DataFrame, period: int = 14, column: str = 'close') -> pd.DataFrame:
    """
    Calculate Relative Strength Index.

    Formula:
        1. Calculate price changes (delta)
        2. Separate gains and losses
        3. Calculate average gain and average loss
        4. RS = Average Gain / Average Loss
        5. RSI = 100 - (100 / (1 + RS))

    Args:
        df: DataFrame with OHLC data
        period: Number of periods (default: 14)
        column: Column to calculate RSI on (default: 'close')

    Returns:
        DataFrame with new column 'rsi'

    Interpretation:
        RSI > 70: Overbought (potential SELL signal)
        RSI < 30: Oversold (potential BUY signal)

    Example:
        >>> df = calculate_rsi(df, period=14)
        >>> print(df['rsi'].tail())

    Production match:
        This matches RsiStrategy.java from Phase 2
    """
    # Calculate price changes
    delta = df[column].diff()

    # Separate gains and losses
    gain = delta.where(delta > 0, 0)
    loss = -delta.where(delta < 0, 0)

    # Calculate average gain and loss using rolling window
    avg_gain = gain.rolling(window=period).mean()
    avg_loss = loss.rolling(window=period).mean()

    # Calculate RS (Relative Strength)
    rs = avg_gain / avg_loss

    # Calculate RSI
    df['rsi'] = 100 - (100 / (1 + rs))

    return df


def calculate_macd(
    df: pd.DataFrame,
    fast: int = 12,
    slow: int = 26,
    signal: int = 9,
    column: str = 'close'
) -> pd.DataFrame:
    """
    Calculate MACD indicator.

    Formula:
        1. MACD Line = EMA(12) - EMA(26)
        2. Signal Line = EMA(9) of MACD Line
        3. MACD Histogram = MACD Line - Signal Line

    Args:
        df: DataFrame with OHLC data
        fast: Fast EMA period (default: 12)
        slow: Slow EMA period (default: 26)
        signal: Signal line period (default: 9)
        column: Column to calculate MACD on (default: 'close')

    Returns:
        DataFrame with new columns:
        - 'macd': MACD line
        - 'macd_signal': Signal line
        - 'macd_hist': Histogram (difference)

    Signals:
        MACD crosses above Signal → BUY
        MACD crosses below Signal → SELL

    Example:
        >>> df = calculate_macd(df)
        >>> # Check for crossover
        >>> if df['macd'].iloc[-1] > df['macd_signal'].iloc[-1]:
        >>>     print("BUY signal")
    """
    # Calculate EMAs
    ema_fast = df[column].ewm(span=fast, adjust=False).mean()
    ema_slow = df[column].ewm(span=slow, adjust=False).mean()

    # MACD line
    df['macd'] = ema_fast - ema_slow

    # Signal line
    df['macd_signal'] = df['macd'].ewm(span=signal, adjust=False).mean()

    # Histogram
    df['macd_hist'] = df['macd'] - df['macd_signal']

    return df


def calculate_bollinger_bands(
    df: pd.DataFrame,
    period: int = 20,
    std_dev: float = 2.0,
    column: str = 'close'
) -> pd.DataFrame:
    """
    Calculate Bollinger Bands.

    Formula:
        1. Middle Band = SMA(20)
        2. Upper Band = Middle Band + (2 × Standard Deviation)
        3. Lower Band = Middle Band - (2 × Standard Deviation)

    Args:
        df: DataFrame with OHLC data
        period: Number of periods (default: 20)
        std_dev: Number of standard deviations (default: 2.0)
        column: Column to calculate on (default: 'close')

    Returns:
        DataFrame with new columns:
        - 'bb_middle': Middle band (SMA)
        - 'bb_upper': Upper band
        - 'bb_lower': Lower band

    Signals:
        Price touches upper band → Overbought (SELL)
        Price touches lower band → Oversold (BUY)

    Example:
        >>> df = calculate_bollinger_bands(df, period=20, std_dev=2)
        >>> if df['close'].iloc[-1] < df['bb_lower'].iloc[-1]:
        >>>     print("Price below lower band - BUY signal")
    """
    # Middle band (SMA)
    df['bb_middle'] = df[column].rolling(window=period).mean()

    # Standard deviation
    rolling_std = df[column].rolling(window=period).std()

    # Upper and lower bands
    df['bb_upper'] = df['bb_middle'] + (rolling_std * std_dev)
    df['bb_lower'] = df['bb_middle'] - (rolling_std * std_dev)

    return df


def calculate_stochastic(
    df: pd.DataFrame,
    k_period: int = 14,
    d_period: int = 3
) -> pd.DataFrame:
    """
    Calculate Stochastic Oscillator.

    Formula:
        %K = 100 × (Close - Lowest Low) / (Highest High - Lowest Low)
        %D = SMA(%K, 3)

    Args:
        df: DataFrame with OHLC data (must have 'high', 'low', 'close')
        k_period: Period for %K (default: 14)
        d_period: Period for %D (default: 3)

    Returns:
        DataFrame with new columns:
        - 'stoch_k': %K line
        - 'stoch_d': %D line (signal)

    Signals:
        %K > 80: Overbought
        %K < 20: Oversold
        %K crosses above %D → BUY
        %K crosses below %D → SELL

    Example:
        >>> df = calculate_stochastic(df, k_period=14, d_period=3)
        >>> if df['stoch_k'].iloc[-1] < 20:
        >>>     print("Oversold - potential BUY")
    """
    # Lowest low and highest high over period
    low_min = df['low'].rolling(window=k_period).min()
    high_max = df['high'].rolling(window=k_period).max()

    # %K
    df['stoch_k'] = 100 * (df['close'] - low_min) / (high_max - low_min)

    # %D (signal line - SMA of %K)
    df['stoch_d'] = df['stoch_k'].rolling(window=d_period).mean()

    return df


def calculate_williams_r(df: pd.DataFrame, period: int = 14) -> pd.DataFrame:
    """
    Calculate Williams %R.

    Formula:
        %R = -100 × (Highest High - Close) / (Highest High - Lowest Low)

    Args:
        df: DataFrame with OHLC data
        period: Number of periods (default: 14)

    Returns:
        DataFrame with new column 'williams_r'

    Range: -100 to 0
        -80 to -100: Oversold (BUY signal)
        -20 to 0: Overbought (SELL signal)

    Example:
        >>> df = calculate_williams_r(df, period=14)
        >>> if df['williams_r'].iloc[-1] < -80:
        >>>     print("Oversold - BUY signal")
    """
    # Highest high and lowest low over period
    high_max = df['high'].rolling(window=period).max()
    low_min = df['low'].rolling(window=period).min()

    # Williams %R
    df['williams_r'] = -100 * (high_max - df['close']) / (high_max - low_min)

    return df


def calculate_adx(df: pd.DataFrame, period: int = 14) -> pd.DataFrame:
    """
    Calculate Average Directional Index (ADX).

    Formula (simplified):
        1. Calculate +DM and -DM (directional movement)
        2. Calculate TR (true range)
        3. Calculate +DI and -DI (directional indicators)
        4. Calculate DX (directional movement index)
        5. ADX = SMA of DX

    Args:
        df: DataFrame with OHLC data
        period: Number of periods (default: 14)

    Returns:
        DataFrame with new column 'adx'

    Interpretation:
        ADX > 25: Strong trend (trade in direction)
        ADX < 20: Weak trend (avoid trend strategies)

    Example:
        >>> df = calculate_adx(df, period=14)
        >>> if df['adx'].iloc[-1] > 25:
        >>>     print("Strong trend detected")
    """
    # Calculate directional movement
    high_diff = df['high'].diff()
    low_diff = -df['low'].diff()

    # +DM and -DM
    plus_dm = high_diff.where((high_diff > low_diff) & (high_diff > 0), 0)
    minus_dm = low_diff.where((low_diff > high_diff) & (low_diff > 0), 0)

    # True Range (TR)
    high_low = df['high'] - df['low']
    high_close = (df['high'] - df['close'].shift()).abs()
    low_close = (df['low'] - df['close'].shift()).abs()
    tr = pd.concat([high_low, high_close, low_close], axis=1).max(axis=1)

    # Smoothed values
    atr = tr.rolling(window=period).mean()
    plus_di = 100 * (plus_dm.rolling(window=period).mean() / atr)
    minus_di = 100 * (minus_dm.rolling(window=period).mean() / atr)

    # DX and ADX
    dx = 100 * (plus_di - minus_di).abs() / (plus_di + minus_di)
    df['adx'] = dx.rolling(window=period).mean()

    # Store +DI and -DI for ADX Strategy
    df['plus_di'] = plus_di
    df['minus_di'] = minus_di

    return df


def calculate_donchian_channel(df: pd.DataFrame, period: int = 20) -> pd.DataFrame:
    """
    Calculate Donchian Channel.

    Formula:
        Upper Channel = Highest High over N periods
        Lower Channel = Lowest Low over N periods

    Args:
        df: DataFrame with OHLC data
        period: Number of periods (default: 20)

    Returns:
        DataFrame with new columns:
        - 'dc_upper': Upper channel
        - 'dc_lower': Lower channel

    Signals:
        Price breaks above upper channel → BUY (breakout)
        Price breaks below lower channel → SELL (breakdown)

    Example:
        >>> df = calculate_donchian_channel(df, period=20)
        >>> if df['close'].iloc[-1] > df['dc_upper'].iloc[-2]:
        >>>     print("Breakout - BUY signal")
    """
    df['dc_upper'] = df['high'].rolling(window=period).max()
    df['dc_lower'] = df['low'].rolling(window=period).min()

    return df


def calculate_roc(df: pd.DataFrame, period: int = 12, column: str = 'close') -> pd.DataFrame:
    """
    Calculate Rate of Change.

    Formula:
        ROC = ((Price(t) - Price(t-n)) / Price(t-n)) × 100

    Args:
        df: DataFrame with OHLC data
        period: Number of periods to look back (default: 12)
        column: Column to calculate on (default: 'close')

    Returns:
        DataFrame with new column 'roc'

    Interpretation:
        ROC > 0: Upward momentum
        ROC < 0: Downward momentum
        ROC crosses above 0 → BUY
        ROC crosses below 0 → SELL

    Example:
        >>> df = calculate_roc(df, period=12)
        >>> if df['roc'].iloc[-1] > 0 and df['roc'].iloc[-2] <= 0:
        >>>     print("ROC crossed above zero - BUY signal")
    """
    df['roc'] = ((df[column] - df[column].shift(period)) / df[column].shift(period)) * 100

    return df


def calculate_vwap(df: pd.DataFrame, period: int = None) -> pd.DataFrame:
    """
    Calculate Volume Weighted Average Price.

    Formula:
        Typical Price = (High + Low + Close) / 3
        VWAP = Sum(Typical Price × Volume) / Sum(Volume) over period

    Args:
        df: DataFrame with OHLC data (must have 'high', 'low', 'close', 'volume')
        period: Rolling period (default: None for cumulative VWAP)

    Returns:
        DataFrame with new column 'vwap'

    Note:
        If period is None, calculates cumulative VWAP (traditional).
        If period is specified, calculates rolling VWAP over that period.

    Signals:
        Price > VWAP → Bullish
        Price < VWAP → Bearish

    Example:
        >>> df = calculate_vwap(df, period=50)
        >>> if df['close'].iloc[-1] < df['vwap'].iloc[-1]:
        >>>     print("Price below VWAP - potential BUY")
    """
    # Typical price
    typical_price = (df['high'] + df['low'] + df['close']) / 3

    if period is None:
        # Cumulative VWAP (traditional)
        df['vwap'] = (typical_price * df['volume']).cumsum() / df['volume'].cumsum()
    else:
        # Rolling VWAP (for backtesting with fixed period)
        df['vwap'] = (
            (typical_price * df['volume']).rolling(window=period).sum() /
            df['volume'].rolling(window=period).sum()
        )

    return df
