# Phase 2: Task List

## Overview

Phase 2 builds the **intelligence layer** that transforms raw market data into actionable insights:
- **Aggregator:** Creates OHLC candles for frontend visualization
- **Strategy Engine:** 10 alpha strategies generating trading signals
- **Extended Consumer:** Persists candles + signals to QuestDB

**Total Time Estimate:** 12-16 hours  
**Approach:** Sequential milestones with end-to-end testing at each stage

---

## Prerequisites

Before starting Phase 2:

- [ ] Phase 1 complete (data-generator + database-consumer working)
- [ ] Docker Compose running (Kafka, QuestDB)
- [ ] QuestDB has tick data (run Phase 1 for at least 1 hour)
- [ ] Java 21 and Maven installed

---

## Task Overview

| Task | Description | Guide | Time |
|------|-------------|-------|------|
| 1 | Setup Strategy Engine Project | strategy-engine-project-setup.md | 1 hour |
| 2 | Build Strategy Framework | strategy-framework-guide.md | 2 hours |
| 3 | Implement First Strategy (MA Crossover) | implementing-first-strategy.md | 2 hours |
| 4 | Build Aggregator Service (Kafka Streams) | aggregator-service-guide.md | 2-3 hours |
| 5 | Extend Database Consumer | extending-database-consumer.md | 1 hour |
| 6 | Implement Remaining 9 Strategies | implementing-remaining-strategies.md | 4-5 hours |
| 7 | Integration Testing & Validation | integration-testing-guide.md | 1 hour |

---

## Task 1: Setup Strategy Engine Project

**Objective:** Create Spring Boot project with all dependencies and basic configuration.

### What You'll Build:
- Maven project structure
- `pom.xml` with Spring Boot 4.0.7
- `application.yml` configuration
- Model classes (Tick, Signal)
- Configuration classes (Kafka, QuestDB)
- Main application class

### Success Criteria:
- [ ] Project compiles (`mvn clean package`)
- [ ] Application starts without errors
- [ ] Connects to QuestDB successfully
- [ ] Connects to Kafka successfully
- [ ] Can query ticks from QuestDB
- [ ] Can send test signal to Kafka

### Guide:
Follow **`guides/strategy-engine-project-setup.md`** for detailed instructions.

**Estimated Time:** 1 hour

---

## Task 2: Build Core Framework

**Objective:** Create the foundation all strategies will use.

### What You'll Build:
- `TradingStrategy` interface (contract all strategies implement)
- `StrategyScheduler` (runs all strategies every minute)
- `IndicatorUtils` (shared calculation methods: MA, RSI, etc.)
- Kafka topic for signals

### Key Concepts:
- Interface-based design
- Spring auto-discovery of strategies
- Dependency injection
- Scheduled tasks

### Success Criteria:
- [ ] TradingStrategy interface compiles
- [ ] StrategyScheduler auto-discovers strategies
- [ ] IndicatorUtils can calculate MA, RSI
- [ ] Scheduler runs every minute (even with 0 strategies)
- [ ] Kafka topic `trading-signals` exists

### Guide:
Follow **`guides/strategy-framework-guide.md`** for detailed implementation.

**Estimated Time:** 2 hours

---

## Task 3: Implement First Strategy (MA Crossover)

**Objective:** Build complete end-to-end flow with one working strategy.

### What You'll Build:
- `MaCrossoverStrategy` class implementing `TradingStrategy`
- Golden Cross / Death Cross detection logic
- State tracking (previous MA values)
- Integration with scheduler

### Key Learning:
- How strategies query QuestDB
- How to detect crossovers (compare current vs previous)
- How to produce signals to Kafka
- Error handling in strategies

### Success Criteria:
- [ ] MA Crossover strategy compiles
- [ ] Spring auto-discovers it (logs "Running 1 strategies")
- [ ] Strategy runs every minute
- [ ] Generates BUY signal on Golden Cross
- [ ] Generates SELL signal on Death Cross
- [ ] Signals appear in Kafka topic
- [ ] No exceptions in logs

### Guide:
Follow **`guides/implementing-first-strategy.md`** for step-by-step implementation.

**Estimated Time:** 2 hours

---

## Task 4: Build Aggregator Service (Kafka Streams)

**Objective:** Create OHLC candles from raw ticks for frontend visualization.

### What You'll Build:
- New Spring Boot project: `aggregator`
- Kafka Streams application with windowed aggregation
- 1-minute tumbling windows
- Calculate OHLC (Open, High, Low, Close) + Volume
- Produce candles to Kafka topic `candles-1m`

### Key Concepts:
- Kafka Streams DSL (KStream, KTable, TimeWindows)
- Stateful processing (windowing, aggregation)
- Tumbling windows vs hopping windows
- Serialization (JSON Serde for custom types)
- State stores and changelog topics

### Why Separate Service:
- **Purpose:** Create candles for **frontend charts** (not strategies)
- **Processing model:** Stateful stream processing (not queries)
- **Technology:** Kafka Streams (event-time windowing)
- **Failure isolation:** Aggregator down → charts break, strategies continue

### Success Criteria:
- [ ] Aggregator project compiles
- [ ] Consumes from `market-data` topic
- [ ] Produces to `candles-1m` topic
- [ ] Candles emit at end of each minute
- [ ] OHLC values correct (verified manually)
- [ ] Can see candles: `docker exec kafka kafka-console-consumer --topic candles-1m`
- [ ] State restored after restart

### Guide:
Follow **`guides/aggregator-service-guide.md`** for complete implementation.

**Estimated Time:** 2-3 hours

---

## Task 5: Extend Database Consumer

**Objective:** Make database-consumer write candles + signals to QuestDB.

### What You'll Modify:
- Existing `database-consumer` project
- Add consumer for `candles-1m` topic → writes to `candles_1m` table
- Add consumer for `trading-signals` topic → writes to `signals` table
- Create QuestDB tables for candles and signals
- Update consumer group IDs

### What You'll Build:
**New Tables:**
```sql
CREATE TABLE candles_1m (
    symbol SYMBOL,
    open DOUBLE,
    high DOUBLE,
    low DOUBLE,
    close DOUBLE,
    volume LONG,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;

CREATE TABLE signals (
    symbol SYMBOL,
    action SYMBOL,
    strategy_name SYMBOL,
    confidence DOUBLE,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

**New Consumers:**
- `CandleConsumer` - consumes `candles-1m`, writes to QuestDB
- `SignalConsumer` - consumes `trading-signals`, writes to QuestDB

### Key Concepts:
- Multiple Kafka consumers in single application
- Batch writing for candles (high volume)
- Real-time writing for signals (low volume)
- Separate consumer groups (independent offset tracking)

### Success Criteria:
- [ ] Two new QuestDB tables created
- [ ] Candles appear in `candles_1m` table
- [ ] Signals appear in `signals` table
- [ ] Can query: `SELECT * FROM candles_1m WHERE symbol='AAPL' LATEST BY symbol`
- [ ] Can query: `SELECT * FROM signals ORDER BY timestamp DESC LIMIT 10`
- [ ] No consumer lag (check Kafka consumer group offsets)

### Guide:
Follow **`guides/extending-database-consumer.md`** for implementation.

**Estimated Time:** 1 hour

---

## Task 6: Implement Remaining 9 Strategies

**Objective:** Scale from 1 strategy to 10 strategies.

### What You'll Build:
Add these strategy implementations:
1. RSI Strategy (mean reversion)
2. Bollinger Bands Strategy (volatility)
3. MACD Strategy (trend + momentum)
4. Stochastic Oscillator Strategy (momentum)
5. Williams %R Strategy (momentum)
6. ADX Strategy (trend strength)
7. Donchian Channel Strategy (breakout)
8. ROC Strategy (momentum)
9. VWAP Strategy (volume-weighted)

### Approach:
- Follow the pattern from MA Crossover
- Copy existing strategy as template
- Modify indicator calculations
- Update signal logic
- Test each strategy individually

### Key Learning:
- Strategy patterns (crossover, threshold, breakout)
- Adding indicator calculations to IndicatorUtils
- Confidence score tuning
- Performance considerations (caching, query optimization)

### Success Criteria:
- [ ] All 10 strategies compile
- [ ] Logs show "Running 10 strategies for 10 symbols"
- [ ] Each strategy generates signals
- [ ] REST API shows signals from all strategies
- [ ] Signal counts distributed across strategies
- [ ] No errors for 30 minutes continuous run

### Guide:
Follow **`guides/adding-more-strategies.md`** for patterns and implementation.

**Estimated Time:** 3-4 hours

---

## Task 7: Integration Testing & Validation

**Objective:** Verify complete Phase 2 works end-to-end.

### What You'll Do:
- Update `docker-compose.yml` with new services
- Run full system for 1 hour
- Monitor performance and errors
- Test all REST API endpoints
- Verify data integrity
- Document final architecture

### Test Scenarios:
1. **Cold start** - Start all services from scratch
2. **Data flow** - Ticks → Strategies → Signals → QuestDB
3. **Signal diversity** - All 10 strategies producing signals
4. **API responses** - All endpoints return correct data
5. **Error recovery** - Services restart after failure
6. **Resource usage** - Memory and CPU within limits

### Success Criteria:
- [ ] All 6 services start successfully
- [ ] System runs for 1 hour without errors
- [ ] 100+ signals generated
- [ ] All 10 strategies contributed signals
- [ ] REST API responses < 200ms
- [ ] Memory usage < 1.2 GB total
- [ ] Can restart services without data loss

### Guide:
Follow **`guides/integration-testing-guide.md`** for comprehensive testing.

**Estimated Time:** 1 hour (mostly monitoring)

---

## Phase 2 Complete! 🎉

**What You've Built:**
- ✅ Aggregator service (creates OHLC candles for frontend)
- ✅ Strategy engine with 10 trading strategies
- ✅ Extended database consumer (writes candles + signals)
- ✅ End-to-end intelligence layer

**System Architecture:**
```
Docker Compose:
├── Zookeeper
├── Kafka (3 topics: market-data, candles-1m, trading-signals)
└── QuestDB (3 tables: ticks, candles_1m, signals)

Application Services:
├── data-generator (Phase 1)
├── database-consumer (Phase 1, extended Phase 2)
├── aggregator (Phase 2) - Creates candles
└── strategy-engine (Phase 2) - 10 strategies inside
```

**Data Flow:**
```
Generator → market-data → Consumer → ticks (QuestDB)
                ↓                          ↓
         Aggregator (Kafka Streams)   Strategies (query)
                ↓                          ↓
            candles-1m               trading-signals
                ↓                          ↓
         Consumer (extended)        Consumer (extended)
                ↓                          ↓
         candles_1m (QuestDB)       signals (QuestDB)
```

**Next Phase:**
- Phase 3: Backtester (evaluate strategy performance)
- Phase 4: API Gateway + React frontend (visualize + interact)

---

## Troubleshooting Guide

### Common Issues

**"Running 0 strategies for 10 symbols"**
- Strategy class missing `@Component` annotation
- Strategy not in scanned package (must be under `com.quantstream.strategy`)
- Check Spring component scanning logs

**"Not enough data for MA(50)"**
- QuestDB doesn't have 50 ticks per symbol yet
- Run Phase 1 services longer to accumulate data
- Check: `SELECT symbol, count(*) FROM ticks GROUP BY symbol`

**"Signals not appearing in QuestDB"**
- Aggregator not running
- Kafka topic doesn't exist
- Check aggregator logs for errors
- Verify: `docker exec -it kafka kafka-topics --list --bootstrap-server localhost:9092`

**"OutOfMemoryError"**
- Increase JVM heap: `MAVEN_OPTS="-Xmx512m" mvn spring-boot:run`
- Reduce scheduler frequency (change 60000ms to 120000ms)
- Check for memory leaks (previous indicator values not garbage collected)

**"Strategy produces duplicate signals"**
- Aggregator deduplication not working
- Check deduplication window (5 minutes)
- Verify timestamp comparison logic

### Getting Help

- Check guide for specific task
- Review Phase 2 concepts documentation
- Read strategy implementation in actual code
- Check Phase 1 documentation for infrastructure issues

---

## Notes

- **Don't rush** - Take time to understand each concept
- **Test incrementally** - Verify each task before moving to next
- **Read the guides** - They explain WHY, not just HOW
- **Experiment** - Try modifying strategies, see what happens
- **Document learnings** - Keep notes of issues and solutions
