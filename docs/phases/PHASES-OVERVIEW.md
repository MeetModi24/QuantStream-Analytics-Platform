# QuantStream - Implementation Phases

## Core Principle: Incremental, No Throwaway Code

Each phase **builds on** the previous phase. We **never** throw away code and start over.

---

## Phase 1: Data Pipeline Foundation

**Goal:** Get data flowing from generator → Kafka → QuestDB

**What We Build:**
1. Docker Compose setup (Kafka + QuestDB)
2. Market Data Generator (10 tokens, simple price generation)
3. Database Consumer (writes ticks to QuestDB)

**What We Learn:**
- Kafka producer/consumer basics
- QuestDB schema and queries
- Spring Boot project structure

**End State:**
- Prices flowing continuously
- Can query: `SELECT * FROM ticks WHERE symbol='BTC'`

**Duration:** ~1 week

---

## Phase 2: Add Aggregation

**Goal:** Calculate OHLC candles using Kafka Streams

**What We Add:**
1. New service: Aggregator (Kafka Streams)
2. New Kafka topic: `candles-1m`
3. Extend Database Consumer to write candles

**What Stays the Same:**
- Generator (unchanged, still running)
- Database Consumer ticks path (still writes ticks)

**What We Learn:**
- Kafka Streams windowed aggregations
- Tumbling windows
- OHLC calculation logic
- State management with RocksDB

**End State:**
- Everything from Phase 1 still works
- PLUS: Candles being calculated
- Can query: `SELECT * FROM candles_1m WHERE symbol='BTC'`

**Duration:** ~1 week

---

## Phase 3: Add API Layer

**Goal:** Expose data via REST and WebSocket

**What We Add:**
1. New service: API Gateway
2. REST endpoints for historical data
3. WebSocket endpoint for real-time updates
4. WebSocket publisher (consumes from Kafka, pushes to browsers)

**What Stays the Same:**
- All Phase 1 & 2 services (unchanged)

**What We Learn:**
- Spring Boot REST API
- Spring WebSocket + STOMP
- WebSocket subscriptions
- Querying QuestDB from Java

**End State:**
- Everything from Phases 1 & 2 still works
- PLUS: Can query REST API
- PLUS: Can subscribe to WebSocket

**Duration:** ~1 week

---

## Phase 4: Add Frontend

**Goal:** Visualize data in browser

**What We Build:**
1. React application
2. Candlestick charts (Lightweight Charts)
3. WebSocket client (STOMP)
4. Real-time chart updates

**What Stays the Same:**
- All backend services (unchanged)

**What We Learn:**
- React with TypeScript
- WebSocket client setup
- Lightweight Charts library
- Real-time UI updates

**End State:**
- Everything from Phases 1-3 still works
- PLUS: Live dashboard with charts

**Duration:** ~1 week

---

## Phase 5: Add Strategies

**Goal:** Calculate technical indicators and generate trading signals

**What We Add:**
1. New service: Strategy Evaluator
2. New Kafka topic: `strategy-signals`
3. Technical indicators (RSI, MACD, MA)
4. Signal generation logic
5. Extend Database Consumer for signals
6. Strategy leaderboard in frontend

**What Stays the Same:**
- All previous services (unchanged)

**What We Learn:**
- ta4j library for indicators
- Strategy logic implementation
- Performance tracking
- Multiple parallel strategies

**End State:**
- Everything from Phases 1-4 still works
- PLUS: Strategies generating signals
- PLUS: Leaderboard showing strategy performance

**Duration:** ~2 weeks

---

## Phase 6: Polish & Deploy

**Goal:** Production-ready system

**What We Do:**
1. Add error handling
2. Add logging and monitoring
3. Performance optimization
4. Documentation
5. Optional: Deploy to cloud

**What Stays the Same:**
- Core functionality (unchanged, just polished)

**What We Learn:**
- Production best practices
- Deployment strategies
- Monitoring and debugging

**Duration:** ~1 week

---

## Total Timeline: ~7-8 Weeks

**Breakdown:**
- Phase 1: 1 week
- Phase 2: 1 week
- Phase 3: 1 week
- Phase 4: 1 week
- Phase 5: 2 weeks
- Phase 6: 1 week

**Flexibility:** Can go faster or slower based on your pace

---

## Key Principles

### 1. Incremental Development

Each phase **extends** the system, never replaces.

**Example:**
```
Phase 1: Generator → Kafka → Database
Phase 2: Generator → Kafka → Database (still running)
                           ↘ Aggregator (NEW)
Phase 3: All Phase 2 components (still running)
                           ↓ API Gateway (NEW)
```

### 2. Always Working System

After each phase, the system **fully works**:
- Phase 1 complete → Can query ticks
- Phase 2 complete → Can query ticks AND candles
- Phase 3 complete → Can query via API
- Phase 4 complete → Can view in browser
- Phase 5 complete → Can see strategies

Never in a "half-broken" state.

### 3. Test Each Phase

Before moving to next phase:
1. Run the system
2. Verify data flows correctly
3. Query the database
4. Check logs

Don't move forward with bugs.

### 4. Small, Understandable Steps

Each phase introduces **1-2 new concepts**:
- Phase 1: Kafka basics
- Phase 2: Kafka Streams
- Phase 3: REST + WebSocket
- Phase 4: React + Charts
- Phase 5: Technical indicators

Not overwhelming.

---

## Next Steps

1. **Read understanding docs** (`docs/understanding/`)
2. **Read Phase 1 details** (`docs/phases/PHASE-1.md`)
3. **Set up environment** (Java, Docker, IDE)
4. **Start coding Phase 1**

---

## Questions?

Save questions in `docs/QUESTIONS.md` as you go.
