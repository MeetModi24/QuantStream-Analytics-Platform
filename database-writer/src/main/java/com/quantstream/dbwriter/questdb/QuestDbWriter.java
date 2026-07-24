package com.quantstream.dbwriter.questdb;

import com.quantstream.common.model.Features;
import com.quantstream.common.model.OrderBookSnapshot;
import com.quantstream.common.model.Position;
import com.quantstream.common.model.PriceLevel;
import com.quantstream.common.model.Signal;
import com.quantstream.common.model.StrategyPnl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Persists domain objects to QuestDB over the PostgreSQL wire protocol.
 *
 * <p>Each method maps one domain record to one row via a parameterized INSERT. The
 * {@code token} lands in a SYMBOL column (QuestDB's interned string type, ideal for
 * the small, repeating set of tickers) and each record's own timestamp becomes the
 * designated timestamp, so rows are stored in event-time order regardless of
 * ingestion lag. Idempotency is handled by the tables' {@code DEDUP UPSERT KEYS}
 * (see {@code SchemaInitializer}), which applies to the PG-wire path as well.
 *
 * <p><b>Why PG-wire and not ILP:</b> QuestDB's native ILP client formats doubles via
 * {@code jdk.internal.math.FDBigInteger}, a package-private JDK class it cannot access
 * on Java 17+ ({@code IllegalAccessError}); no module flag fixes a package-private
 * access. The PG-wire path formats server-side and is fully JDK-26 compatible.
 *
 * <p><b>Threading:</b> {@link JdbcTemplate} is thread-safe and draws pooled
 * connections from HikariCP, so the multiple Kafka listener threads (order books,
 * features, signals) call this writer concurrently without coordination.
 *
 * <p>QuestDB's {@code TIMESTAMP} columns are microseconds since the Unix epoch; the
 * driver binds them from a {@code long}, so {@link Instant} values are converted with
 * {@link #toMicros(Instant)}.
 */
@Component
public class QuestDbWriter {

    private static final String INSERT_ORDER_BOOK = """
            INSERT INTO order_book_snapshots
                (token, best_bid_price, best_bid_volume, best_ask_price, best_ask_volume,
                 bid_depth_l5, ask_depth_l5, ts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_FEATURES = """
            INSERT INTO features
                (token, obi, microprice, mid_price, spread, spread_bps, ts)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_SIGNAL = """
            INSERT INTO signals
                (strategy, token, action, price, confidence, reason, ts)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_POSITION = """
            INSERT INTO positions
                (strategy, token, net_position, avg_entry_price, realized_pnl, unrealized_pnl, ts)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_STRATEGY_PNL = """
            INSERT INTO strategy_pnl
                (strategy, realized_pnl, unrealized_pnl, total_pnl, num_trades, win_rate, ts)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public QuestDbWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes one order book snapshot, deriving best-level prices/volumes and the
     * cumulative depth across all levels.
     */
    public void writeOrderBook(OrderBookSnapshot ob) {
        jdbc.update(INSERT_ORDER_BOOK,
                ob.token(),
                ob.bestBidPrice(),
                ob.bestBidVolume(),
                ob.bestAskPrice(),
                ob.bestAskVolume(),
                totalVolume(ob.bids()),
                totalVolume(ob.asks()),
                toMicros(ob.timestamp()));
    }

    /** Writes one computed feature row. */
    public void writeFeatures(Features f) {
        jdbc.update(INSERT_FEATURES,
                f.token(),
                f.obi(),
                f.microprice(),
                f.midPrice(),
                f.spread(),
                f.spreadBps(),
                toMicros(f.timestamp()));
    }

    /** Writes one trading signal. */
    public void writeSignal(Signal s) {
        jdbc.update(INSERT_SIGNAL,
                s.strategy(),
                s.token(),
                s.action().name(),
                s.price(),
                s.confidence(),
                s.reason() == null ? "" : s.reason(),
                toMicros(s.timestamp()));
    }

    /** Writes one paper-trading position snapshot from the Signal Aggregator. */
    public void writePosition(Position p) {
        jdbc.update(INSERT_POSITION,
                p.strategy(),
                p.token(),
                p.netPosition(),
                p.avgEntryPrice(),
                p.realizedPnl(),
                p.unrealizedPnl(),
                toMicros(p.timestamp()));
    }

    /** Writes one per-strategy performance snapshot from the Signal Aggregator. */
    public void writeStrategyPnl(StrategyPnl s) {
        jdbc.update(INSERT_STRATEGY_PNL,
                s.strategy(),
                s.realizedPnl(),
                s.unrealizedPnl(),
                s.totalPnl(),
                s.numTrades(),
                s.winRate(),
                toMicros(s.timestamp()));
    }

    /** QuestDB TIMESTAMP is microseconds since the Unix epoch. */
    private static long toMicros(Instant ts) {
        return ts.getEpochSecond() * 1_000_000L + ts.getNano() / 1_000L;
    }

    private static double totalVolume(Iterable<PriceLevel> levels) {
        double sum = 0.0;
        for (PriceLevel level : levels) {
            sum += level.volume();
        }
        return sum;
    }
}
