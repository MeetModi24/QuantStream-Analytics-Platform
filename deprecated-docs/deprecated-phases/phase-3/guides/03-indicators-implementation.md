# Task 4: Technical Indicators Module

**Goal:** Implement calculation of technical indicators (RSI, MACD, Bollinger Bands, etc.) using pure Pandas/NumPy.

**Estimated Time:** 3 hours

---

## Overview

Technical indicators are mathematical calculations based on price and volume data that help identify trading opportunities.

**Why do we need this?**
- Our 10 trading strategies (from Phase 2) rely on these indicators
- Backtesting must replicate the EXACT indicator calculations used in production
- No external library (pandas-ta had installation issues)
- Implement ourselves using Pandas and NumPy (full control + better understanding)

---

## Understanding Technical Indicators

### What is an Indicator?

An **indicator** is a derived value calculated from price/volume data.

**Example: Simple Moving Average (SMA)**
```
Prices: [100, 102, 101, 103, 105]
SMA(3) for last 3 prices: (101 + 103 + 105) / 3 = 103
```

### Warmup Period

Most indicators need **historical data** before they can calculate a value.

**Example: MA(14) needs 14 prices**
```
Day 1:  price = 100  →  MA(14) = NaN (need 14 prices)
Day 2:  price = 102  →  MA(14) = NaN
...
Day 13: price = 110  →  MA(14) = NaN
Day 14: price = 112  →  MA(14) = 105.5 ✅ (first valid value)
Day 15: price = 114  →  MA(14) = 106.2
```

The first 13 rows will have `NaN` (Not a Number) - this is **expected** and **correct**.

---

## The 10 Strategies and Their Indicators

From Phase 2 Strategy Engine:

| Strategy | Indicators Needed |
|----------|-------------------|
| 1. MA Crossover | SMA(10), SMA(50) |
| 2. RSI Mean Reversion | RSI(14) |
| 3. MACD Momentum | MACD(12, 26, 9) |
| 4. Bollinger Bands | Bollinger(20, 2) |
| 5. Stochastic Oscillator | Stochastic(%K=14, %D=3) |
| 6. Williams %R | Williams %R(14) |
| 7. ADX Trend Strength | ADX(14) |
| 8. Donchian Channel | Donchian(20) |
| 9. ROC (Rate of Change) | ROC(12) |
| 10. VWAP Deviation | VWAP |

---

## Step 1: Create Indicators Module

Create `app/core/indicators.py`:

```bash
touch app/core/indicators.py
```

---

## Step 2: Implement Basic Indicators

### 2.1: Simple Moving Average (SMA)

**Formula:** Average of last N prices

```python
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
```

### 2.2: Exponential Moving Average (EMA)

**Formula:** Weighted average giving more weight to recent prices

```python
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
```

---

## Step 3: Implement Strategy-Specific Indicators

### 3.1: RSI (Relative Strength Index)

**Used by:** RSI Strategy  
**Formula:** Measures momentum (overbought/oversold conditions)

```python
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
```

### 3.2: MACD (Moving Average Convergence Divergence)

**Used by:** MACD Strategy  
**Formula:** Trend-following momentum indicator

```python
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
```

### 3.3: Bollinger Bands

**Used by:** Bollinger Bands Strategy  
**Formula:** Volatility bands around moving average

```python
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
```

### 3.4: Stochastic Oscillator

**Used by:** Stochastic Strategy  
**Formula:** Compares closing price to price range

```python
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
```

### 3.5: Williams %R

**Used by:** Williams %R Strategy  
**Formula:** Similar to Stochastic but inverted

```python
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
```

### 3.6: ADX (Average Directional Index)

**Used by:** ADX Strategy  
**Formula:** Measures trend strength (complex calculation)

```python
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
    
    return df
```

### 3.7: Donchian Channel

**Used by:** Donchian Channel Strategy  
**Formula:** Highest high and lowest low over period

```python
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
```

### 3.8: ROC (Rate of Change)

**Used by:** ROC Strategy  
**Formula:** Percentage change over period

```python
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
```

### 3.9: VWAP (Volume Weighted Average Price)

**Used by:** VWAP Strategy  
**Formula:** Average price weighted by volume

```python
def calculate_vwap(df: pd.DataFrame) -> pd.DataFrame:
    """
    Calculate Volume Weighted Average Price.
    
    Formula:
        Typical Price = (High + Low + Close) / 3
        VWAP = Cumulative(Typical Price × Volume) / Cumulative(Volume)
    
    Args:
        df: DataFrame with OHLC data (must have 'high', 'low', 'close', 'volume')
        
    Returns:
        DataFrame with new column 'vwap'
        
    Note:
        VWAP is typically calculated intraday and resets each day.
        This implementation calculates cumulative VWAP.
        
    Signals:
        Price > VWAP → Bullish
        Price < VWAP → Bearish
        
    Example:
        >>> df = calculate_vwap(df)
        >>> if df['close'].iloc[-1] < df['vwap'].iloc[-1]:
        >>>     print("Price below VWAP - potential BUY")
    """
    # Typical price
    typical_price = (df['high'] + df['low'] + df['close']) / 3
    
    # VWAP
    df['vwap'] = (typical_price * df['volume']).cumsum() / df['volume'].cumsum()
    
    return df
```

---

## Step 3: Complete indicators.py File

Put it all together in `app/core/indicators.py`:

```python
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


# [Paste all 11 functions here from above]
# calculate_sma()
# calculate_ema()
# calculate_rsi()
# calculate_macd()
# calculate_bollinger_bands()
# calculate_stochastic()
# calculate_williams_r()
# calculate_adx()
# calculate_donchian_channel()
# calculate_roc()
# calculate_vwap()
```

---

## Step 4: Test the Indicators

Create `test_indicators.py`:

```python
import sys
sys.path.append('.')

import pandas as pd
from app.core.indicators import *

# Create sample OHLC data
data = {
    'open': [100, 102, 101, 103, 105, 104, 106, 108, 107, 109, 111, 110, 112, 114, 113],
    'high': [101, 103, 102, 104, 106, 105, 107, 109, 108, 110, 112, 111, 113, 115, 114],
    'low': [99, 101, 100, 102, 104, 103, 105, 107, 106, 108, 110, 109, 111, 113, 112],
    'close': [100.5, 102.5, 101.5, 103.5, 105.5, 104.5, 106.5, 108.5, 107.5, 109.5, 111.5, 110.5, 112.5, 114.5, 113.5],
    'volume': [1000, 1100, 900, 1200, 1300, 1000, 1400, 1500, 1100, 1600, 1700, 1200, 1800, 1900, 1300]
}
df = pd.DataFrame(data)

print("=" * 60)
print("SAMPLE DATA")
print("=" * 60)
print(df)

print("\n" + "=" * 60)
print("TEST 1: SMA")
print("=" * 60)
df = calculate_sma(df, period=5)
print(df[['close', 'sma_5']].tail(10))

print("\n" + "=" * 60)
print("TEST 2: EMA")
print("=" * 60)
df = calculate_ema(df, period=5)
print(df[['close', 'ema_5']].tail(10))

print("\n" + "=" * 60)
print("TEST 3: RSI")
print("=" * 60)
df = calculate_rsi(df, period=5)
print(df[['close', 'rsi']].tail(10))
print(f"RSI range check: {df['rsi'].min():.2f} to {df['rsi'].max():.2f}")
assert df['rsi'].min() >= 0 and df['rsi'].max() <= 100, "RSI must be between 0 and 100"

print("\n" + "=" * 60)
print("TEST 4: MACD")
print("=" * 60)
df = calculate_macd(df, fast=5, slow=10, signal=3)
print(df[['close', 'macd', 'macd_signal', 'macd_hist']].tail(10))

print("\n" + "=" * 60)
print("TEST 5: Bollinger Bands")
print("=" * 60)
df = calculate_bollinger_bands(df, period=5, std_dev=2)
print(df[['close', 'bb_lower', 'bb_middle', 'bb_upper']].tail(10))
# Verify upper > middle > lower
last_row = df.iloc[-1]
assert last_row['bb_upper'] > last_row['bb_middle'] > last_row['bb_lower'], "Bollinger Bands ordering failed"

print("\n" + "=" * 60)
print("TEST 6: Stochastic")
print("=" * 60)
df = calculate_stochastic(df, k_period=5, d_period=3)
print(df[['close', 'stoch_k', 'stoch_d']].tail(10))

print("\n" + "=" * 60)
print("TEST 7: Williams %R")
print("=" * 60)
df = calculate_williams_r(df, period=5)
print(df[['close', 'williams_r']].tail(10))

print("\n" + "=" * 60)
print("TEST 8: ADX")
print("=" * 60)
df = calculate_adx(df, period=5)
print(df[['close', 'adx']].tail(10))

print("\n" + "=" * 60)
print("TEST 9: Donchian Channel")
print("=" * 60)
df = calculate_donchian_channel(df, period=5)
print(df[['close', 'dc_lower', 'dc_upper']].tail(10))

print("\n" + "=" * 60)
print("TEST 10: ROC")
print("=" * 60)
df = calculate_roc(df, period=5)
print(df[['close', 'roc']].tail(10))

print("\n" + "=" * 60)
print("TEST 11: VWAP")
print("=" * 60)
df = calculate_vwap(df)
print(df[['close', 'vwap']].tail(10))

print("\n✅ All indicator tests complete!")
print(f"\nDataFrame now has {len(df.columns)} columns:")
print(df.columns.tolist())
```

---

## Success Criteria Checklist

- [ ] `app/core/indicators.py` created with all 11 functions
- [ ] RSI values between 0 and 100
- [ ] MACD components (line, signal, histogram) calculated
- [ ] Bollinger Bands: upper > middle > lower
- [ ] Stochastic values between 0 and 100
- [ ] Williams %R values between -100 and 0
- [ ] ADX calculates without errors
- [ ] Donchian Channel upper/lower calculated
- [ ] ROC calculates percentage change
- [ ] VWAP uses volume weighting
- [ ] All indicators handle warmup period (NaN for first N rows)
- [ ] Test script runs successfully
- [ ] All 10 strategies have their required indicators

---

## Expected Output

When you run the test, you should see:
- Sample data (15 rows)
- Each indicator calculated with last 10 rows shown
- NaN values in first few rows (warmup period)
- Valid indicator values in last rows
- All assertions pass

---

## Next Steps

Once Task 4 is complete:

**Task 5: Portfolio Simulation**
- Implement `Portfolio` class
- Track cash, shares, portfolio value
- Execute buy/sell trades
- Calculate profit/loss

See: `docs/phase-3/guides/04-portfolio-simulation.md`

---

**Time:** ~3 hours  
**Files Created:** 2 (indicators.py, test_indicators.py)
