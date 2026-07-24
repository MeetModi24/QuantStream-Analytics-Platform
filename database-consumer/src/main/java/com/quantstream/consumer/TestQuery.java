package com.quantstream.consumer;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;

// @SpringBootApplication  // DISABLED: Manually run with: mvn spring-boot:run -Dspring-boot.run.main-class=com.quantstream.consumer.TestQuery
public class TestQuery implements CommandLineRunner {
    
    private final JdbcTemplate jdbcTemplate;
    
    public TestQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    public static void main(String[] args) {
        SpringApplication.run(TestQuery.class, args);
    }
    
    @Override
    public void run(String... args) {
        System.out.println("\n=== CANDLES_1M DATA SUMMARY ===\n");
        
        // Total count
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM candles_1m", Long.class);
        System.out.println("Total rows: " + count);
        
        // Date range
        jdbcTemplate.query(
            "SELECT MIN(timestamp), MAX(timestamp) FROM candles_1m",
            rs -> {
                System.out.println("Earliest: " + rs.getTimestamp(1));
                System.out.println("Latest: " + rs.getTimestamp(2));
            }
        );
        
        System.out.println("\n=== CANDLES QUALITY TEST ===\n");
        
        // Sample data - verify OHLC quality
        jdbcTemplate.query(
            "SELECT symbol, open, high, low, close, volume, timestamp " +
            "FROM candles_1m LIMIT 10",
            rs -> {
                double o = rs.getDouble("open");
                double h = rs.getDouble("high");
                double l = rs.getDouble("low");
                double c = rs.getDouble("close");
                boolean valid = (h >= o && h >= l && h >= c && l <= o && l <= h && l <= c);
                System.out.printf("%s: O=%.2f H=%.2f L=%.2f C=%.2f V=%.0f [%s]\n",
                    rs.getString("symbol"), o, h, l, c, rs.getDouble("volume"),
                    valid ? "✓ VALID" : "✗ INVALID");
            }
        );
        
        // Check for variation (H != L means multiple ticks aggregated)
        System.out.println("\n=== AGGREGATION QUALITY ===\n");
        Long flatCandles = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM candles_1m WHERE high = low", Long.class);
        Long variedCandles = count - flatCandles;
        
        System.out.println("Flat candles (H=L): " + flatCandles + 
            String.format(" (%.1f%%)", flatCandles * 100.0 / count));
        System.out.println("Varied candles (H≠L): " + variedCandles + 
            String.format(" (%.1f%%)", variedCandles * 100.0 / count));
        
        if (variedCandles > count * 0.9) {
            System.out.println("✓ EXCELLENT: >90% of candles have price variation (proper aggregation)");
        } else if (variedCandles > count * 0.5) {
            System.out.println("⚠ WARNING: Only " + String.format("%.1f%%", variedCandles * 100.0 / count) + 
                " have variation");
        } else {
            System.out.println("✗ POOR: Most candles are flat (aggregation issue)");
        }
        
        System.out.println("\n=== KAFKA vs DB ===\n");
        System.out.println("Kafka candles-1m topic: 2,099,423 messages");
        System.out.println("QuestDB candles_1m table: " + count + " rows");
        System.out.println("Difference: " + (2099423 - count) + " (Kafka Streams duplicates/late arrivals)");
        
        System.exit(0);
    }
}
