# Phase 2 Documentation

## Overview

Complete documentation for Phase 2: Strategy Engine implementation.

**Goal:** Build trading strategy microservices that analyze stored tick data and generate trading signals.

---

## Documentation Structure

```
docs/phase-2/
├── PHASE-2-OVERVIEW.md              # High-level architecture and design decisions
├── README.md                         # This file
├── concepts/                         # Core concepts explained
│   ├── 01-what-are-trading-strategies.md
│   ├── 02-technical-indicators-explained.md
│   └── 03-interface-based-strategy-design.md
├── guides/                           # Step-by-step implementation guides
│   └── strategy-engine-setup.md
└── tasks/                            # Task list for implementation
    └── TASK-LIST.md
```

---

## Where to Start

### 1. **Understand Concepts** (Read First)

Read these in order to build foundational knowledge:

1. **01-what-are-trading-strategies.md**
   - What is a trading strategy?
   - Why strategies need historical data
   - Types of strategies (trend, mean-reversion, momentum, volume)
   - Strategy vs backtester

2. **02-technical-indicators-explained.md**
   - Moving Average (MA)
   - RSI (Relative Strength Index)
   - Bollinger Bands
   - MACD
   - Stochastic, Williams %R, ROC, ADX, Donchian, VWAP
   - Code examples for each

3. **03-interface-based-strategy-design.md**
   - Why interface-based design?
   - TradingStrategy interface
   - How Spring auto-discovers strategies
   - Benefits vs separate microservices
   - Common implementation patterns

**Time:** 1-2 hours reading

---

### 2. **Follow Task List** (Implement)

Open `tasks/TASK-LIST.md` and follow step-by-step:

**Phase 2A: Core Infrastructure (Week 1)**
- Task 6: Create Strategy Engine Project
- Task 7: Create Models (Tick, Signal)
- Task 8: Create Configuration (Kafka, QuestDB)
- Task 9: Create TradingStrategy Interface
- Task 10: Create Indicator Utilities
- Task 11: Implement MA Crossover Strategy
- Task 12: Create Strategy Scheduler
- Task 13: Create Kafka Topic
- Task 14: Test End-to-End
- Task 15-19: Aggregator Service (Kafka Streams for candles)

**Phase 2B: Scale to 10 Strategies (Week 2)**
- Task 20-28: Implement 9 more strategies
- Task 29: Final integration test

**Time:** 10-12 hours total

---

### 3. **Use Guides** (Reference)

Refer to guides when implementing specific tasks:

- **strategy-engine-setup.md** - Detailed project setup (covers Tasks 6-10)
- More guides TBD as you implement

---

## Key Architectural Decisions

### Single Strategy Service (Not 10 Microservices)

**Decision:** All 10 strategies run inside ONE `strategy-engine` service.

**Why:**
- Strategies are algorithms (idempotent, deterministic)
- Identical resource profiles (no scaling differences)
- Free tier deployment constraints (11 services = 2.75 GB RAM)
- Simpler development and debugging
- Zero code duplication

**How:**
- Interface-based design (`TradingStrategy` interface)
- Spring auto-discovers all `@Component` implementations
- `StrategyScheduler` runs all strategies every minute

---

### Aggregator Service (Separate Service)

**Decision:** Aggregator service creates OHLC candles from raw ticks for frontend visualization.

**Why:**
- Different responsibility (Kafka Streams windowing vs strategy analysis)
- Different scaling profile (streaming vs batch query)
- Different failure domain (aggregator down doesn't stop signal generation)

---

## Services in Phase 2

| Service | Port | Purpose | Language |
|---------|------|---------|----------|
| strategy-engine | 8083 | Run 10 strategies, produce signals | Java/Spring Boot |
| aggregator | 8084 | Create OHLC candles from ticks (Kafka Streams) | Java/Spring Boot |

**Total:** 2 new services (+ 2 from Phase 1 = 4 total application services)

---

## Data Flow

```
Phase 1 (Existing):
data-generator → Kafka (market-data) → database-consumer → QuestDB (ticks table)

Phase 2 (New):
Kafka (market-data)
    ↓ (consume)
Aggregator (Kafka Streams)
    ↓ (produce)
Kafka (candles-1m topic)
    ↓ (consume)
database-consumer (extended) → QuestDB (candles_1m table)

QuestDB (ticks table)
    ↓ (strategies query)
strategy-engine (10 strategies inside)
    ↓ (produce)
Kafka (trading-signals topic)
    ↓ (consume)
database-consumer (extended) → QuestDB (signals table)
```

---

## 10 Trading Strategies

### Trend Following (4)
1. **MA Crossover** - Golden/Death cross signals
2. **MACD** - Moving average convergence/divergence
3. **ADX** - Trend strength filter
4. **Donchian Channel** - Breakout trading

### Mean Reversion (3)
5. **RSI** - Overbought/oversold (30/70 thresholds)
6. **Bollinger Bands** - Price touches bands
7. **Stochastic** - Momentum oscillator

### Momentum (2)
8. **ROC** - Rate of change
9. **Williams %R** - Momentum oscillator

### Volume-Based (1)
10. **VWAP** - Volume-weighted average price

---

## Success Criteria

Phase 2 is complete when:

- [ ] Strategy engine starts without errors
- [ ] All 10 strategies auto-discovered by Spring
- [ ] Strategies run every minute
- [ ] Signals produced to Kafka
- [ ] Aggregator consumes and persists signals
- [ ] REST API returns signals
- [ ] Query works: `SELECT * FROM signals ORDER BY timestamp DESC LIMIT 100`
- [ ] System runs stably for 24 hours
- [ ] Memory usage < 600 MB (both services combined)

---

## Storage Estimates

**Phase 1 (3 months):**
- Ticks: ~7.5 GB

**Phase 2 (3 months):**
- Signals: ~1.3 GB

**Total:** ~9 GB (laptop-friendly!)

---

## Phase 3 Preview

**What comes next:**

1. **Python Backtester (FastAPI + Pandas)**
   - Backtest strategies on historical data
   - Calculate performance metrics (Sharpe ratio, win rate, etc.)
   - Compare strategies

2. **React Frontend**
   - Live signal feed (WebSocket)
   - Strategy performance leaderboard
   - Interactive charts (price + signals overlaid)
   - Backtest UI

---

## Questions?

Track issues and learnings as you build!

**Common Questions:**

**Q: Why not use Spring Data JPA?**  
A: QuestDB doesn't support transactions. JdbcTemplate is simpler and works perfectly.

**Q: Can I add more strategies later?**  
A: Yes! Just add a new class implementing `TradingStrategy`, Spring auto-discovers it.

**Q: How do I backtest a strategy?**  
A: Phase 3 will add Python backtester. For now, strategies run in real-time only.

**Q: Can strategies access each other's signals?**  
A: Not directly. Strategies are independent. Phase 3 aggregator can combine signals (ensemble).

---

## Resources

- **Phase 1 Docs:** `/docs/phase-1/` (data pipeline foundation)
- **Phase 2 Docs:** `/docs/phase-2/` (this folder)
- **Actual Code:** `/strategy-engine/` and `/signal-aggregator/` (to be created)

---

## Next Steps

1. Read `PHASE-2-OVERVIEW.md` (high-level understanding)
2. Read concepts in order (deep understanding)
3. Follow `tasks/TASK-LIST.md` step-by-step (implementation)
4. Refer to guides when needed (detailed help)

**Let's build Phase 2!** 🚀
