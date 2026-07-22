package com.quantstream.aggregator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily Aggregation Job
 *
 * Purpose: Aggregate candles_1m into candles_1d daily.
 *
 * Schedule: Runs once per day at 00:05:00 (5 minutes after midnight)
 *
 * Logic:
 * 1. For each symbol:
 *    - Query all candles_1m from yesterday (1440 rows per symbol)
 *    - Calculate daily OHLC:
 *      * open = first candle's open
 *      * high = max of all highs
 *      * low = min of all lows
 *      * close = last candle's close
 *      * volume = sum of all volumes
 *    - Insert into candles_1d
 *
 * Why 00:05 instead of 00:00?
 * - Ensures all 1-minute candles from yesterday are persisted
 * - Aggregator emits last candle at 23:59:00
 * - Database consumer writes by 23:59:05
 * - Wait 5 more minutes for safety = 00:05
 *
 * Add this service to strategy-engine or create separate daily-aggregator service.
 */
@Service
public class DailyAggregationJob {

    private static final Logger log = LoggerFactory.getLogger(DailyAggregationJob.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final List<String> SYMBOLS = List.of(
        "AAPL", "MSFT", "GOOGL", "TSLA", "AMZN",
        "BTC", "ETH", "SOL", "AVAX", "MATIC"
    );

    /**
     * Runs daily at 00:05:00 (5 minutes after midnight).
     *
     * Cron format: second minute hour day month weekday
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void aggregateDailyCandles() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        log.info("Starting daily aggregation for date: {}", yesterday);

        int totalAggregated = 0;

        for (String symbol : SYMBOLS) {
            try {
                // Query all 1-minute candles from yesterday (should be ~1440 rows)
                String query = """
                    SELECT
                        first(open) as day_open,
                        max(high) as day_high,
                        min(low) as day_low,
                        last(close) as day_close,
                        sum(volume) as day_volume
                    FROM candles_1m
                    WHERE symbol = ?
                      AND timestamp >= dateadd('d', -1, now())
                      AND timestamp < now()
                    """;

                DailyCandle dailyCandle = jdbcTemplate.queryForObject(
                    query,
                    (rs, rowNum) -> new DailyCandle(
                        symbol,
                        rs.getDouble("day_open"),
                        rs.getDouble("day_high"),
                        rs.getDouble("day_low"),
                        rs.getDouble("day_close"),
                        rs.getDouble("day_volume"),
                        java.sql.Timestamp.valueOf(yesterday.atStartOfDay())
                    ),
                    symbol
                );

                // Insert into candles_1d
                if (dailyCandle != null) {
                    jdbcTemplate.update(
                        "INSERT INTO candles_1d (symbol, open, high, low, close, volume, date) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        dailyCandle.symbol,
                        dailyCandle.open,
                        dailyCandle.high,
                        dailyCandle.low,
                        dailyCandle.close,
                        dailyCandle.volume,
                        dailyCandle.date
                    );

                    totalAggregated++;
                    log.debug("Aggregated {} for {}: O={} H={} L={} C={} V={}",
                        yesterday, symbol,
                        dailyCandle.open, dailyCandle.high, dailyCandle.low,
                        dailyCandle.close, dailyCandle.volume
                    );
                }

            } catch (Exception e) {
                log.error("Failed to aggregate {} for {}: {}", symbol, yesterday, e.getMessage());
            }
        }

        log.info("Daily aggregation complete: {} symbols aggregated for {}", totalAggregated, yesterday);
    }

    /**
     * DTO for daily candle
     */
    private static record DailyCandle(
        String symbol,
        double open,
        double high,
        double low,
        double close,
        double volume,
        java.sql.Timestamp date
    ) {}
}
