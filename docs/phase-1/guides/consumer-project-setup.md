# Database Consumer - Project Setup Guide

## What You're Building

**Database Consumer** is a Spring Boot application that:
1. Consumes market data from Kafka topic "market-data"
2. Writes each tick to QuestDB (time-series database)
3. Runs continuously, processing messages as they arrive
4. Stores data for real-time queries and historical analysis

**This is the "storage layer" of your system** — it persists the streaming data for later analysis.

---

## Prerequisites

Before starting, ensure you understand:

**QuestDB Concepts:**
- Read: `/Users/mhiteshkumar/QuantStream/docs/phase-1/concepts/questdb-concepts.md`
- Key concepts: Time-series database, PostgreSQL wire protocol compatibility, columnar storage

**Why PostgreSQL JDBC driver works with QuestDB:**
- QuestDB implements PostgreSQL wire protocol
- JDBC clients connect to QuestDB like it's PostgreSQL
- Standard PostgreSQL commands work: `INSERT`, `SELECT`, `CREATE TABLE`
- QuestDB-specific features (time-series optimizations) happen under the hood

---

## Project Structure (What You'll Create)

```
database-consumer/
├── pom.xml                                  # Maven build file
└── src/
    └── main/
        ├── java/
        │   └── com/quantstream/consumer/
        │       ├── ConsumerApplication.java      # Main class
        │       ├── config/
        │       │   ├── KafkaConsumerConfig.java # Kafka configuration
        │       │   └── QuestDBConfig.java       # Database configuration
        │       ├── model/
        │       │   └── Tick.java                # Data model (same as generator)
        │       └── service/
        │           ├── TickConsumer.java        # Kafka message listener
        │           └── TickRepository.java      # Database operations
        └── resources/
            └── application.yml                   # Configuration file
```

---

## Step 1: Create Project Using Spring Initializr

### Why Spring Initializr?

**Spring Initializr** generates a Spring Boot project with:
- Correct folder structure
- Maven/Gradle build files
- Dependencies pre-configured
- Ready-to-run skeleton

**Same tool as generator, different dependencies.**

### Option A: Web Browser (Recommended)

1. **Open:** https://start.spring.io/

2. **Configure project:**

   **Project Metadata:**
   ```
   Project:       Maven
   Language:      Java
   Spring Boot:   3.5.0 (or latest stable version)
   
   Group:         com.quantstream
   Artifact:      database-consumer
   Name:          Database Consumer
   Description:   Kafka consumer that writes market data to QuestDB
   Package name:  com.quantstream.consumer
   Packaging:     Jar
   Java:          21
   ```

   **Dependencies (click "Add Dependencies" button):**
   - Spring for Apache Kafka
   - PostgreSQL Driver
   - Lombok
   - Spring Boot DevTools

3. **Click "Generate"**

   Downloads `database-consumer.zip`

4. **Extract:**
   ```bash
   cd /Users/mhiteshkumar/QuantStream
   unzip ~/Downloads/database-consumer.zip
   ```

   Creates `database-consumer/` folder

### Option B: Command Line (Alternative)

```bash
cd /Users/mhiteshkumar/QuantStream

curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.5.0 \
  -d groupId=com.quantstream \
  -d artifactId=database-consumer \
  -d name="Database Consumer" \
  -d description="Kafka consumer that writes market data to QuestDB" \
  -d packageName=com.quantstream.consumer \
  -d packaging=jar \
  -d javaVersion=21 \
  -d dependencies=kafka,postgresql,lombok,devtools \
  -o database-consumer.zip

unzip database-consumer.zip
```

---

## Step 2: Open Project in IntelliJ IDEA

### Why IntelliJ IDEA?

**IntelliJ IDEA** is the best IDE for Spring Boot:
- Auto-import dependencies
- Code completion for Spring annotations
- Run/debug configurations
- Maven integration

**Community Edition is free** and sufficient for our needs.

### Opening the Project

1. **Launch IntelliJ IDEA**

2. **Open Project:**
   - Click "Open"
   - Navigate to `/Users/mhiteshkumar/QuantStream/database-consumer`
   - Click "Open"

3. **Wait for Indexing:**
   - IntelliJ scans project files
   - Downloads Maven dependencies
   - Takes 1-2 minutes first time
   - Progress bar at bottom of screen

4. **Verify:**
   - Left panel shows project structure
   - `src/main/java/com/quantstream/consumer` folder exists
   - `ConsumerApplication.java` exists

---

## Step 3: Understand Generated Files

### pom.xml (Maven Build File)

**Location:** `/Users/mhiteshkumar/QuantStream/database-consumer/pom.xml`

**What it contains:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <!-- Parent: Spring Boot Starter (provides dependency versions) -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.0</version>
        <relativePath/>
    </parent>
    
    <!-- Project coordinates -->
    <groupId>com.quantstream</groupId>
    <artifactId>database-consumer</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>Database Consumer</name>
    <description>Kafka consumer that writes market data to QuestDB</description>
    
    <!-- Java version -->
    <properties>
        <java.version>21</java.version>
    </properties>
    
    <!-- Dependencies -->
    <dependencies>
        <!-- Spring Boot Starter (core Spring Boot) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        
        <!-- Spring Kafka (Kafka consumer support) -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
        
        <!-- PostgreSQL JDBC Driver (connects to QuestDB) -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        
        <!-- Lombok (reduces boilerplate code) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        
        <!-- Spring Boot DevTools (auto-restart during development) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
            <optional>true</optional>
        </dependency>
        
        <!-- Spring Boot Test (for testing, we'll use in Phase 6) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        
        <!-- Spring Kafka Test (for testing Kafka consumer) -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    
    <!-- Build plugin (packages JAR) -->
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### Understanding Key Dependencies

#### 1. Spring for Apache Kafka

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

**What it provides:**
- `@KafkaListener` annotation for consuming messages
- Automatic JSON deserialization
- Error handling and retry logic
- Consumer group management

**Consumer vs Producer:**
- **Producer** (generator): Sends messages to Kafka using `KafkaTemplate`
- **Consumer** (this project): Receives messages using `@KafkaListener`
- Same library, different parts

#### 2. PostgreSQL JDBC Driver

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

**What it provides:**
- JDBC driver for connecting to PostgreSQL-compatible databases
- Standard SQL operations: `INSERT`, `SELECT`, `CREATE TABLE`
- Connection pooling via HikariCP (included in Spring Boot)

**Why it works with QuestDB:**

| Feature | PostgreSQL | QuestDB |
|---------|-----------|---------|
| **Wire Protocol** | PostgreSQL protocol | Same (compatible) |
| **Port** | 5432 | 8812 (default) |
| **SQL Dialect** | Standard SQL | PostgreSQL-compatible SQL |
| **Connection** | `jdbc:postgresql://host:port/db` | `jdbc:postgresql://host:8812/qdb` |

**Code example:**
```java
// Same code works for both PostgreSQL and QuestDB
Connection conn = DriverManager.getConnection(
    "jdbc:postgresql://localhost:8812/qdb",
    "admin", 
    "quest"
);
```

**QuestDB-specific optimizations happen automatically:**
- Columnar storage
- Time-series indexing
- Out-of-order ingestion
- We just use standard SQL!

#### 3. Lombok

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

**What it provides:**
- `@Data`: Generates getters, setters, toString, equals, hashCode
- `@AllArgsConstructor`: Generates constructor with all fields
- `@NoArgsConstructor`: Generates no-argument constructor
- `@RequiredArgsConstructor`: Generates constructor for `final` fields

**Example:**
```java
// Without Lombok (30 lines)
public class Tick {
    private String symbol;
    private double price;
    private long timestamp;
    
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    // ... 20 more lines
}

// With Lombok (5 lines)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tick {
    private String symbol;
    private double price;
    private long timestamp;
}
```

#### 4. Spring Boot DevTools

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

**What it provides:**
- Automatic application restart when code changes
- Faster development cycle
- No need to manually stop/start app

**How it works:**
1. You change a Java file
2. IntelliJ auto-compiles it
3. DevTools detects change
4. App restarts automatically (1-2 seconds)

**Without DevTools:**
- Stop app (`Ctrl+C`)
- Rebuild project
- Start app again
- Takes 10-15 seconds

---

### ConsumerApplication.java (Main Class)

**Location:** `src/main/java/com/quantstream/consumer/ConsumerApplication.java`

**Generated code:**

```java
package com.quantstream.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
```

**What it does:**
- `@SpringBootApplication`: Tells Spring this is the entry point
- `main()`: Standard Java entry point
- `SpringApplication.run()`: Starts Spring Boot

**This is the file you run to start the consumer.**

**No additional annotations needed:**
- No `@EnableScheduling` (consumer is event-driven, not scheduled)
- No `@EnableKafka` (auto-enabled by Spring Boot)

---

## Step 4: Create Package Structure

### Why Packages?

**Packages = Folders for organizing code by purpose.**

**Our structure:**
```
com.quantstream.consumer
├── config/    # Configuration classes (Kafka, QuestDB setup)
├── model/     # Data classes (Tick POJO)
└── service/   # Business logic (consumer, repository)
```

### Create Packages in IntelliJ

**Method 1: Right-click in Project Explorer**

1. Right-click `src/main/java/com/quantstream/consumer`
2. New → Package
3. Enter: `config`
4. Repeat for `model` and `service`

**Method 2: Terminal (Alternative)**

```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer

mkdir -p src/main/java/com/quantstream/consumer/config
mkdir -p src/main/java/com/quantstream/consumer/model
mkdir -p src/main/java/com/quantstream/consumer/service
```

**Result:**
```
com.quantstream.consumer/
├── ConsumerApplication.java
├── config/     (empty)
├── model/      (empty)
└── service/    (empty)
```

---

## Step 5: Enable Lombok Annotation Processing

### Why This Is Needed

**Lombok generates code at compile time:**
- `@Data` → generates getters, setters, toString, equals, hashCode
- `@NoArgsConstructor` → generates no-argument constructor
- `@AllArgsConstructor` → generates constructor with all fields

**IntelliJ needs to be told to process these annotations.**

### Steps

1. **Open Preferences:**
   - Mac: `Cmd + ,`
   - Windows/Linux: `File → Settings`

2. **Search:** "annotation processing"

3. **Navigate to:**
   ```
   Build, Execution, Deployment
   → Compiler
   → Annotation Processors
   ```

4. **Enable:**
   - Check "Enable annotation processing"

5. **Click:** "Apply" → "OK"

6. **Rebuild project:**
   - Menu: Build → Rebuild Project

**Without this:**
- Lombok annotations won't work
- IntelliJ will show errors like "Cannot resolve method getSymbol()"
- Code won't compile

---

## Step 6: Create application.yml

### Delete application.properties

1. Right-click `src/main/resources/application.properties`
2. Delete

### Create application.yml

1. Right-click `src/main/resources`
2. New → File
3. Name: `application.yml`
4. Click OK

**Add this content:**

```yaml
spring:
  application:
    name: database-consumer
  
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: questdb-consumer-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.quantstream.consumer.model
    listener:
      ack-mode: manual

server:
  port: 8082

logging:
  level:
    com.quantstream: DEBUG
    org.springframework: INFO
    org.apache.kafka: WARN

questdb:
  url: jdbc:postgresql://localhost:8812/qdb
  username: admin
  password: quest
```

### Understanding Each Section

#### Application Name

```yaml
spring:
  application:
    name: database-consumer
```

Application name (used in logs, metrics).

#### Kafka Bootstrap Servers

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

Where Kafka is running (our Docker Compose setup).

#### Consumer Group ID

```yaml
consumer:
  group-id: questdb-consumer-group
```

**What is a consumer group?**
- Kafka tracks which messages each group has consumed
- Multiple consumers in same group share the workload
- Each message processed by only one consumer in the group

**Our setup:**
- Only 1 consumer (this app)
- Group name: `questdb-consumer-group`
- Kafka remembers which messages we've processed

#### Auto Offset Reset

```yaml
auto-offset-reset: earliest
```

**What happens when consumer starts for first time?**
- `earliest`: Read from beginning of topic (all historical messages)
- `latest`: Read only new messages (skip history)

**We use `earliest`** to process all data, even if consumer was down.

**Example scenario:**
1. Generator produces 100 messages
2. Consumer crashes after processing 50
3. Consumer restarts
4. With `earliest`: Continues from message 51 (Kafka remembers offset)
5. With `latest`: Skips to message 101 (loses messages 51-100)

#### Key Deserializer

```yaml
key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

How to convert message key (bytes → String).

**Producer sends:** "AAPL" (String) → bytes
**Consumer receives:** bytes → "AAPL" (String)

#### Value Deserializer

```yaml
value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
properties:
  spring.json.trusted.packages: com.quantstream.consumer.model
```

How to convert message value (bytes → Tick object).

**Producer sends:** `Tick` object → JSON → bytes
**Consumer receives:** bytes → JSON → `Tick` object

**Trusted packages:**
- Security feature to prevent malicious deserialization
- Only classes in `com.quantstream.consumer.model` can be deserialized
- Without this: Spring refuses to deserialize (security error)

#### Listener Ack Mode

```yaml
listener:
  ack-mode: manual
```

**When does Kafka mark message as "processed"?**
- `auto`: Immediately after listener method returns
- `manual`: When you explicitly call `acknowledgment.acknowledge()`

**We use `manual`** to ensure message saved to database before acknowledging.

**Example:**
```java
@KafkaListener(topics = "market-data")
public void consume(Tick tick, Acknowledgment ack) {
    repository.save(tick);    // Save to database
    ack.acknowledge();         // Now mark as processed
}
```

**If database save fails:**
- Don't acknowledge
- Kafka will redeliver message
- No data loss

#### Server Port

```yaml
server:
  port: 8082
```

HTTP port for this application.

**Why different from generator (8081)?**
- Can't have two apps on same port
- Generator: 8081
- Consumer: 8082
- Web UI (Phase 2): 8083

#### Logging Levels

```yaml
logging:
  level:
    com.quantstream: DEBUG
    org.springframework: INFO
    org.apache.kafka: WARN
```

**Logging levels:**
- **DEBUG:** Very detailed (use for our code while developing)
- **INFO:** Important events (use for Spring framework)
- **WARN:** Warnings only (use for Kafka to reduce noise)

**Our code will log:**
```
DEBUG c.q.c.s.TickConsumer : Received tick: AAPL -> $180.52
DEBUG c.q.c.s.TickRepository : Saved to QuestDB: AAPL
```

#### QuestDB Connection

```yaml
questdb:
  url: jdbc:postgresql://localhost:8812/qdb
  username: admin
  password: quest
```

**Connection details:**
- **Protocol:** `jdbc:postgresql://` (PostgreSQL wire protocol)
- **Host:** `localhost` (Docker Compose setup)
- **Port:** `8812` (QuestDB's PostgreSQL-compatible port, not 5432)
- **Database:** `qdb` (default QuestDB database name)
- **Username:** `admin` (default)
- **Password:** `quest` (default)

**Custom config section:**
- Not Spring Boot standard (we'll read this manually)
- Spring Boot auto-config doesn't know about QuestDB
- We'll create `QuestDBConfig.java` to read these values

---

## Step 7: Verify Setup

### Build Project

**In IntelliJ:**
1. Click "Maven" tab (right side)
2. Expand "database-consumer"
3. Expand "Lifecycle"
4. Double-click "clean"
5. Double-click "install"

**Or in terminal:**
```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer
mvn clean install
```

**Expected output:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: 5.234 s
```

**If errors:**
- Check Java version: `java -version` (should be 21)
- Check Maven version: `mvn -version` (should be 3.9.x)
- Try: `mvn clean install -U` (force update dependencies)

### Run Application (Should Start and Stop)

**In IntelliJ:**
1. Open `ConsumerApplication.java`
2. Click green arrow next to `main()` method
3. Select "Run 'ConsumerApplication'"

**Or in terminal:**
```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer
mvn spring-boot:run
```

**Expected output:**
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.5.0)

2024-07-12 10:00:00 INFO  c.q.c.ConsumerApplication : Starting ConsumerApplication
2024-07-12 10:00:02 INFO  c.q.c.ConsumerApplication : Started ConsumerApplication in 2.5 seconds
```

**Application starts successfully!**

**Stop it:** `Ctrl+C` (we'll add actual logic in next guides)

---

## Common Issues

### Issue 1: "Cannot find symbol @SpringBootApplication"

**Cause:** Maven dependencies not downloaded

**Fix:**
```bash
mvn clean install -U
```

In IntelliJ: File → Invalidate Caches → Restart

### Issue 2: "Java version mismatch"

**Error:**
```
Fatal error compiling: error: invalid target release: 21
```

**Cause:** Project configured for Java 21, but IDE using Java 17

**Fix in IntelliJ:**
1. File → Project Structure → Project
2. SDK: Choose Java 21
3. Language level: 21

**Verify Java version:**
```bash
java -version
```

Should show `openjdk version "21.0.x"`.

### Issue 3: Lombok Not Working

**Error:** "Cannot resolve method getPrice()"

**Cause:** Annotation processing not enabled

**Fix:** Follow Step 5 above

### Issue 4: Port 8082 Already in Use

**Error:**
```
Web server failed to start. Port 8082 was already in use.
```

**Cause:** Another app using port 8082

**Fix:**
```bash
# Find process using port 8082
lsof -i :8082

# Kill it
kill -9 <PID>
```

**Or change port in `application.yml`:**
```yaml
server:
  port: 8083
```

### Issue 5: Kafka Connection Error

**Error:**
```
WARN  o.a.k.c.NetworkClient : Connection to node -1 could not be established
```

**Cause:** Kafka not running

**Fix:**
```bash
cd /Users/mhiteshkumar/QuantStream
docker-compose up -d
```

Wait 30 seconds for Kafka to start, then retry.

### Issue 6: QuestDB Connection Error

**Error:**
```
Connection refused: localhost:8812
```

**Cause:** QuestDB not running

**Fix:**
```bash
cd /Users/mhiteshkumar/QuantStream
docker-compose up -d
```

Wait 10 seconds for QuestDB to start, then retry.

---

## Project Checklist

Before moving to next guide, verify:

- [ ] Project created via Spring Initializr
- [ ] Opened in IntelliJ IDEA
- [ ] Packages created: `config`, `model`, `service`
- [ ] Lombok annotation processing enabled
- [ ] `application.yml` created with Kafka and QuestDB config
- [ ] `mvn clean install` succeeds
- [ ] Application starts without errors
- [ ] Docker Compose running (Kafka on `localhost:9092`, QuestDB on `localhost:8812`)

---

## What's Next?

Now that the project skeleton is ready, you'll create:

1. **Tick.java** (model) — Data class representing one price update (same structure as generator)
2. **QuestDBConfig.java** (config) — QuestDB connection configuration
3. **KafkaConsumerConfig.java** (config) — Kafka consumer configuration
4. **TickRepository.java** (service) — Database operations (INSERT into QuestDB)
5. **TickConsumer.java** (service) — Kafka message listener that calls repository

**Next guide:** `consumer-model-guide.md` (creating Tick.java and config classes)
