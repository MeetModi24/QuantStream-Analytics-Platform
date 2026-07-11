# QuantStream System Architecture

## Overview

QuantStream is a **real-time trading strategy analytics platform** built using:
- **Spring Boot** microservices
- **Apache Kafka** for message streaming
- **Kafka Streams** for real-time aggregation
- **QuestDB** for time-series storage
- **WebSocket** for real-time frontend updates
- **React** dashboard with candlestick charts

---

## System Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  LAYER 1: DATA GENERATION                                   │
│  ┌────────────────────────────────────────────────────┐    │
│  │  Market Data Generator (Spring Boot)               │    │
│  │  - Generates realistic prices for 1,000 tokens     │    │
│  │  - Updates every second (1,000 msg/sec)            │    │
│  │  - Produces to Kafka "market-data" topic           │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                         ↓ (Kafka Producer)
┌─────────────────────────────────────────────────────────────┐
│  LAYER 2: MESSAGE BROKER (Kafka)                            │
│  ┌────────────────────────────────────────────────────┐    │
│  │  Topic: market-data                                 │    │
│  │  - 10 partitions (parallel processing)             │    │
│  │  - 1,000 messages/sec                              │    │
│  │  - Retention: 5 minutes                            │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
          ↓                          ↓                      ↓
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│  Database        │  │  Aggregator      │  │  Strategy        │
│  Consumer        │  │  (Kafka Streams) │  │  Evaluator       │
│  (Spring Boot)   │  │  (Spring Boot)   │  │  (Spring Boot)   │
│                  │  │                  │  │                  │
│  Writes raw      │  │  Calculates      │  │  Calculates      │
│  ticks to        │  │  1m, 5m candles  │  │  indicators      │
│  QuestDB         │  │                  │  │  (RSI, MACD)     │
└──────────────────┘  └──────────────────┘  └──────────────────┘
                               ↓                       ↓
                      ┌─────────────────┐    ┌─────────────────┐
                      │ Kafka Topic:    │    │ Kafka Topic:    │
                      │ candles-1m      │    │ strategy-signals│
                      └─────────────────┘    └─────────────────┘
                               ↓                       ↓
                      ┌──────────────────────────────────────┐
                      │  Database Consumer (extended)        │
                      │  Writes candles & signals to QuestDB │
                      └──────────────────────────────────────┘
                                       ↓
┌─────────────────────────────────────────────────────────────┐
│  LAYER 3: STORAGE (QuestDB)                                 │
│  ┌────────────────────────────────────────────────────┐    │
│  │  Tables:                                            │    │
│  │  - ticks (raw price data, 1,000 rows/sec)         │    │
│  │  - candles_1m (1-min OHLC, ~17 rows/sec)          │    │
│  │  - candles_5m (5-min OHLC, ~3 rows/sec)           │    │
│  │  - strategy_signals (trading signals)              │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                               ↓ (SQL Queries)
┌─────────────────────────────────────────────────────────────┐
│  LAYER 4: API GATEWAY (Spring Boot)                         │
│  ┌────────────────────────────────────────────────────┐    │
│  │  REST API:                                          │    │
│  │  - GET /api/tokens (list all tokens)               │    │
│  │  - GET /api/tokens/{symbol}/candles (historical)   │    │
│  │  - GET /api/strategies (list strategies)           │    │
│  │  - GET /api/strategies/{id}/performance            │    │
│  │                                                     │    │
│  │  WebSocket:                                         │    │
│  │  - /ws (connection endpoint)                       │    │
│  │  - /topic/{symbol} (real-time price updates)       │    │
│  │  - /topic/leaderboard (strategy rankings)          │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                         ↓ (HTTP / WebSocket)
┌─────────────────────────────────────────────────────────────┐
│  LAYER 5: FRONTEND (React)                                  │
│  ┌────────────────────────────────────────────────────┐    │
│  │  - Candlestick charts (Lightweight Charts)         │    │
│  │  - Real-time price updates (WebSocket)             │    │
│  │  - Strategy leaderboard (PnL, win rate, Sharpe)    │    │
│  │  - Token watchlist                                 │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

## Services

### 1. Market Data Generator
- **Language:** Java 21 + Spring Boot
- **Purpose:** Generate realistic price data
- **Output:** Kafka topic `market-data`
- **Rate:** 1,000 messages/second (1,000 tokens × 1 update/sec)
- **Algorithm:** Geometric Brownian Motion (realistic price movements)

### 2. Database Consumer
- **Language:** Java 21 + Spring Boot
- **Purpose:** Write data to QuestDB
- **Input:** Kafka topics `market-data`, `candles-1m`, `strategy-signals`
- **Database:** QuestDB (time-series)

### 3. Aggregator (Kafka Streams)
- **Language:** Java 21 + Spring Boot + Kafka Streams
- **Purpose:** Calculate OHLC candles
- **Input:** Kafka topic `market-data`
- **Output:** Kafka topics `candles-1m`, `candles-5m`
- **Windows:** 1-minute, 5-minute tumbling windows

### 4. Strategy Evaluator
- **Language:** Java 21 + Spring Boot + ta4j
- **Purpose:** Calculate technical indicators and generate signals
- **Input:** Kafka topic `candles-1m`
- **Output:** Kafka topic `strategy-signals`
- **Indicators:** RSI, MACD, Moving Averages, Bollinger Bands

### 5. API Gateway
- **Language:** Java 21 + Spring Boot
- **Purpose:** Expose REST API and WebSocket
- **REST:** Historical data queries
- **WebSocket:** Real-time updates to frontend
- **Database:** Queries QuestDB

### 6. Frontend Dashboard
- **Language:** TypeScript + React 18
- **Purpose:** Visualize data
- **Charts:** Lightweight Charts (TradingView)
- **Updates:** WebSocket (STOMP protocol)

---

## Data Flow

### Example: BTC Price Update

**1. Generation (t=0ms)**
```java
// Generator creates price update
Tick tick = new Tick("BTC", 50000.00, 1000, Instant.now());
kafkaTemplate.send("market-data", "BTC", tick);
```

**2. Kafka (t=5ms)**
```
Topic: market-data
Partition: 2 (based on key "BTC")
Message: {"symbol":"BTC","price":50000.00,"volume":1000,"timestamp":"2024-01-01T14:00:00Z"}
```

**3. Consumers (t=10ms)**

**Database Consumer:**
```sql
INSERT INTO ticks VALUES ('BTC', 50000.00, 1000, '2024-01-01T14:00:00Z');
```

**Aggregator (Kafka Streams):**
```java
// Updates 1-minute window state
currentCandle.updateHigh(50000.00);
currentCandle.updateLow(50000.00);
currentCandle.setClose(50000.00);
```

**Strategy Evaluator:**
```java
// Calculate RSI on latest candle
double rsi = calculateRSI(candles, 14);
if (rsi < 30) {
    Signal signal = new Signal("BUY", "BTC", rsi);
    kafkaTemplate.send("strategy-signals", signal);
}
```

**4. Window Closes (t=60000ms = 1 minute)**
```java
// Aggregator emits completed candle
OHLCCandle candle = new OHLCCandle(
    "BTC",
    50000.00,  // open
    50100.00,  // high
    49900.00,  // low
    50050.00,  // close
    60000,     // volume
    "2024-01-01T14:00:00Z"
);
kafkaTemplate.send("candles-1m", candle);
```

**5. WebSocket (t=60010ms)**
```java
// API Gateway consumes candle and pushes to WebSocket
simpMessagingTemplate.convertAndSend("/topic/BTC", candle);
```

**6. Frontend (t=60020ms)**
```typescript
// Browser receives update
stompClient.subscribe('/topic/BTC', (message) => {
    const candle = JSON.parse(message.body);
    chart.update(candle);  // Add new candle to chart
});
```

**Total latency: ~100ms** (generation → display)

---

## Scaling Strategy

### Current (Development)
- 1,000 tokens
- 1,000 messages/sec
- Single instance of each service
- Runs on laptop

### Future (Production)
- 30,000 tokens
- 30,000 messages/sec (30x scale)
- Multiple instances:
  - 3-6 Aggregator instances
  - 5-10 Strategy Evaluator instances
- Each processes different partitions

**How it scales:**
```
Kafka Topic: market-data (30 partitions)

Aggregator Instance 1 → Partitions 0-9   (10K msg/sec)
Aggregator Instance 2 → Partitions 10-19 (10K msg/sec)
Aggregator Instance 3 → Partitions 20-29 (10K msg/sec)

Total: 30K msg/sec
```

---

## Technology Stack

### Backend
- **Java 21** (LTS)
- **Spring Boot 3.3+**
- **Apache Kafka 3.8+**
- **Kafka Streams 3.8+**
- **QuestDB 8.x** (time-series database)
- **ta4j** (technical analysis library)
- **Maven** (build tool)

### Frontend
- **React 18**
- **TypeScript 5**
- **Lightweight Charts** (TradingView)
- **STOMP.js** (WebSocket client)
- **Tailwind CSS** (styling)
- **Vite** (build tool)

### Infrastructure
- **Docker Compose** (local development)
- **Kafka UI** (topic visualization)
- **QuestDB Web Console** (query interface)

---

## Deployment

### Local Development (Free)
```
Docker Compose:
- Kafka (port 9092)
- Zookeeper (port 2181)
- Kafka UI (port 8080)
- QuestDB (port 9000, 9009)

Services (run locally):
- Data Generator (port 8081)
- Database Consumer (port 8082)
- Aggregator (port 8083)
- Strategy Evaluator (port 8084)
- API Gateway (port 8085)

Frontend (npm run dev):
- React Dev Server (port 5173)
```

### Production (Optional, Later)
- **Frontend:** Vercel (free tier)
- **Backend:** Fly.io (free tier + $10-20/month for larger instances)
- **Kafka:** Upstash or Confluent Cloud ($30-50/month)
- **QuestDB:** Self-hosted or QuestDB Cloud

---

## Key Design Decisions

### Why Kafka?
- **Decouples services** - each service independent
- **Handles high throughput** - 1M+ msg/sec capability
- **Durable** - messages persisted, can replay
- **Scalable** - add partitions and consumers

### Why Kafka Streams?
- **Stateful processing** - maintains windowed aggregations
- **Fault-tolerant** - state backed up to Kafka
- **Exactly-once** - no duplicate candles
- **Embedded** - runs in Spring Boot, no separate service

### Why QuestDB?
- **Fast ingestion** - 1.6M rows/sec (tested)
- **SQL interface** - familiar query language
- **Columnar storage** - 10x better compression
- **Time-based partitioning** - fast time-range queries

### Why WebSocket?
- **Real-time** - push updates instantly
- **Efficient** - persistent connection, no polling overhead
- **Bi-directional** - server push + client commands

---

## Next Steps

1. Read all understanding docs (`docs/understanding/`)
2. Review phase-by-phase implementation plan (`docs/phases/`)
3. Set up development environment
4. Start Phase 1: Data Pipeline

---

## Questions?

Save questions in `docs/QUESTIONS.md` as we go through the project.
