import sys
sys.path.append('.')

from app.core.data_fetcher import QuestDBFetcher

# Initialize fetcher
fetcher = QuestDBFetcher()

print("=" * 60)
print("TEST 1: Fetch Ticks")
print("=" * 60)

# Fetch 1 day of AAPL ticks
ticks_df = fetcher.fetch_ticks('AAPL', '2026-07-19', '2026-07-20')

print(f"\nDataFrame shape: {ticks_df.shape}")
print(f"Columns: {ticks_df.columns.tolist()}")
print(f"\nFirst 5 rows:")
print(ticks_df.head())
print(f"\nLast 5 rows:")
print(ticks_df.tail())

print("\n" + "=" * 60)
print("TEST 2: Resample to 1-Minute OHLC")
print("=" * 60)

# Resample to 1-minute candles
candles_1m = fetcher.resample_to_ohlc(ticks_df, '1T')

print(f"\nDataFrame shape: {candles_1m.shape}")
print(f"Columns: {candles_1m.columns.tolist()}")
print(f"\nFirst 5 candles:")
print(candles_1m.head())

print("\n" + "=" * 60)
print("TEST 3: Resample to 1-Hour OHLC")
print("=" * 60)

# Resample to 1-hour candles
candles_1h = fetcher.resample_to_ohlc(ticks_df, '1H')

print(f"\nDataFrame shape: {candles_1h.shape}")
print(f"Columns: {candles_1h.columns.tolist()}")
print(f"\nFirst 5 candles:")
print(candles_1h.head())

print("\n" + "=" * 60)
print("TEST 4: Fetch Candles (Convenience Method)")
print("=" * 60)

# Fetch and resample in one call
candles = fetcher.fetch_candles('BTC', '2026-07-19', '2026-07-20', '15T')

print(f"\nDataFrame shape: {candles.shape}")
print(f"Columns: {candles.columns.tolist()}")
print(f"\nFirst 5 candles:")
print(candles.head())

print("\n" + "=" * 60)
print("TEST 5: Empty Result Handling")
print("=" * 60)

# Fetch data for non-existent symbol
empty_df = fetcher.fetch_ticks('NONEXISTENT', '2026-07-19', '2026-07-20')

print(f"\nDataFrame shape: {empty_df.shape}")
print(f"Is empty: {empty_df.empty}")

print("\n✅ All tests complete!")
