# Phase 2: Task List

## Overview

Phase 2 builds the strategy engine that analyzes tick data and generates trading signals.

**Total Time Estimate:** 10-12 hours  
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
| 2 | Build Core Framework | strategy-framework-guide.md | 2 hours |
| 3 | Implement First Strategy (MA Crossover) | implementing-first-strategy.md | 2 hours |
| 4 | Build Signal Aggregator Service | signal-aggregator-guide.md | 2-3 hours |
| 5 | Add Remaining 9 Strategies | adding-more-strategies.md | 3-4 hours |
| 6 | Integration Testing & Deployment | testing-and-deployment.md | 1 hour |

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

## Task 4: Build Signal Aggregator Service

**Objective:** Create service that consumes signals and provides REST API.

### What You'll Build:
- New Spring Boot project: `signal-aggregator`
- QuestDB `signals` table
- Kafka consumer for `trading-signals` topic
- Deduplication logic (avoid duplicate signals)
- REST API endpoints:
  - `GET /api/signals` - List all signals
  - `GET /api/signals?symbol=AAPL` - Filter by symbol
  - `GET /api/signals/strategy/{name}` - Filter by strategy
  - `GET /api/signals/latest` - Recent signals
  - `GET /api/signals/count` - Signal counts by strategy

### Key Concepts:
- Kafka consumer configuration
- Manual vs auto commit
- REST API design
- SQL queries in QuestDB

### Success Criteria:
- [ ] Aggregator project compiles
- [ ] Consumes signals from Kafka
- [ ] Persists signals to QuestDB
- [ ] Deduplication works (no duplicates within 5 min)
- [ ] REST API returns correct data
- [ ] Can query signals: `SELECT * FROM signals LIMIT 10`

### Guide:
Follow **`guides/signal-aggregator-guide.md`** for complete implementation.

**Estimated Time:** 2-3 hours

---

## Task 5: Add Remaining 9 Strategies

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

## Task 6: Integration Testing & Deployment

**Objective:** Verify complete system works end-to-end and prepare for deployment.

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
Follow **`guides/testing-and-deployment.md`** for comprehensive testing.

**Estimated Time:** 1 hour (mostly monitoring)

---

## Phase 2 Complete! 🎉

**What You've Built:**
- ✅ Strategy engine with 10 trading strategies
- ✅ Interface-based design (easy to extend)
- ✅ Signal aggregator with REST API
- ✅ End-to-end pipeline: ticks → analysis → signals → storage

**System Architecture:**
```
Docker Compose:
├── Zookeeper
├── Kafka (2 topics: market-data, trading-signals)
└── QuestDB (2 tables: ticks, signals)

Application Services:
├── data-generator (Phase 1)
├── database-consumer (Phase 1)
├── strategy-engine (Phase 2) - 10 strategies inside
└── signal-aggregator (Phase 2) - REST API
```

**Next Phase:**
- Phase 3: Python backtester + React frontend
- Evaluate strategy performance
- Build trading dashboard

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
