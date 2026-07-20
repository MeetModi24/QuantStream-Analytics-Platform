# Task 2: QuestDB Data Fetcher

**Goal:** Implement module to fetch historical price data from QuestDB.

**Estimated Time:** 2 hours

---

## Overview

The Data Fetcher is the **foundation** of the backtesting engine. It:
1. Connects to QuestDB (time-series database)
2. Fetches historical tick data (raw prices)
3. Converts data to Pandas DataFrame
4. Resamples ticks to OHLC candles (Open, High, Low, Close)

**Why do we need this?**
- Backtesting requires historical data to replay strategies
- QuestDB stores all production tick data from Phase 1 & 2
- Pandas DataFrame is the standard format for time-series analysis in Python

---

## Understanding the Data

### QuestDB Tables (from Architecture)

**1. `ticks` table (raw price data)**
```sql
CREATE TABLE ticks (
    symbol SYMBOL,
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

**Sample data:**
```
symbol | price    | volume      | timestamp
-------|----------|-------------|---------------------------
AAPL   | 180.50   | 50,561,269  | 2026-07-19T10:15:23.123Z
AAPL   | 180.52   | 50,562,180  | 2026-07-19T10:15:24.456Z
BTC    | 50123.00 | 12,800,000  | 2026-07-19T10:15:25.789Z
```

**Data volume:**
- 10 symbols × 1 tick/second = 10 ticks/sec
- 86,400 ticks/day × 10 symbols = 864,000 rows/day
- 30 days = ~26 million rows

**2. `candles_1m` table (OHLC for visualization)**
```sql
CREATE TABLE candles_1m (
    symbol SYMBOL,
    open DOUBLE,
    high DOUBLE,
    low DOUBLE,
    close DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

**Sample data:**
```
symbol | open   | high   | low    | close  | volume      | timestamp
-------|--------|--------|--------|--------|-------------|-------------------
AAPL   | 180.50 | 180.75 | 180.45 | 180.70 | 3,033,762   | 2026-07-19T10:15:00Z
BTC    | 50100  | 50200  | 50050  | 50180  | 768,000,000 | 2026-07-19T10:15:00Z
```

**Critical: Which table to use for backtesting?**

From Phase 2 architecture:
> **Signals are generated from TICKS (not candles)**
> - Candles are ONLY for frontend visualization
> - Strategies read TICKS for more accurate indicators
> - Higher resolution (60 ticks/min vs 1 candle/min)

**Therefore:**
- ✅ Fetch TICKS for backtesting
- ✅ Resample ticks to OHLC inside backtester (for indicator calculations)
- ❌ Do NOT use pre-aggregated candles table (less accurate)

---

## Step 1: Create Data Fetcher Class

Create `app/core/data_fetcher.py`:

```bash
touch app/core/data_fetcher.py
```

**Add the implementation:**

```python
import psycopg2
import pandas as pd
from typing import Optional
from datetime import datetime
from app.config import get_settings

settings = get_settings()


class QuestDBFetcher:
    """
    Fetches historical price data from QuestDB for backtesting.
    
    Responsibilities:
    1. Connect to QuestDB using connection pooling
    2. Fetch tick data for a given symbol and date range
    3. Convert to Pandas DataFrame with DatetimeIndex
    4. Resample ticks to OHLC candles (if needed)
    """
    
    def __init__(self):
        """Initialize QuestDB connection parameters."""
        self.host = settings.questdb_host
        self.port = settings.questdb_port
        self.user = settings.questdb_user
        self.password = settings.questdb_password
        self.database = settings.questdb_database
        
    def _get_connection(self):
        """
        Create a new database connection.
        
        Uses psycopg2 (PostgreSQL wire protocol).
        QuestDB supports PostgreSQL wire protocol on port 8812.
        
        Returns:
            psycopg2.connection: Database connection
            
        Raises:
            psycopg2.OperationalError: If connection fails
        """
        return psycopg2.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            database=self.database
        )
    
    def fetch_ticks(
        self,
        symbol: str,
        start_date: str,
        end_date: str
    ) -> pd.DataFrame:
        """
        Fetch raw tick data from QuestDB.
        
        Args:
            symbol: Trading symbol (e.g., 'AAPL', 'BTC')
            start_date: Start date in ISO format (e.g., '2026-06-19')
            end_date: End date in ISO format (e.g., '2026-07-19')
            
        Returns:
            pd.DataFrame: DataFrame with columns [price, volume] and DatetimeIndex
            
        Example:
            >>> fetcher = QuestDBFetcher()
            >>> df = fetcher.fetch_ticks('AAPL', '2026-07-01', '2026-07-02')
            >>> print(df.head())
            
                                     price      volume
            timestamp                                  
            2026-07-01 00:00:00  180.50  50561269
            2026-07-01 00:00:01  180.52  50562180
            2026-07-01 00:00:02  180.48  50560145
        """
        conn = None
        cursor = None
        
        try:
            # Connect to QuestDB
            conn = self._get_connection()
            cursor = conn.cursor()
            
            # SQL query to fetch ticks
            # Note: QuestDB timestamp format requires explicit casting
            query = """
                SELECT timestamp, price, volume
                FROM ticks
                WHERE symbol = %s
                  AND timestamp >= %s::timestamp
                  AND timestamp < %s::timestamp
                ORDER BY timestamp ASC
            """
            
            # Execute query
            cursor.execute(query, (symbol, start_date, end_date))
            
            # Fetch all rows
            rows = cursor.fetchall()
            
            # Handle empty result
            if not rows:
                print(f"⚠️  No data found for {symbol} between {start_date} and {end_date}")
                # Return empty DataFrame with correct structure
                return pd.DataFrame(columns=['price', 'volume'], index=pd.DatetimeIndex([], name='timestamp'))
            
            # Convert to DataFrame
            df = pd.DataFrame(rows, columns=['timestamp', 'price', 'volume'])
            
            # Convert timestamp to datetime
            df['timestamp'] = pd.to_datetime(df['timestamp'])
            
            # Set timestamp as index
            df.set_index('timestamp', inplace=True)
            
            print(f"✅ Fetched {len(df)} ticks for {symbol} ({start_date} to {end_date})")
            
            return df
            
        except psycopg2.OperationalError as e:
            print(f"❌ Database connection error: {e}")
            print("Troubleshooting:")
            print("1. Ensure QuestDB is running: docker ps | grep questdb")
            print("2. Check QuestDB logs: docker logs questdb")
            print("3. Verify port 8812 is accessible: nc -zv localhost 8812")
            raise
            
        except Exception as e:
            print(f"❌ Error fetching ticks: {e}")
            raise
            
        finally:
            # Close cursor and connection
            if cursor:
                cursor.close()
            if conn:
                conn.close()
    
    def resample_to_ohlc(
        self,
        df: pd.DataFrame,
        frequency: str = '1T'
    ) -> pd.DataFrame:
        """
        Resample tick data to OHLC (Open, High, Low, Close) candles.
        
        Args:
            df: DataFrame with tick data (must have 'price' and 'volume' columns)
            frequency: Resampling frequency (Pandas offset string)
                      - '1T' or '1min' = 1 minute
                      - '5T' or '5min' = 5 minutes
                      - '15T' or '15min' = 15 minutes
                      - '1H' = 1 hour
                      - '1D' = 1 day
                      
        Returns:
            pd.DataFrame: OHLC DataFrame with columns [open, high, low, close, volume]
            
        Example:
            >>> # 86,400 tick rows (1 tick/sec for 1 day)
            >>> ticks_df = fetcher.fetch_ticks('AAPL', '2026-07-01', '2026-07-02')
            >>> 
            >>> # Resample to 1-minute candles (1,440 rows)
            >>> candles_df = fetcher.resample_to_ohlc(ticks_df, '1T')
            >>> 
            >>> print(candles_df.head())
            
                                open    high     low   close    volume
            timestamp                                                  
            2026-07-01 00:00:00  180.50  180.55  180.45  180.52  3033762
            2026-07-01 00:01:00  180.52  180.58  180.50  180.55  3045123
            2026-07-01 00:02:00  180.55  180.60  180.53  180.58  3021456
        """
        if df.empty:
            # Return empty OHLC DataFrame
            return pd.DataFrame(columns=['open', 'high', 'low', 'close', 'volume'], 
                              index=pd.DatetimeIndex([], name='timestamp'))
        
        # Ensure index is DatetimeIndex
        if not isinstance(df.index, pd.DatetimeIndex):
            raise ValueError("DataFrame index must be DatetimeIndex (set timestamp as index)")
        
        # Resample and aggregate
        ohlc = df['price'].resample(frequency).agg(['first', 'max', 'min', 'last'])
        volume = df['volume'].resample(frequency).sum()
        
        # Rename columns to OHLC format
        ohlc.columns = ['open', 'high', 'low', 'close']
        
        # Combine OHLC and volume
        result = pd.concat([ohlc, volume], axis=1)
        
        # Drop rows where no data exists (all NaN)
        result.dropna(inplace=True)
        
        print(f"✅ Resampled {len(df)} ticks to {len(result)} candles (frequency: {frequency})")
        
        return result
    
    def fetch_candles(
        self,
        symbol: str,
        start_date: str,
        end_date: str,
        frequency: str = '1T'
    ) -> pd.DataFrame:
        """
        Convenience method: Fetch ticks and resample to OHLC in one call.
        
        Args:
            symbol: Trading symbol
            start_date: Start date (ISO format)
            end_date: End date (ISO format)
            frequency: Resampling frequency (default: '1T' = 1 minute)
            
        Returns:
            pd.DataFrame: OHLC DataFrame
            
        Example:
            >>> fetcher = QuestDBFetcher()
            >>> candles = fetcher.fetch_candles('AAPL', '2026-07-01', '2026-07-02', '1H')
            >>> # Returns 24 hourly candles
        """
        # Fetch ticks
        ticks_df = self.fetch_ticks(symbol, start_date, end_date)
        
        # Resample to OHLC
        candles_df = self.resample_to_ohlc(ticks_df, frequency)
        
        return candles_df
```

---

## Step 2: Understanding the Code

### Key Concepts Explained

**1. Connection Management**

```python
def _get_connection(self):
    return psycopg2.connect(host=..., port=8812, ...)
```

- QuestDB supports PostgreSQL wire protocol
- Port 8812 is for PostgreSQL connections (port 9000 is for HTTP)
- Each method creates a new connection (closed in `finally` block)
- Production optimization: Use connection pooling (future Task)

**2. SQL Query**

```python
query = """
    SELECT timestamp, price, volume
    FROM ticks
    WHERE symbol = %s
      AND timestamp >= %s::timestamp
      AND timestamp < %s::timestamp
    ORDER BY timestamp ASC
"""
```

- `%s` = parameterized query (prevents SQL injection)
- `::timestamp` = QuestDB type casting
- `ORDER BY timestamp ASC` = chronological order (required for backtesting)

**3. Pandas DataFrame Creation**

```python
df = pd.DataFrame(rows, columns=['timestamp', 'price', 'volume'])
df['timestamp'] = pd.to_datetime(df['timestamp'])
df.set_index('timestamp', inplace=True)
```

- Creates DataFrame from SQL rows
- Converts timestamp strings to Python datetime objects
- Sets timestamp as index (required for resampling)

**4. Resampling to OHLC**

```python
ohlc = df['price'].resample('1T').agg(['first', 'max', 'min', 'last'])
```

- `resample('1T')` = group by 1-minute intervals
- `first` = Open (first tick in interval)
- `max` = High (highest price in interval)
- `min` = Low (lowest price in interval)
- `last` = Close (last tick in interval)

**Visual Example:**
```
Ticks (10 rows, 10 seconds):
timestamp           price
2026-07-01 10:00:00  180.50  ← Open (first)
2026-07-01 10:00:01  180.55
2026-07-01 10:00:02  180.60  ← High (max)
2026-07-01 10:00:03  180.58
2026-07-01 10:00:04  180.52
2026-07-01 10:00:05  180.48  ← Low (min)
2026-07-01 10:00:06  180.50
2026-07-01 10:00:07  180.53
2026-07-01 10:00:08  180.55
2026-07-01 10:00:09  180.57  ← Close (last)

↓ Resample to 1-minute OHLC

Candle (1 row):
timestamp           open   high   low    close
2026-07-01 10:00:00  180.50 180.60 180.48 180.57
```

---

## Step 3: Test the Data Fetcher

Create test script:

```bash
touch test_data_fetcher.py
```

**Add test code:**

```python
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
```

---

## Step 4: Run the Tests

**Prerequisites:**
1. QuestDB must be running
2. Ticks table must have data (from Phase 2)

**Start QuestDB (if not running):**

```bash
# Check if QuestDB is running
docker ps | grep questdb

# If not running, start it
docker start questdb

# Wait for it to be ready (check logs)
docker logs -f questdb
# Look for: "server-main enjoy" (indicates ready)
```

**Run the test:**

```bash
cd /Users/mhiteshkumar/QuantStream/backtester
source venv/bin/activate
python test_data_fetcher.py
```

**Expected output:**

```
============================================================
TEST 1: Fetch Ticks
============================================================
✅ Fetched 86400 ticks for AAPL (2026-07-19 to 2026-07-20)

DataFrame shape: (86400, 2)
Columns: ['price', 'volume']

First 5 rows:
                           price     volume
timestamp                                  
2026-07-19 00:00:00  180.50  50561269
2026-07-19 00:00:01  180.52  50562180
2026-07-19 00:00:02  180.48  50560145
2026-07-19 00:00:03  180.51  50561890
2026-07-19 00:00:04  180.53  50562456

Last 5 rows:
                           price     volume
timestamp                                  
2026-07-19 23:59:55  182.10  51023456
2026-07-19 23:59:56  182.12  51024567
2026-07-19 23:59:57  182.11  51023789
2026-07-19 23:59:58  182.13  51024890
2026-07-19 23:59:59  182.15  51025123

============================================================
TEST 2: Resample to 1-Minute OHLC
============================================================
✅ Resampled 86400 ticks to 1440 candles (frequency: 1T)

DataFrame shape: (1440, 5)
Columns: ['open', 'high', 'low', 'close', 'volume']

First 5 candles:
                          open    high     low   close      volume
timestamp                                                          
2026-07-19 00:00:00  180.50  180.58  180.45  180.55  3033762123
2026-07-19 00:01:00  180.55  180.62  180.52  180.60  3045123456
2026-07-19 00:02:00  180.60  180.68  180.58  180.65  3021456789
2026-07-19 00:03:00  180.65  180.70  180.62  180.68  3034567890
2026-07-19 00:04:00  180.68  180.75  180.65  180.72  3028901234

============================================================
TEST 3: Resample to 1-Hour OHLC
============================================================
✅ Resampled 86400 ticks to 24 candles (frequency: 1H)

DataFrame shape: (24, 5)
Columns: ['open', 'high', 'low', 'close', 'volume']

First 5 candles:
                          open    high     low   close        volume
timestamp                                                            
2026-07-19 00:00:00  180.50  181.25  180.30  181.10  182026873400
2026-07-19 01:00:00  181.10  181.80  180.95  181.65  182103456789
2026-07-19 02:00:00  181.65  182.20  181.50  182.00  182145678901
2026-07-19 03:00:00  182.00  182.50  181.85  182.35  182198765432
2026-07-19 04:00:00  182.35  182.90  182.20  182.75  182234567890

============================================================
TEST 4: Fetch Candles (Convenience Method)
============================================================
✅ Fetched 86400 ticks for BTC (2026-07-19 to 2026-07-20)
✅ Resampled 86400 ticks to 96 candles (frequency: 15T)

DataFrame shape: (96, 5)
Columns: ['open', 'high', 'low', 'close', 'volume']

First 5 candles:
                              open      high       low     close          volume
timestamp                                                                        
2026-07-19 00:00:00  50123.00  50145.50  50110.20  50135.10  11520000000000
2026-07-19 00:15:00  50135.10  50158.30  50120.45  50150.75  11534567890123
2026-07-19 00:30:00  50150.75  50175.20  50145.60  50168.90  11545678901234

============================================================
TEST 5: Empty Result Handling
============================================================
⚠️  No data found for NONEXISTENT between 2026-07-19 and 2026-07-20

DataFrame shape: (0, 2)
Is empty: True

✅ All tests complete!
```

---

## Step 5: Verify with QuestDB Console

**Open QuestDB Web Console:**

```bash
open http://localhost:9000
```

**Run these queries to verify data:**

```sql
-- 1. Check total tick count
SELECT symbol, count(*) as tick_count
FROM ticks
WHERE timestamp >= '2026-07-19'
  AND timestamp < '2026-07-20'
GROUP BY symbol;

-- Expected: ~86,400 ticks per symbol (1 tick/sec for 24 hours)

-- 2. Check price range for AAPL
SELECT 
    symbol,
    min(price) as min_price,
    max(price) as max_price,
    avg(price) as avg_price
FROM ticks
WHERE symbol = 'AAPL'
  AND timestamp >= '2026-07-19'
  AND timestamp < '2026-07-20';

-- 3. Sample first 10 ticks
SELECT *
FROM ticks
WHERE symbol = 'AAPL'
  AND timestamp >= '2026-07-19'
LIMIT 10;
```

---

## Success Criteria Checklist

Mark each as complete:

- [ ] `app/core/data_fetcher.py` created with `QuestDBFetcher` class
- [ ] `fetch_ticks()` method fetches data from QuestDB
- [ ] Returns Pandas DataFrame with correct structure
- [ ] DataFrame has DatetimeIndex (timestamp as index)
- [ ] Handles empty results gracefully (returns empty DataFrame)
- [ ] `resample_to_ohlc()` converts ticks to OHLC candles
- [ ] OHLC columns calculated correctly (open, high, low, close, volume)
- [ ] `fetch_candles()` convenience method works
- [ ] Can fetch 30 days of AAPL ticks without errors
- [ ] Can resample to different frequencies (1T, 5T, 1H, 1D)
- [ ] Test script runs successfully
- [ ] QuestDB connection errors handled with clear messages

---

## Common Issues and Solutions

### Issue 1: QuestDB connection refused

**Error:**
```
connection to server at "localhost" (127.0.0.1), port 8812 failed: Connection refused
```

**Solution:**
```bash
# Start QuestDB
docker start questdb

# Verify it's running
docker ps | grep questdb

# Check logs
docker logs questdb
```

### Issue 2: Table does not exist

**Error:**
```
psycopg2.errors.UndefinedTable: table 'ticks' does not exist
```

**Solution:**
Ensure Phase 2 is complete and ticks table has data:

```sql
-- In QuestDB console (http://localhost:9000)
SELECT count(*) FROM ticks;
```

If empty, run Phase 2 services to generate data.

### Issue 3: Empty DataFrame

**Error:**
```
⚠️  No data found for AAPL between 2026-06-19 and 2026-07-19
```

**Solution:**
1. Check if data exists for that date range in QuestDB
2. Verify symbol name is correct (case-sensitive)
3. Adjust date range to match available data

### Issue 4: Timestamp parsing errors

**Error:**
```
ValueError: time data '2026-07-19T10:15:23.123287Z' does not match format
```

**Solution:**
Already handled by `pd.to_datetime()` which auto-detects format. If it fails, QuestDB might be returning unexpected format.

### Issue 5: Memory issues with large datasets

**Error:**
```
MemoryError: Unable to allocate array
```

**Solution:**
Fetch smaller date ranges:

```python
# Instead of 30 days
df = fetcher.fetch_ticks('AAPL', '2026-06-19', '2026-07-19')  # 2.6M rows

# Fetch 1 day at a time
df = fetcher.fetch_ticks('AAPL', '2026-07-19', '2026-07-20')  # 86K rows
```

---

## Data Volume Reference

**From Architecture Document:**

| Period   | Ticks/Symbol | Total Ticks | DataFrame Size |
|----------|--------------|-------------|----------------|
| 1 day    | 86,400       | 864,000     | ~40 MB         |
| 1 week   | 604,800      | 6,048,000   | ~280 MB        |
| 30 days  | 2,592,000    | 25,920,000  | ~1.2 GB        |

**Resampled (1-minute candles):**

| Period   | Candles/Symbol | Total Candles | DataFrame Size |
|----------|----------------|---------------|----------------|
| 1 day    | 1,440          | 14,400        | ~1 MB          |
| 1 week   | 10,080         | 100,800       | ~7 MB          |
| 30 days  | 43,200         | 432,000       | ~30 MB         |

---

## Next Steps

Once Task 2 is complete:

**Task 3: Data Resampling (Enhanced)**
- Add advanced resampling features
- Handle missing data (forward-fill, interpolation)
- Add resampling validation

**Task 4: Technical Indicators**
- Implement RSI, MACD, Bollinger Bands
- Use fetched data to calculate indicators
- No external library needed (pure Pandas/NumPy)

See: `docs/phase-3/guides/03-indicators-implementation.md`

---

## Files Created

This task creates:

```
backtester/
├── app/
│   └── core/
│       └── data_fetcher.py       # QuestDBFetcher class
└── test_data_fetcher.py          # Test script
```

**Total:** 2 files  
**Time:** ~2 hours

---

## Verification Commands

Run these to verify Task 2 is complete:

```bash
# 1. Test imports
python -c "from app.core.data_fetcher import QuestDBFetcher; print('✅ Import works')"

# 2. Test QuestDB connection
python -c "from app.core.data_fetcher import QuestDBFetcher; f = QuestDBFetcher(); f._get_connection(); print('✅ Connection works')"

# 3. Run full test suite
python test_data_fetcher.py

# 4. Verify in QuestDB console
open http://localhost:9000
# Run: SELECT count(*) FROM ticks WHERE timestamp >= '2026-07-19';
```

If all tests pass, Task 2 is complete! ✅
