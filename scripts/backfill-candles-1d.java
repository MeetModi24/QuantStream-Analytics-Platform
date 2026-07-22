package com.quantstream.scripts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Backfill Script for candles_1d Table
 *
 * Purpose: Generate 50+ days of synthetic daily OHLC candles for each symbol.
 *
 * This script creates realistic historical data using random walk simulation
 * so that strategies have enough data to calculate indicators like MA(50).
 *
 * Usage:
 * 1. Build: mvn clean package
 * 2. Run: java -jar target/backfill-candles-1d.jar
 * 3. Verify: SELECT count(*) FROM candles_1d;
 *
 * Expected output: 10 symbols × 60 days = 600 rows
 */
@SpringBootApplication
public class BackfillCandles1d implements CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final List<String> SYMBOLS = Arrays.asList(
        "AAPL", "MSFT", "GOOGL", "TSLA", "AMZN",
        "BTC", "ETH", "SOL", "AVAX", "MATIC"
    );

    // Starting prices for each symbol
    private static final double[] BASE_PRICES = {
        180.0, 410.0, 140.0, 250.0, 175.0,
        50000.0, 2800.0, 145.0, 38.0, 0.88
    };

    private static final int DAYS_TO_BACKFILL = 60;  // 60 days (more than MA(50) needs)
    private static final Random random = new Random(12345);  // Fixed seed for reproducibility

    public static void main(String[] args) {
        SpringApplication.run(BackfillCandles1d.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=== Starting candles_1d Backfill ===");
        System.out.println("Symbols: " + SYMBOLS);
        System.out.println("Days: " + DAYS_TO_BACKFILL);

        int totalInserted = 0;

        for (int i = 0; i < SYMBOLS.size(); i++) {
            String symbol = SYMBOLS.get(i);
            double basePrice = BASE_PRICES[i];

            System.out.println("\nBackfilling " + symbol + " (base price: " + basePrice + ")");

            double currentPrice = basePrice;

            for (int day = DAYS_TO_BACKFILL; day >= 0; day--) {
                LocalDate date = LocalDate.now().minusDays(day);

                // Generate realistic daily OHLC using random walk
                double dailyChange = (random.nextDouble() - 0.5) * 0.04;  // ±2% daily change
                double open = currentPrice;
                double close = open * (1 + dailyChange);
                double high = Math.max(open, close) * (1 + random.nextDouble() * 0.02);  // +0-2%
                double low = Math.min(open, close) * (1 - random.nextDouble() * 0.02);   // -0-2%
                double volume = 1_000_000 + random.nextDouble() * 5_000_000;  // 1M-6M volume

                // Insert into QuestDB
                jdbcTemplate.update(
                    "INSERT INTO candles_1d (symbol, open, high, low, close, volume, date) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    symbol, open, high, low, close, volume, java.sql.Timestamp.valueOf(date.atStartOfDay())
                );

                currentPrice = close;  // Next day starts from previous close
                totalInserted++;

                if (day % 10 == 0) {
                    System.out.print(".");
                }
            }

            System.out.println(" Done! " + (DAYS_TO_BACKFILL + 1) + " days inserted.");
        }

        System.out.println("\n=== Backfill Complete ===");
        System.out.println("Total rows inserted: " + totalInserted);
        System.out.println("Expected: " + (SYMBOLS.size() * (DAYS_TO_BACKFILL + 1)));

        // Verify
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM candles_1d", Long.class);
        System.out.println("Verification: candles_1d table has " + count + " rows");

        // Show sample data
        System.out.println("\nSample data (most recent 5 rows):");
        jdbcTemplate.query(
            "SELECT * FROM candles_1d ORDER BY date DESC LIMIT 5",
            (rs) -> {
                System.out.printf("%s | %s | open=%.2f high=%.2f low=%.2f close=%.2f vol=%.0f%n",
                    rs.getString("symbol"),
                    rs.getTimestamp("date"),
                    rs.getDouble("open"),
                    rs.getDouble("high"),
                    rs.getDouble("low"),
                    rs.getDouble("close"),
                    rs.getDouble("volume")
                );
            }
        );
    }
}
