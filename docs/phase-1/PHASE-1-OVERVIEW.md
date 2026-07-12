# Phase 1: Data Pipeline Foundation

## Goal

Build the foundation: data flowing from Generator → Kafka → QuestDB

**This pipeline will never be thrown away.** All future phases extend it.

---

## What We're Building

```
Market Data Generator (Spring Boot)
    ↓ (produces to Kafka)
Kafka Topic: market-data
    ↓ (consumes from Kafka)
Database Consumer (Spring Boot)
    ↓ (writes to)
QuestDB Table: ticks
```

---

## End State

After Phase 1, you can:
1. Start Kafka + QuestDB with Docker Compose
2. Run data-generator service
3. Run database-consumer service
4. Open QuestDB console (http://localhost:9000)
5. Query: `SELECT * FROM ticks ORDER BY timestamp DESC LIMIT 10`
6. See live price updates for 10 tokens

---

## What You'll Learn

### Technical Concepts
1. **Kafka** - What it is, why we need it
2. **Kafka Producer** - How to send messages
3. **Kafka Consumer** - How to read messages
4. **QuestDB** - Time-series database basics
5. **Spring Boot** - Project structure, configuration

### Implementation Skills
1. How to set up Docker Compose
2. How to create Spring Boot microservices
3. How to serialize/deserialize JSON messages
4. How to write to time-series database
5. How to test message flow

### Domain Knowledge
1. **Tick data** - What is it, why it matters
2. **GBM (Geometric Brownian Motion)** - Realistic price generation
3. **Market data structure** - Symbol, price, volume, timestamp

---

## Timeline

**Estimated:** 3-5 days

- **Day 1:** Environment setup + understand concepts
- **Day 2:** Docker Compose + basic Spring Boot projects
- **Day 3:** Implement data generator with GBM
- **Day 4:** Implement database consumer
- **Day 5:** Test, debug, verify data flow

*(Can go faster or slower based on your pace)*

---

## Prerequisites

### Software to Install
- [ ] Java 21 JDK
- [ ] Maven 3.9+
- [ ] Docker Desktop
- [ ] IDE (IntelliJ IDEA Community recommended)
- [ ] Git

### Knowledge Assumed
- Basic Java (classes, methods, objects)
- Basic command line usage
- Basic understanding of databases (SQL)

### What We'll Teach
- Spring Boot (from scratch)
- Kafka (from scratch)
- QuestDB (from scratch)
- GBM algorithm (from scratch)

---

## Project Structure

```
QuantStream/
├── docker-compose.yml              # Kafka + QuestDB setup
├── data-generator/                 # Service 1
│   ├── pom.xml
│   └── src/main/java/
│       └── com/quantstream/generator/
│           ├── GeneratorApplication.java
│           ├── model/Tick.java
│           ├── config/KafkaProducerConfig.java
│           ├── service/MarketDataGenerator.java
│           └── service/PriceSimulator.java (GBM)
└── database-consumer/              # Service 2
    ├── pom.xml
    └── src/main/java/
        └── com/quantstream/consumer/
            ├── ConsumerApplication.java
            ├── model/Tick.java
            ├── config/KafkaConsumerConfig.java
            ├── consumer/TickConsumer.java
            └── repository/TickRepository.java
```

---

## Data Flow (Detailed)

### Step 1: Generator Creates Tick
```java
Tick tick = Tick.builder()
    .symbol("AAPL")
    .price(180.50)
    .volume(1000)
    .timestamp(Instant.now())
    .build();
```

### Step 2: Producer Sends to Kafka
```java
kafkaTemplate.send("market-data", "AAPL", tick);
```

### Step 3: Kafka Stores Message
```
Topic: market-data
Partition: 2 (based on key "AAPL")
Offset: 12345
Message: {"symbol":"AAPL","price":180.50,"volume":1000,"timestamp":"2024-07-12T10:30:45Z"}
```

### Step 4: Consumer Reads from Kafka
```java
@KafkaListener(topics = "market-data", groupId = "database-group")
public void consume(Tick tick) {
    // Process message
}
```

### Step 5: Consumer Writes to QuestDB
```sql
INSERT INTO ticks VALUES ('AAPL', 180.50, 1000, '2024-07-12T10:30:45Z');
```

### Step 6: Query QuestDB
```sql
SELECT symbol, price, volume, timestamp 
FROM ticks 
WHERE symbol = 'AAPL' 
ORDER BY timestamp DESC 
LIMIT 10;
```

---

## Configuration (Local Development)

### Tokens
**10 instruments:**
- **Stocks (5):** AAPL, MSFT, GOOGL, TSLA, AMZN
- **Crypto (5):** BTC, ETH, SOL, AVAX, MATIC

### Update Frequency
- 1 update per second per token
- **Total:** 10 messages/second

### Initial Prices
```
AAPL:  $180.00
MSFT:  $350.00
GOOGL: $140.00
TSLA:  $250.00
AMZN:  $3200.00
BTC:   $50000.00
ETH:   $3000.00
SOL:   $100.00
AVAX:  $35.00
MATIC: $0.80
```

### GBM Parameters
```
Drift (μ): 0.05 (5% annual growth)
Volatility (σ): 0.20 (20% annual volatility)
```

---

## Success Criteria

Phase 1 is complete when:

- [ ] Docker Compose starts Kafka + QuestDB
- [ ] Data generator runs without errors
- [ ] Database consumer runs without errors
- [ ] Kafka UI shows messages in `market-data` topic
- [ ] QuestDB table `ticks` has data
- [ ] Prices update every second for all 10 tokens
- [ ] Query returns recent ticks: `SELECT * FROM ticks ORDER BY timestamp DESC LIMIT 100`
- [ ] All services restart without losing data (Kafka retention works)

---

## Next Steps

1. **Read:** `concepts/` folder (organized learning)
2. **Follow:** `tasks/TASK-LIST.md` (step-by-step)
3. **Refer to:** `guides/` folder when implementing

---

## Questions?

Add questions to `/docs/QUESTIONS.md` as you go.
