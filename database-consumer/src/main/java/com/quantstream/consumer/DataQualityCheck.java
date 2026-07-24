package com.quantstream.consumer;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;

// @SpringBootApplication  // DISABLED: Manually run with: mvn spring-boot:run -Dspring-boot.run.main-class=com.quantstream.consumer.DataQualityCheck
public class DataQualityCheck implements CommandLineRunner {
    
    private final JdbcTemplate jdbcTemplate;
    
    public DataQualityCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    public static void main(String[] args) {
        SpringApplication.run(DataQualityCheck.class, args);
    }
    
    @Override
    public void run(String... args) {
        System.out.println("\n=== WHAT IS 'FLAT'? ===");
        System.out.println("Flat = High equals Low (H=L)");
        System.out.println("This means only 1 tick was aggregated in that minute window.");
        System.out.println("");
        System.out.println("Example FLAT candle:   O=175.50 H=175.50 L=175.50 C=175.50");
        System.out.println("Example VARIED candle: O=175.50 H=175.52 L=175.48 C=175.51");
        System.out.println("                                    ^^^^    ^^^^");
        System.out.println("With 60 ticks/minute, we EXPECT varied candles (price moves during the minute).");
        System.out.println("");
        
        System.out.println("=== GOOD vs BAD CANDLES ===\n");
        
        // Show flat candles
        System.out.println("BAD (Flat - only 1 tick):");
        jdbcTemplate.query(
            "SELECT symbol, open, high, low, close, timestamp FROM candles_1m WHERE high = low LIMIT 3",
            rs -> {
                System.out.printf("  %s @ %s: O=%.2f H=%.2f L=%.2f C=%.2f\n",
                    rs.getString(1), rs.getTimestamp(6), 
                    rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5));
            }
        );
        
        System.out.println("\nGOOD (Varied - multiple ticks):");
        jdbcTemplate.query(
            "SELECT symbol, open, high, low, close, timestamp FROM candles_1m WHERE high != low LIMIT 3",
            rs -> {
                System.out.printf("  %s @ %s: O=%.2f H=%.2f L=%.2f C=%.2f\n",
                    rs.getString(1), rs.getTimestamp(6), 
                    rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5));
            }
        );
        
        System.out.println("\n=== TIME COVERAGE (60 days expected) ===\n");
        
        jdbcTemplate.query(
            "SELECT MIN(timestamp), MAX(timestamp) FROM candles_1m",
            rs -> {
                java.sql.Timestamp min = rs.getTimestamp(1);
                java.sql.Timestamp max = rs.getTimestamp(2);
                if (min != null && max != null) {
                    long days = (max.getTime() - min.getTime()) / (1000L * 60 * 60 * 24);
                    System.out.println("Earliest: " + min);
                    System.out.println("Latest:   " + max);
                    System.out.println("Span:     " + days + " days");
                    System.out.println(days >= 59 ? "✓ Coverage OK" : "✗ Coverage SHORT");
                } else {
                    System.out.println("✗ NO DATA in candles_1m table");
                }
            }
        );
        
        System.out.println("\n=== EXPECTED vs ACTUAL CANDLES ===\n");
        
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM candles_1m", Long.class);
        
        System.out.println("Expected: 60 days × 24 hrs × 60 min × 10 symbols = 864,000 candles");
        System.out.println("Actual:   " + total + " candles");
        
        if (total > 864000 * 1.1) {
            System.out.println("⚠ DUPLICATES: " + (total - 864000) + " extra candles (Kafka Streams issue)");
        } else if (total < 864000 * 0.9) {
            System.out.println("✗ MISSING DATA: " + (864000 - total) + " candles missing");
        } else {
            System.out.println("✓ Count reasonable (within 10%)");
        }
        
        System.out.println("\n=== SYMBOL DISTRIBUTION ===\n");
        
        // Count per symbol (simulated - QuestDB doesn't support GROUP BY easily)
        String[] symbols = {"AAPL", "GOOGL", "MSFT"};
        for (String sym : symbols) {
            Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM candles_1m WHERE symbol = ?", Long.class, sym);
            System.out.println(sym + ": " + count + " candles");
        }
        
        System.out.println("\n=== DATA QUALITY VERDICT ===\n");
        
        Long flat = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM candles_1m WHERE high = low", Long.class);
        double flatPct = flat * 100.0 / total;
        
        System.out.println("Flat rate: " + String.format("%.1f%%", flatPct));
        
        if (flatPct > 50) {
            System.out.println("✗ BAD: Most candles are flat (aggregator not processing 60 ticks/min)");
            System.out.println("  → Either backfiller sent 1 tick/min, OR aggregator windowing is wrong");
        } else if (flatPct > 10) {
            System.out.println("⚠ FAIR: Some flat candles (expected in low-volatility periods)");
        } else {
            System.out.println("✓ GOOD: Most candles have price variation");
        }
        
        System.exit(0);
    }
}
