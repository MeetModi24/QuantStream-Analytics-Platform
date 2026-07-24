package com.quantstream.dbwriter.schema;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the QuestDB tables on startup if they do not already exist.
 *
 * <p>DDL is issued over the PostgreSQL wire protocol (the same pooled {@link JdbcTemplate}
 * / DataSource used for row ingestion). QuestDB's {@code CREATE TABLE IF NOT EXISTS}
 * makes this idempotent, so restarting the service is safe.
 *
 * <p>Tables are partitioned by time and declare a WAL, which is what enables the
 * cheap partition-drop retention strategy documented in the planning docs.
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    // DEDUP UPSERT KEYS makes ingestion idempotent: a re-delivered row with the same
    // (token, ts) overwrites rather than duplicates. This neutralizes Kafka's
    // at-least-once delivery and consumer restarts without any app-side dedup logic.
    private static final String ORDER_BOOK_DDL = """
            CREATE TABLE IF NOT EXISTS order_book_snapshots (
                token SYMBOL CAPACITY 65536 CACHE,
                best_bid_price DOUBLE,
                best_bid_volume DOUBLE,
                best_ask_price DOUBLE,
                best_ask_volume DOUBLE,
                bid_depth_l5 DOUBLE,
                ask_depth_l5 DOUBLE,
                ts TIMESTAMP
            ) TIMESTAMP(ts) PARTITION BY DAY WAL
            DEDUP UPSERT KEYS(ts, token)
            """;

    private static final String FEATURES_DDL = """
            CREATE TABLE IF NOT EXISTS features (
                token SYMBOL CAPACITY 65536 CACHE,
                obi DOUBLE,
                microprice DOUBLE,
                mid_price DOUBLE,
                spread DOUBLE,
                spread_bps DOUBLE,
                ts TIMESTAMP
            ) TIMESTAMP(ts) PARTITION BY DAY WAL
            DEDUP UPSERT KEYS(ts, token)
            """;

    // Signals include `strategy` in the dedup key: two strategies may legitimately
    // signal the same token at the same instant, and both must be recorded.
    private static final String SIGNALS_DDL = """
            CREATE TABLE IF NOT EXISTS signals (
                strategy SYMBOL CAPACITY 256 CACHE,
                token SYMBOL CAPACITY 65536 CACHE,
                action SYMBOL CAPACITY 8 CACHE,
                price DOUBLE,
                confidence DOUBLE,
                reason STRING,
                ts TIMESTAMP
            ) TIMESTAMP(ts) PARTITION BY DAY WAL
            DEDUP UPSERT KEYS(ts, token, strategy)
            """;

    // Paper-trading position snapshots from the Signal Aggregator. A snapshot is unique
    // per (ts, strategy, token) — a redelivered snapshot upserts rather than duplicating.
    private static final String POSITIONS_DDL = """
            CREATE TABLE IF NOT EXISTS positions (
                strategy SYMBOL CAPACITY 256 CACHE,
                token SYMBOL CAPACITY 65536 CACHE,
                net_position DOUBLE,
                avg_entry_price DOUBLE,
                realized_pnl DOUBLE,
                unrealized_pnl DOUBLE,
                ts TIMESTAMP
            ) TIMESTAMP(ts) PARTITION BY DAY WAL
            DEDUP UPSERT KEYS(ts, strategy, token)
            """;

    // Per-strategy performance snapshots. Unique per (ts, strategy).
    private static final String STRATEGY_PNL_DDL = """
            CREATE TABLE IF NOT EXISTS strategy_pnl (
                strategy SYMBOL CAPACITY 256 CACHE,
                realized_pnl DOUBLE,
                unrealized_pnl DOUBLE,
                total_pnl DOUBLE,
                num_trades LONG,
                win_rate DOUBLE,
                ts TIMESTAMP
            ) TIMESTAMP(ts) PARTITION BY DAY WAL
            DEDUP UPSERT KEYS(ts, strategy)
            """;

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        try {
            jdbc.execute(ORDER_BOOK_DDL);
            jdbc.execute(FEATURES_DDL);
            jdbc.execute(SIGNALS_DDL);
            jdbc.execute(POSITIONS_DDL);
            jdbc.execute(STRATEGY_PNL_DDL);
            log.info("QuestDB schema ready (order_book_snapshots, features, signals, positions, strategy_pnl)");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize QuestDB schema", e);
        }
    }
}
