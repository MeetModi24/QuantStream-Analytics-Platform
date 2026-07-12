# Phase 1: Task List

**How to use this:** Work through tasks sequentially. Each task has a detailed guide in the `guides/` folder.

---

## Task 1: Environment Setup ⏱️ 1-2 hours

### What You're Doing
Installing all the tools needed to build Spring Boot applications and run Docker containers.

### Why This Matters
You can't write Java code without the JDK. You can't run Kafka without Docker. Maven builds our Spring Boot projects. The IDE makes development easier than using a text editor.

### Steps

**1.1 Install Java 21 JDK**

Java 21 is the latest Long-Term Support (LTS) version. Spring Boot 3.3 requires Java 17 or higher.

```bash
# Check if already installed
java -version

# Should show: openjdk version "21.x.x" or similar
# If not installed or version < 21, download from:
# https://adoptium.net/temurin/releases/?version=21
```

**Why Java 21?**
- LTS version (supported until 2029)
- Latest features (virtual threads, pattern matching)
- Spring Boot 3.3 fully supports it
- Modern, performant

**1.2 Install Maven 3.9+**

Maven is a build tool that downloads dependencies (like Spring Boot, Kafka libraries) and compiles your code.

```bash
# Check if installed
mvn -version

# Should show: Apache Maven 3.9.x or higher
# If not installed:
# macOS: brew install maven
# Windows: https://maven.apache.org/download.cgi
```

**Why Maven?**
- Standard build tool for Java projects
- Manages dependencies automatically (downloads JARs)
- Used by most Spring Boot projects
- Alternative: Gradle (we're using Maven for simplicity)

**1.3 Install Docker Desktop**

Docker lets you run Kafka and QuestDB in isolated containers without installing them directly on your machine.

```bash
# Check if installed
docker --version
docker-compose --version

# If not installed:
# Download from: https://www.docker.com/products/docker-desktop/
```

**Why Docker?**
- Kafka setup is complex (needs Zookeeper, specific configs)
- QuestDB needs specific runtime environment
- Docker gives you pre-configured, working versions
- Easy to start/stop/reset
- Same setup works on any machine (Mac, Windows, Linux)

**1.4 Install IntelliJ IDEA Community Edition (Recommended)**

While you can use any text editor, IntelliJ provides:
- Auto-completion (saves typing)
- Error detection (red squiggles before you run)
- Spring Boot integration (run/debug easily)
- Maven integration (builds automatically)

```
Download from: https://www.jetbrains.com/idea/download/
Choose: Community Edition (Free)
```

**Alternative:** VS Code with Java extensions (works but less integrated)

### Verification

Run these commands to verify everything works:

```bash
java -version      # Should show 21.x.x
mvn -version       # Should show 3.9.x or higher
docker --version   # Should show version number
docker ps          # Should show empty list (no containers running yet)
```

### If Something Fails
- Java: Make sure JAVA_HOME environment variable is set
- Maven: Make sure it's added to PATH
- Docker: Make sure Docker Desktop is running (icon in system tray)

**Questions?** Add to `/docs/QUESTIONS.md`

---

## Task 2: Understand Core Concepts ⏱️ 2-3 hours

### What You're Doing
Reading concept documents to understand Kafka, QuestDB, and GBM before you write code.

### Why This Matters
If you write code without understanding these concepts, you'll just be copying and pasting. When something breaks, you won't know how to fix it. Understanding first = faster development later.

### Reading Order

**2.1 Read: `concepts/01-why-kafka.md`**

This explains:
- Why we can't just write directly to database
- What problems Kafka solves
- How Kafka stores messages
- When to use Kafka vs direct database writes

**After reading, you should be able to answer:**
- What happens if the database is slow?
- Why do we need a message broker?
- What is a "topic"?
- What is a "partition"?

**2.2 Read: `concepts/02-questdb-basics.md`**

This explains:
- Why regular databases (MySQL, PostgreSQL) are too slow for time-series data
- How QuestDB stores data differently
- Why queries are 100x faster
- What "columnar storage" means

**After reading, you should be able to answer:**
- What makes QuestDB faster than MySQL for time-series data?
- What is "time-based partitioning"?
- When would you NOT use QuestDB?

**2.3 Read: `concepts/03-gbm-explained.md`**

This explains:
- Why random price changes look unrealistic
- How GBM creates realistic price movements
- What "drift" and "volatility" mean
- How to implement GBM in Java

**After reading, you should be able to answer:**
- What's the difference between random walk and GBM?
- What does "volatility" control in the price?
- Why can't GBM prices go negative?

**2.4 Read: `concepts/04-spring-boot-structure.md`**

This explains:
- How Spring Boot projects are organized
- What @SpringBootApplication does
- What @Component, @Service, @Configuration mean
- How dependency injection works

**After reading, you should be able to answer:**
- What's the difference between @Component and @Service?
- What is dependency injection?
- Where do you put configuration code?

### Verification

After reading, take 10 minutes to write answers to the questions above in your own words. If you can't answer them clearly, re-read that section.

**Don't rush this step.** Understanding these concepts saves hours of confusion later.

---

## Task 3: Set Up Docker Compose ⏱️ 30 minutes

### What You're Doing
Creating a `docker-compose.yml` file that starts Kafka, Zookeeper, QuestDB, and Kafka UI with one command.

### Why This Matters
Without Docker Compose, you'd need to:
1. Install Kafka manually (complex)
2. Configure Zookeeper manually (error-prone)
3. Install QuestDB manually
4. Remember all the startup commands
5. Make sure ports don't conflict

Docker Compose does all this with one file and one command: `docker-compose up`

### Steps

**3.1 Create docker-compose.yml**

Follow: `guides/docker-compose-guide.md`

This guide explains:
- Each service (Zookeeper, Kafka, QuestDB, Kafka UI)
- Why we need each one
- What each configuration parameter does
- Which ports each service uses

**3.2 Start Services**

```bash
# From QuantStream directory
docker-compose up -d

# -d means "detached" (runs in background)
```

**What happens:**
1. Docker downloads images (first time only, ~2-3 minutes)
2. Starts Zookeeper (Kafka's coordinator)
3. Starts Kafka broker
4. Starts QuestDB
5. Starts Kafka UI

**3.3 Verify Services**

```bash
# Check all containers are running
docker-compose ps

# Should show 4 containers: zookeeper, kafka, questdb, kafka-ui
# All should have status "Up"

# If any show "Exit" or "Restarting", check logs:
docker-compose logs <service-name>
```

**3.4 Test Kafka UI**

Open browser: http://localhost:8080

You should see:
- Kafka cluster name
- 0 topics (we haven't created any yet)
- 1 broker running

**3.5 Test QuestDB**

Open browser: http://localhost:9000

You should see:
- QuestDB web console
- SQL editor
- No tables yet (database is empty)

Try running: `SELECT 1;`
Should return: `1`

### Common Issues

**"Port 9092 already in use"**
- Another Kafka instance is running
- Solution: `docker ps` to find it, `docker stop <container-id>`

**"Cannot connect to Docker daemon"**
- Docker Desktop isn't running
- Solution: Open Docker Desktop application

**Kafka container keeps restarting**
- Not enough memory allocated to Docker
- Solution: Docker Desktop → Settings → Resources → Increase memory to 4GB

### Verification

All 4 services should be accessible:
- Kafka: Can connect (we'll test in next task)
- Kafka UI: http://localhost:8080 loads
- QuestDB: http://localhost:9000 loads
- QuestDB can run queries: `SELECT 1;` returns 1

---

## Task 4: Create Market Data Generator Project ⏱️ 2-3 hours

### What You're Doing
Creating a Spring Boot application that generates realistic prices for 10 tokens and sends them to Kafka.

### Why This Matters
This is the "source" of all data in our system. Without realistic data, we can't test aggregations, strategies, or visualizations properly. GBM ensures our prices look like real market data.

### Steps

**4.1 Generate Spring Boot Project**

Follow: `guides/generator-project-setup.md`

This guide walks through:
- Using Spring Initializr (https://start.spring.io)
- Selecting correct dependencies (Spring Kafka, Lombok)
- Importing into IntelliJ
- Understanding the generated structure

**4.2 Create Data Model**

Follow: `guides/generator-model-guide.md`

You'll create: `Tick.java`

This guide explains:
- Why we use a POJO (Plain Old Java Object)
- What @Data annotation does (Lombok generates getters/setters)
- How to serialize to JSON
- Why we use Instant for timestamps (not Date or Long)

**4.3 Configure Kafka Producer**

Follow: `guides/generator-kafka-config.md`

You'll create: `KafkaProducerConfig.java`

This guide explains:
- What a ProducerFactory does
- What serialization means (Java object → bytes → Kafka)
- Why we use JsonSerializer
- What each configuration property does (bootstrap.servers, key.serializer, etc.)

**4.4 Implement GBM Price Simulator**

Follow: `guides/generator-gbm-implementation.md`

You'll create: `PriceSimulator.java`

This guide explains:
- GBM formula step-by-step
- What each variable means (μ, σ, dt, dW)
- How to use Java's Random class for Gaussian numbers
- Why we use Math.exp() (exponential ensures positive prices)

**4.5 Implement Market Data Generator Service**

Follow: `guides/generator-service-implementation.md`

You'll create: `MarketDataGenerator.java`

This guide explains:
- How @Scheduled works (runs every second)
- How to manage multiple tokens (Map<String, PriceSimulator>)
- How to send messages to Kafka with KafkaTemplate
- How to handle errors (logging)

**4.6 Run and Test**

```bash
# Make sure Docker Compose is running
docker-compose ps

# Run the generator
cd data-generator
mvn spring-boot:run

# You should see logs:
# "Tick sent: AAPL -> $180.23"
# "Tick sent: BTC -> $50123.45"
# ...
```

**Verify in Kafka UI:**
1. Open http://localhost:8080
2. Navigate to Topics
3. You should see topic: `market-data`
4. Click on it → Messages tab
5. See live messages arriving

### Common Issues

**"Connection refused to localhost:9092"**
- Kafka isn't running
- Solution: `docker-compose ps` to check, `docker-compose up -d` to start

**"No serializer found for class Tick"**
- JsonSerializer not configured
- Solution: Check KafkaProducerConfig has JsonSerializer

**Prices don't look realistic (all same value)**
- GBM formula issue
- Solution: Check PriceSimulator implementation

### Verification

- [ ] Generator starts without errors
- [ ] Logs show "Tick sent: ..." every second for 10 tokens
- [ ] Kafka UI shows messages in `market-data` topic
- [ ] Messages are JSON format with: symbol, price, volume, timestamp
- [ ] Prices change realistically (not jumping $100 per second)

---

## Task 5: Create Database Consumer Project ⏱️ 2-3 hours

### What You're Doing
Creating a Spring Boot application that reads messages from Kafka and writes them to QuestDB.

### Why This Matters
Kafka is temporary storage (5 minutes retention). We need permanent storage for:
- Historical queries ("show me yesterday's prices")
- Analysis (calculating candles, indicators)
- Charts (displaying past data)

QuestDB is our permanent, queryable storage.

### Steps

**5.1 Generate Spring Boot Project**

Follow: `guides/consumer-project-setup.md`

Similar to generator, but different dependencies:
- Spring Kafka (consumer)
- PostgreSQL JDBC driver (QuestDB uses PostgreSQL wire protocol)

**5.2 Create Data Model**

Follow: `guides/consumer-model-guide.md`

You'll create: `Tick.java`

**Important:** This is the SAME model as generator, but in a different project. We duplicate it because these are separate microservices. They shouldn't share code (in real systems, this would be a shared library, but we're keeping it simple).

**5.3 Configure Kafka Consumer**

Follow: `guides/consumer-kafka-config.md`

You'll create: `KafkaConsumerConfig.java`

This guide explains:
- What a ConsumerFactory does
- Difference between producer and consumer config
- What "group ID" means (important!)
- How deserialization works (bytes → JSON → Java object)

**5.4 Configure QuestDB Connection**

Follow: `guides/consumer-questdb-config.md`

You'll create: `application.yml` with database config

This guide explains:
- QuestDB connection string format
- Why we use PostgreSQL JDBC driver (QuestDB compatibility)
- Port 8812 (QuestDB's PostgreSQL wire protocol port, not 9000 which is HTTP)
- Connection pooling

**5.5 Create QuestDB Repository**

Follow: `guides/consumer-repository-guide.md`

You'll create: `TickRepository.java`

This guide explains:
- Why we use JdbcTemplate (simple JDBC wrapper)
- How to write prepared statements (prevents SQL injection)
- How to batch inserts (faster than one-by-one)
- QuestDB-specific SQL syntax

**5.6 Implement Kafka Consumer**

Follow: `guides/consumer-implementation.md`

You'll create: `TickConsumer.java`

This guide explains:
- @KafkaListener annotation
- How Kafka delivers messages to your method
- Error handling (what if database is down?)
- Offset management (Kafka tracks which messages you've processed)

**5.7 Create QuestDB Table**

Before running the consumer, create the table:

```sql
-- Open QuestDB console: http://localhost:9000
-- Run this SQL:

CREATE TABLE ticks (
    symbol SYMBOL,
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY;
```

This guide explains:
- SYMBOL type (QuestDB-specific, optimized for strings with low cardinality)
- timestamp(timestamp) designation (tells QuestDB this is the time column)
- PARTITION BY DAY (creates separate partitions for each day's data)

**5.8 Run and Test**

```bash
# Make sure Generator is still running
# (if not: cd data-generator && mvn spring-boot:run)

# In another terminal:
cd database-consumer
mvn spring-boot:run

# You should see logs:
# "Consumed tick: AAPL -> $180.23"
# "Saved tick to database"
```

**Verify in QuestDB:**

```sql
-- QuestDB console: http://localhost:9000

SELECT COUNT(*) FROM ticks;
-- Should show increasing number

SELECT symbol, price, volume, timestamp 
FROM ticks 
ORDER BY timestamp DESC 
LIMIT 10;
-- Should show latest 10 ticks

SELECT symbol, COUNT(*) as count 
FROM ticks 
GROUP BY symbol;
-- Should show ~equal counts for each token
```

### Common Issues

**"Table 'ticks' does not exist"**
- Forgot to create table
- Solution: Run CREATE TABLE SQL in QuestDB console

**"Connection refused to localhost:8812"**
- QuestDB isn't running
- Solution: `docker-compose ps` to check

**Consumer receives messages but doesn't save**
- Check consumer logs for errors
- Test database connection separately

**Duplicate messages in database**
- Consumer restarted and Kafka offset reset
- Normal in development, can ignore

### Verification

- [ ] Consumer starts without errors
- [ ] Logs show "Consumed tick: ..." every second
- [ ] QuestDB table `ticks` has data
- [ ] Count increases every second (10 rows/second = 10 tokens)
- [ ] All 10 tokens have data
- [ ] Timestamps are recent (not 1970 or future dates)

---

## Task 6: Test End-to-End Flow ⏱️ 30 minutes

### What You're Doing
Verifying the entire pipeline works correctly by observing data flow from generator → Kafka → consumer → QuestDB.

### Why This Matters
Each component might work individually but fail when integrated. This test ensures:
- Data format matches (generator JSON = consumer expects JSON)
- Timing is correct (not too fast/slow)
- No data loss
- Performance is acceptable

### Steps

**6.1 Stop All Services**

```bash
# Stop Spring Boot apps
# Press Ctrl+C in both terminal windows

# Stop Docker
docker-compose down
```

**6.2 Clean Start**

```bash
# Start Docker (fresh state)
docker-compose up -d

# Wait 30 seconds for Kafka to fully start

# Verify services
docker-compose ps
# All should show "Up"
```

**6.3 Create QuestDB Table**

```sql
-- http://localhost:9000

DROP TABLE IF EXISTS ticks;

CREATE TABLE ticks (
    symbol SYMBOL,
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY;
```

**6.4 Start Generator**

```bash
cd data-generator
mvn clean spring-boot:run

# Watch for:
# - Application started on port XXXX
# - Tick sent: ... (every second, 10 lines)
```

**6.5 Start Consumer (30 seconds after generator)**

This delay ensures messages are in Kafka before consumer starts.

```bash
# In new terminal
cd database-consumer
mvn clean spring-boot:run

# Watch for:
# - Application started
# - Consumed tick: ... (every second, 10 lines)
# - No errors
```

**6.6 Monitor Kafka UI**

Open: http://localhost:8080 → Topics → market-data

You should see:
- Messages: increasing counter
- Consumer Groups: "database-group" with 1 member
- Lag: should be 0 or very low (consumer keeping up)

**What is "lag"?**
- Producer has sent 1000 messages
- Consumer has processed 950 messages
- Lag = 50 messages behind

Low lag = good (consumer keeping up)
High lag = problem (consumer too slow)

**6.7 Query QuestDB**

```sql
-- Run every 10 seconds and watch count increase

SELECT COUNT(*) FROM ticks;
-- Should increase by ~100 every 10 seconds (10 tokens × 1 msg/sec × 10 sec)

SELECT symbol, COUNT(*) as count 
FROM ticks 
GROUP BY symbol 
ORDER BY count DESC;
-- All symbols should have similar counts (~equal)

SELECT symbol, price, timestamp 
FROM ticks 
WHERE symbol = 'AAPL' 
ORDER BY timestamp DESC 
LIMIT 20;
-- Prices should be increasing/decreasing realistically (not jumping randomly)

SELECT 
    symbol,
    MIN(price) as min_price,
    MAX(price) as max_price,
    MAX(price) - MIN(price) as range
FROM ticks
GROUP BY symbol;
-- Ranges should be reasonable (not 0, not 100x the price)
```

**6.8 Performance Check**

```bash
# In generator terminal, check logs
# Should see: "Tick sent: ..." every second
# No "Error" or "Failed" messages

# In consumer terminal, check logs
# Should see: "Consumed tick: ..." every second
# No "Error" or "Failed to save" messages

# In Docker, check Kafka logs
docker-compose logs kafka | tail -50
# No ERROR or WARN messages about memory, disk, etc.
```

**6.9 Restart Test**

This tests durability (Kafka retains messages, consumer offsets saved).

```bash
# Stop consumer (Ctrl+C)
# Wait 30 seconds (generator keeps running)
# Start consumer again
mvn spring-boot:run

# Consumer should:
# - Resume from where it left off (not re-process old messages)
# - Start consuming immediately
# - No duplicate data in QuestDB
```

### Expected Results

After 5 minutes of running:

**Kafka:**
- Topic: market-data exists
- Messages: ~3,000 (10 tokens × 1 msg/sec × 300 seconds)
- Consumer group: 1 member, lag < 10

**QuestDB:**
- Rows in ticks table: ~3,000
- All 10 symbols present
- Prices change realistically
- No gaps in timestamps
- Latest timestamp is < 2 seconds old

**Logs:**
- No errors in generator
- No errors in consumer
- No errors in Docker logs

### Common Issues

**Consumer lag keeps increasing**
- Consumer can't keep up with producer
- Possible: Database writes too slow
- Solution: Check QuestDB CPU usage, increase batch size

**Duplicate messages in database**
- Consumer restarted without saving offset
- Normal in dev, can ignore or check consumer config

**Some symbols have no data**
- Generator not producing for those symbols
- Check generator logs, verify all 10 symbols being sent

**Timestamps are wrong (future or 1970)**
- Timezone issue or wrong timestamp format
- Check Tick model, ensure using Instant.now()

### Verification Checklist

- [ ] Generator runs for 5 minutes without errors
- [ ] Consumer runs for 5 minutes without errors
- [ ] Kafka UI shows messages flowing
- [ ] QuestDB has ~3,000 rows after 5 minutes
- [ ] All 10 tokens have data
- [ ] Prices look realistic (smooth changes, not random jumps)
- [ ] Consumer restart works (no duplicates)
- [ ] Kafka lag stays low (< 50)

---

## Phase 1 Complete! 🎉

You now have:
- ✅ Kafka cluster running
- ✅ Data generator producing realistic prices
- ✅ Consumer saving to QuestDB
- ✅ 10 messages/second flowing through system
- ✅ Queryable time-series database

**This is the foundation.** Phase 2 will add Kafka Streams aggregator on top of this.

### Before Moving to Phase 2

Take 30 minutes to:
1. Review the code you wrote
2. Make sure you understand each piece
3. Try modifying something (change a token, change GBM volatility)
4. Verify your changes work

**Questions?** Add to `/docs/QUESTIONS.md`

### Next: Phase 2 - Add Aggregation

In Phase 2, we'll add a Kafka Streams service that:
- Reads from `market-data` topic
- Calculates 1-minute OHLC candles
- Writes to `candles-1m` topic
- Consumer saves candles to QuestDB

**Everything from Phase 1 keeps running.** We're adding, not replacing.
