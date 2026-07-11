# QuantStream Analytics Platform

Real-time trading strategy analytics platform built with Spring Boot, Kafka, and React.

---

## Project Status

**Current Phase:** Planning & Understanding  
**Next Step:** Phase 1 - Data Pipeline Foundation

---

## What is QuantStream?

A system that:
1. Generates realistic price data for 1,000 cryptocurrency tokens
2. Calculates OHLC candles (1-minute, 5-minute intervals)
3. Runs trading strategies with technical indicators (RSI, MACD, etc.)
4. Displays real-time data on a web dashboard
5. Tracks strategy performance (PnL, win rate, Sharpe ratio)

**Scale:** Handles 1,000 messages/second (scales to 30,000+ with partitioning)

---

## Architecture

```
Market Data Generator
         ↓
       Kafka
         ↓
  ┌──────┼──────┐
  ↓      ↓      ↓
Database  Aggregator  Strategy
Consumer  (Kafka      Evaluator
         Streams)     
         ↓
       QuestDB
         ↓
    API Gateway
         ↓
   React Dashboard
```

---

## Technology Stack

**Backend:**
- Java 21 + Spring Boot 3.3+
- Apache Kafka 3.8+ (message streaming)
- Kafka Streams (real-time aggregation)
- QuestDB (time-series database)
- ta4j (technical analysis)

**Frontend:**
- React 18 + TypeScript
- Lightweight Charts (TradingView)
- WebSocket (STOMP)

**Infrastructure:**
- Docker Compose (local development)

---

## Documentation

### Start Here
1. **[Understanding Docs](docs/understanding/)** - Learn core concepts FIRST
   - [01 - Kafka Basics](docs/understanding/01-kafka-basics.md)
   - [02 - Time-Series Databases](docs/understanding/02-time-series-databases.md)
   - [03 - OHLC Candles](docs/understanding/03-ohlc-candles.md)
   - [04 - Kafka Streams](docs/understanding/04-kafka-streams.md)
   - [05 - WebSocket](docs/understanding/05-websocket.md)
   - [06 - Technical Indicators](docs/understanding/06-technical-indicators.md)

2. **[Architecture](docs/architecture/ARCHITECTURE.md)** - System design

3. **[Phases](docs/phases/PHASES-OVERVIEW.md)** - Implementation plan

### Implementation (Coming Soon)
- Phase 1: Data Pipeline
- Phase 2: Aggregation
- Phase 3: API Layer
- Phase 4: Frontend
- Phase 5: Strategies
- Phase 6: Polish & Deploy

---

## Prerequisites

### Knowledge
- Java basics (classes, methods, OOP)
- Spring Boot basics (optional, we'll learn together)
- Basic understanding of REST APIs
- Basic understanding of databases

### Tools to Install
- **Java 21** (JDK)
- **Maven** (build tool)
- **Docker Desktop** (for Kafka + QuestDB)
- **Node.js 18+** (for frontend, later)
- **IDE** (IntelliJ IDEA recommended)

---

## Quick Start (Phase 1)

*Coming soon after understanding docs are reviewed*

---

## Learning Goals

### High-Level Design (HLD)
- Distributed system architecture
- Service decomposition
- Kafka topic design and partitioning
- Data flow and orchestration
- Scalability patterns

### Low-Level Design (LLD)
- Kafka Streams topology
- Time-series data modeling
- WebSocket connection management
- Windowed aggregation algorithms
- Concurrency and thread safety

### Domain Knowledge
- Trading systems
- Technical indicators
- Market data processing
- Real-time analytics

---

## Project Principles

1. **Incremental Development** - Each phase builds on the previous
2. **No Throwaway Code** - We never restart from scratch
3. **Always Working** - System works after each phase
4. **Learn by Doing** - Code with understanding, not copy-paste
5. **Free & Local** - Everything runs on your laptop

---

## Current Phase: Understanding

**TODO:**
- [ ] Read all understanding docs (`docs/understanding/`)
- [ ] Review architecture (`docs/architecture/ARCHITECTURE.md`)
- [ ] Review phases (`docs/phases/PHASES-OVERVIEW.md`)
- [ ] Ask questions (save in `docs/QUESTIONS.md`)
- [ ] Set up environment (Java, Docker, IDE)

---

## Questions?

Save questions in `docs/QUESTIONS.md` as you go through the docs.

---

## License

MIT License - Free to use for learning and portfolio projects.
