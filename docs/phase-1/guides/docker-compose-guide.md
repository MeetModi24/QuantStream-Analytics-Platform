# Docker Compose Setup Guide

## What is Docker Compose?

**Docker Compose** is a tool for defining and running multi-container Docker applications.

**Think of it as:** A configuration file that spins up all your infrastructure (Kafka, QuestDB, etc.) with one command.

---

## Why Use Docker Compose for Development?

### Without Docker Compose

**Manual setup:**

```bash
# Start Zookeeper
docker run -d --name zookeeper -p 2181:2181 zookeeper:3.8

# Start Kafka
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  confluentinc/cp-kafka:latest

# Start QuestDB
docker run -d --name questdb -p 9000:9000 -p 8812:8812 \
  questdb/questdb:latest

# Start Kafka UI
docker run -d --name kafka-ui -p 8080:8080 \
  -e KAFKA_CLUSTERS_0_NAME=local \
  -e KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=kafka:9092 \
  provectuslabs/kafka-ui:latest
```

**Problems:**
- Must remember all commands
- Must start in correct order (Zookeeper before Kafka)
- Must configure networking manually
- Hard to reproduce on another machine

### With Docker Compose

**One file:** `docker-compose.yml`

**One command:**
```bash
docker-compose up -d
```

**Result:** All 4 services start automatically, networked together, with correct dependencies.

---

## Our docker-compose.yml File

Create this file in the **root** of your project (`/Users/mhiteshkumar/QuantStream/docker-compose.yml`):

```yaml
version: '3.8'

services:
  # 1. Zookeeper (Kafka metadata manager)
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    networks:
      - quantstream-network

  # 2. Kafka (Message broker)
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true'
    networks:
      - quantstream-network

  # 3. QuestDB (Time-series database)
  questdb:
    image: questdb/questdb:7.3.10
    container_name: questdb
    ports:
      - "9000:9000"  # Web console
      - "8812:8812"  # PostgreSQL wire protocol
      - "9009:9009"  # InfluxDB line protocol (not used in Phase 1)
    volumes:
      - questdb-data:/var/lib/questdb
    networks:
      - quantstream-network

  # 4. Kafka UI (Web interface for Kafka)
  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    container_name: kafka-ui
    depends_on:
      - kafka
    ports:
      - "8080:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
      KAFKA_CLUSTERS_0_ZOOKEEPER: zookeeper:2181
    networks:
      - quantstream-network

# Network for all services to communicate
networks:
  quantstream-network:
    driver: bridge

# Persistent storage for QuestDB
volumes:
  questdb-data:
```

---

## Understanding Each Service

### 1. Zookeeper

**What it does:**
- Manages Kafka cluster metadata
- Tracks which brokers are alive
- Stores topic configurations
- Coordinates leader election

**Why we need it:**
- Kafka requires Zookeeper to run
- (Note: Kafka 3.x can run without Zookeeper using KRaft mode, but 7.5.0 still uses Zookeeper by default)

**Configuration:**

```yaml
zookeeper:
  image: confluentinc/cp-zookeeper:7.5.0
  container_name: zookeeper
  ports:
    - "2181:2181"  # Port for Kafka to connect
  environment:
    ZOOKEEPER_CLIENT_PORT: 2181      # Port Zookeeper listens on
    ZOOKEEPER_TICK_TIME: 2000        # Time unit (2 seconds)
```

**Key details:**
- **Port 2181:** Kafka connects to Zookeeper on this port
- **ZOOKEEPER_CLIENT_PORT:** Must match the port mapping
- **ZOOKEEPER_TICK_TIME:** Internal heartbeat interval (2000ms)

**You won't directly interact with Zookeeper** (it's infrastructure for Kafka).

### 2. Kafka

**What it does:**
- Message broker (stores and routes messages)
- Receives messages from producers
- Delivers messages to consumers
- Stores messages on disk (durability)

**Why we need it:**
- Decouples generator from consumers
- Buffers messages
- Enables multiple consumers

**Configuration:**

```yaml
kafka:
  image: confluentinc/cp-kafka:7.5.0
  container_name: kafka
  depends_on:
    - zookeeper  # Wait for Zookeeper to start first
  ports:
    - "9092:9092"  # Port for producers/consumers to connect
  environment:
    KAFKA_BROKER_ID: 1  # Unique ID for this broker
    KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181  # Connect to Zookeeper
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT
    KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1  # Single broker, no replication
    KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true'    # Auto-create topics on first message
```

**Key details:**

**Port 9092:**
- **Your Java applications connect here:** `localhost:9092`
- Producer sends messages to `localhost:9092`
- Consumer reads messages from `localhost:9092`

**KAFKA_ADVERTISED_LISTENERS:**
```yaml
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
```

This tells Kafka: "Tell clients to connect to `localhost:9092`"

**Why `localhost`?**
- Your Java apps run on host machine (not inside Docker)
- They access Kafka via `localhost:9092`

**If this was `kafka:9092`:**
- Java app would try to connect to hostname `kafka`
- Hostname `kafka` doesn't exist outside Docker
- Connection would fail

**KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true':**
- When generator sends to topic "market-data", Kafka creates it automatically
- You don't need to manually create topics
- Default 1 partition per topic

**KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1:**
- Internal topic for consumer offsets
- `1` means no replication (single broker)
- Production would use `3` (3 copies for fault tolerance)

### 3. QuestDB

**What it does:**
- Time-series database
- Stores tick data (symbol, price, volume, timestamp)
- Provides SQL query interface
- Web console for exploring data

**Why we need it:**
- Persistent storage (Kafka only keeps messages for 5 minutes)
- Historical queries ("Show me AAPL prices from yesterday")
- Fast time-range queries

**Configuration:**

```yaml
questdb:
  image: questdb/questdb:7.3.10
  container_name: questdb
  ports:
    - "9000:9000"  # Web console (browser UI)
    - "8812:8812"  # PostgreSQL wire protocol (JDBC connection)
    - "9009:9009"  # InfluxDB line protocol (not used in Phase 1)
  volumes:
    - questdb-data:/var/lib/questdb  # Persist data on host
```

**Key details:**

**Port 9000:** Web console
- Open browser: `http://localhost:9000`
- SQL editor
- View tables
- Run queries

**Port 8812:** PostgreSQL wire protocol
- **Your Java app connects here**
- JDBC URL: `jdbc:postgresql://localhost:8812/questdb`
- Uses PostgreSQL driver (QuestDB is compatible)

**Port 9009:** InfluxDB line protocol
- Alternative ingestion method (not using in Phase 1)
- Used for high-speed bulk imports

**volumes:**
```yaml
volumes:
  - questdb-data:/var/lib/questdb
```

**What this does:**
- Data stored inside container at `/var/lib/questdb`
- Mapped to Docker volume `questdb-data` on host
- **Data persists** even if container is deleted
- If you run `docker-compose down` and `docker-compose up`, data is still there

**Without volumes:**
- Data deleted when container is removed
- Every restart = fresh database

### 4. Kafka UI

**What it does:**
- Web interface for Kafka
- View topics, messages, consumer groups
- Debug Kafka issues
- Monitor message flow

**Why we need it:**
- Makes development easier
- See messages flowing through Kafka
- Verify topics are created
- Check consumer lag

**Configuration:**

```yaml
kafka-ui:
  image: provectuslabs/kafka-ui:latest
  container_name: kafka-ui
  depends_on:
    - kafka  # Wait for Kafka to start first
  ports:
    - "8080:8080"  # Web interface
  environment:
    KAFKA_CLUSTERS_0_NAME: local             # Display name
    KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092  # Connect to Kafka
    KAFKA_CLUSTERS_0_ZOOKEEPER: zookeeper:2181     # Connect to Zookeeper
```

**Key details:**

**Port 8080:** Web interface
- Open browser: `http://localhost:8080`
- View topics
- View messages in real-time
- Monitor consumer groups

**KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092**
- **Inside Docker network:** `kafka` is resolvable
- Kafka UI container can reach Kafka via `kafka:9092`

**Note the difference:**
- **Your Java app:** `localhost:9092` (outside Docker)
- **Kafka UI container:** `kafka:9092` (inside Docker)

---

## Docker Networking Explained

### The quantstream-network

```yaml
networks:
  quantstream-network:
    driver: bridge
```

**What this creates:**
- Virtual network inside Docker
- All services connected to this network can talk to each other
- Hostname = service name

**Example:**

Inside `kafka` container:
```bash
ping zookeeper  # Works! Resolves to zookeeper container IP
ping questdb    # Works! Resolves to questdb container IP
```

Inside `zookeeper` container:
```bash
ping kafka      # Works! Resolves to kafka container IP
```

**From host machine (your Mac):**
```bash
ping kafka      # Doesn't work (kafka hostname only exists in Docker)
```

You access services via `localhost:<port>`:
- `localhost:9092` → Kafka
- `localhost:9000` → QuestDB
- `localhost:8080` → Kafka UI

---

## Using Docker Compose

### Start All Services

```bash
cd /Users/mhiteshkumar/QuantStream
docker-compose up -d
```

**What happens:**
1. Downloads images (first time only, ~2-5 minutes)
2. Creates `quantstream-network`
3. Creates `questdb-data` volume
4. Starts Zookeeper
5. Waits for Zookeeper to be ready
6. Starts Kafka
7. Starts QuestDB
8. Starts Kafka UI

**Output:**
```
Creating network "quantstream_quantstream-network" with driver "bridge"
Creating volume "quantstream_questdb-data" with default driver
Creating zookeeper ... done
Creating questdb   ... done
Creating kafka     ... done
Creating kafka-ui  ... done
```

**`-d` flag:** "detached" mode (runs in background)

### Check Running Services

```bash
docker-compose ps
```

**Output:**
```
    Name                  Command               State                    Ports
-----------------------------------------------------------------------------------------------
kafka        /etc/confluent/docker/run        Up      0.0.0.0:9092->9092/tcp
kafka-ui     java -jar kafka-ui-api.jar       Up      0.0.0.0:8080->8080/tcp
questdb      /app/bin/java ...                Up      0.0.0.0:8812->8812/tcp, 0.0.0.0:9000->9000/tcp
zookeeper    /etc/confluent/docker/run        Up      0.0.0.0:2181->2181/tcp
```

All services should show `State: Up`.

### View Logs

**All services:**
```bash
docker-compose logs -f
```

**One service:**
```bash
docker-compose logs -f kafka
```

**Useful for debugging:**
- See Kafka startup messages
- Check for errors
- Verify topics are created

**Exit logs:** `Ctrl+C`

### Stop All Services

```bash
docker-compose down
```

**What happens:**
- Stops all containers
- Removes containers
- Removes network
- **Keeps volumes** (data persists)

### Stop and Remove Data

```bash
docker-compose down -v
```

**`-v` flag:** Remove volumes too

**Warning:** This deletes all QuestDB data!

### Restart One Service

```bash
docker-compose restart kafka
```

Useful if one service crashes.

### Rebuild After Config Changes

If you change `docker-compose.yml`:

```bash
docker-compose down
docker-compose up -d
```

---

## Verifying Setup

### 1. Check Zookeeper

```bash
docker-compose logs zookeeper | grep "binding to port"
```

**Should see:**
```
zookeeper | binding to port 0.0.0.0/0.0.0.0:2181
```

### 2. Check Kafka

```bash
docker-compose logs kafka | grep "started (kafka.server.KafkaServer)"
```

**Should see:**
```
kafka | [KafkaServer id=1] started (kafka.server.KafkaServer)
```

### 3. Check QuestDB

Open browser: `http://localhost:9000`

**Should see:**
- QuestDB web console
- SQL editor

**Test query:**
```sql
SELECT 1;
```

**Should return:** `1`

### 4. Check Kafka UI

Open browser: `http://localhost:8080`

**Should see:**
- Kafka UI dashboard
- Cluster "local"
- No topics yet (we haven't created any)

---

## Common Issues

### Issue 1: Port Already in Use

**Error:**
```
Error starting userland proxy: listen tcp4 0.0.0.0:9092: bind: address already in use
```

**Cause:** Another process is using port 9092

**Fix:**

**Option 1:** Find and kill the process
```bash
lsof -ti:9092 | xargs kill -9
```

**Option 2:** Change port in `docker-compose.yml`
```yaml
ports:
  - "9093:9092"  # Use 9093 on host, 9092 inside container
```

Then connect to `localhost:9093` in your Java app.

### Issue 2: Kafka Can't Connect to Zookeeper

**Error in Kafka logs:**
```
WARN [ZooKeeperClient-] Unable to connect to ZooKeeper
```

**Cause:** Zookeeper not ready yet

**Fix:** Wait 30 seconds and check again
```bash
docker-compose logs kafka -f
```

If still failing after 1 minute:
```bash
docker-compose restart kafka
```

### Issue 3: QuestDB Web Console Not Loading

**Error:** `http://localhost:9000` doesn't load

**Cause 1:** QuestDB still starting (takes 10-15 seconds)

**Fix:** Wait and refresh

**Cause 2:** Port conflict

**Check:**
```bash
lsof -ti:9000
```

**Fix:** Kill conflicting process or change port

### Issue 4: Docker Daemon Not Running

**Error:**
```
Cannot connect to the Docker daemon. Is the docker daemon running?
```

**Fix:** Start Docker Desktop

### Issue 5: Out of Disk Space

**Error:**
```
no space left on device
```

**Check Docker disk usage:**
```bash
docker system df
```

**Clean up:**
```bash
docker system prune -a  # Removes all unused containers/images
```

---

## Useful Commands

### View Resource Usage

```bash
docker stats
```

**Shows:**
- CPU usage per container
- Memory usage
- Network I/O

**Exit:** `Ctrl+C`

### Execute Command in Container

```bash
docker exec -it kafka bash
```

Opens shell inside Kafka container.

**Example: List Kafka topics**
```bash
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### Clear All Data and Restart Fresh

```bash
docker-compose down -v          # Stop and remove volumes
docker system prune -f          # Clean up Docker
docker-compose up -d            # Start fresh
```

---

## Development Workflow

### Starting Your Day

```bash
cd /Users/mhiteshkumar/QuantStream
docker-compose up -d
docker-compose logs -f  # Watch logs until all services are up
# Wait for "started" messages from all services
# Ctrl+C to exit logs
```

**Verify:**
1. Open `http://localhost:9000` (QuestDB)
2. Open `http://localhost:8080` (Kafka UI)

### Ending Your Day

```bash
docker-compose down
```

**Note:** Data persists (volumes not removed)

### When Debugging

**Tail logs:**
```bash
docker-compose logs -f
```

**Check specific service:**
```bash
docker-compose logs kafka
```

**Restart problematic service:**
```bash
docker-compose restart kafka
```

---

## Port Reference

| Service   | Port  | Purpose                          | Access                     |
|-----------|-------|----------------------------------|----------------------------|
| Zookeeper | 2181  | Kafka metadata management        | Internal only              |
| Kafka     | 9092  | Producer/Consumer connections    | `localhost:9092`           |
| QuestDB   | 9000  | Web console                      | `http://localhost:9000`    |
| QuestDB   | 8812  | PostgreSQL wire protocol (JDBC)  | `localhost:8812`           |
| QuestDB   | 9009  | InfluxDB line protocol           | Not used in Phase 1        |
| Kafka UI  | 8080  | Web interface                    | `http://localhost:8080`    |

---

## What's Next?

After running `docker-compose up -d` and verifying all services are running, you're ready to:

1. **Create the Market Data Generator project** (Task 4)
   - Spring Boot application
   - Sends messages to `localhost:9092`
   - Topic: `market-data`

2. **Create the Database Consumer project** (Task 5)
   - Spring Boot application
   - Consumes from `localhost:9092`
   - Writes to QuestDB via `localhost:8812`

3. **Watch messages flow** (Task 6)
   - Kafka UI: `http://localhost:8080`
   - QuestDB Console: `http://localhost:9000`

---

## Summary

**Docker Compose:**
- One file defines entire infrastructure
- One command starts everything
- Handles dependencies (Zookeeper before Kafka)
- Creates network for inter-service communication
- Persists data in volumes

**Services:**
1. **Zookeeper:** Kafka metadata manager
2. **Kafka:** Message broker
3. **QuestDB:** Time-series database
4. **Kafka UI:** Web interface for Kafka

**Commands:**
- `docker-compose up -d`: Start all services
- `docker-compose ps`: Check status
- `docker-compose logs -f`: View logs
- `docker-compose down`: Stop all services
- `docker-compose down -v`: Stop and remove data

**Next:** Create Market Data Generator Spring Boot project!
