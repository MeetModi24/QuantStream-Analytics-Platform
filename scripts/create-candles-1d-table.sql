-- Create candles_1d table for daily OHLC candles
-- This table is used by strategy engine for traditional technical analysis
-- MA(50) means 50 DAYS of daily closing prices

CREATE TABLE IF NOT EXISTS candles_1d (
    symbol SYMBOL CAPACITY 256 CACHE,
    open DOUBLE,
    high DOUBLE,
    low DOUBLE,
    close DOUBLE,
    volume DOUBLE,
    date TIMESTAMP
) TIMESTAMP(date) PARTITION BY DAY;

-- Create index on symbol for faster queries
-- QuestDB automatically indexes the designated timestamp column (date)

-- Note: This table must be populated with:
-- 1. Daily aggregation job (aggregate candles_1m to candles_1d daily)
-- 2. Backfill script (generate 50+ days of synthetic historical data)
