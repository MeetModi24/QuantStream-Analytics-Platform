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
