# Strategy Engine Project Setup

## Overview

This guide walks through creating the `strategy-engine` Spring Boot service from scratch, including all configuration, models, and testing.

**By the end:** You'll have a working Spring Boot application that connects to QuestDB and Kafka, ready to implement strategies.

---

## Why This Architecture?

### Interview Question: "Why not use Spring Data JPA?"

**Answer:**
QuestDB doesn't support transactions (no BEGIN/COMMIT/ROLLBACK). Spring Data JPA requires transaction support for its EntityManager. We use **JdbcTemplate** instead because:

1. **Simpler** - Direct SQL, no ORM complexity
2. **Works with QuestDB** - No transaction requirements
3. **Faster** - No object-relational mapping overhead
4. **Transparent** - SQL is visible in code, easy to debug

**Alternative considered:** Hibernate with `@Transactional(propagation=NOT_SUPPORTED)` → Rejected as overly complex.

---

### Interview Question: "Why single service for all strategies?"

**Answer:**
Strategies are **algorithms**, not **features**:

| Aspect | Strategies | Features (Users, Orders) |
|--------|------------|-------------------------|
| **State** | Stateless (deterministic) | Stateful (user sessions) |
| **Scaling** | Identical (all CPU-bound) | Different (users=DB, orders=compute) |
| **Teams** | Single team | Separate teams |
| **Deployment** | Together (algorithm suite) | Independent (feature lifecycle) |

**Microservices pattern** solves organizational problems (Conway's Law). For algorithms with identical resource profiles, **modular monolith** is pragmatic.

**Free tier consideration:** 10 microservices = 2.5 GB RAM minimum (impossible on free tier). Single service = 300 MB.

---

## Prerequisites

- [ ] Java 21 installed (`java -version`)
- [ ] Maven 3.9+ installed (`mvn -version`)
- [ ] Docker Desktop running
- [ ] Phase 1 services running (data-generator, database-consumer)
- [ ] QuestDB has tick data

**Verify Phase 1:**
```bash
# Check QuestDB has data
curl -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT count(*) FROM ticks;"
# Should return > 1000
```

---

## Step 1: Create Project Directory

```bash
cd /Users/mhiteshkumar/QuantStream
mkdir strategy-engine
cd strategy-engine
```

---

## Step 2: Create pom.xml

Create `pom.xml` with Spring Boot 4.0.7:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.7</version>
        <relativePath/>
    </parent>

    <groupId>com.quantstream</groupId>
    <artifactId>strategy-engine</artifactId>
    <version>1.0.0</version>
    <name>QuantStream Strategy Engine</name>
    <description>Trading strategy engine - analyzes ticks, generates signals</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Core -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- JDBC for QuestDB access -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- Kafka Producer -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- PostgreSQL Driver (QuestDB uses PostgreSQL wire protocol) -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Lombok (reduce boilerplate) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

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

### Interview Question: "Why these specific dependencies?"

**`spring-boot-starter`:**
- Core Spring Boot (IoC, DI, auto-configuration)
- Without `-web` → No embedded Tomcat (we don't need REST endpoints here)

**`spring-boot-starter-jdbc`:**
- JdbcTemplate + DataSource abstraction
- HikariCP connection pooling (fastest Java pool)
- Transaction management (we disable it for QuestDB)

**`spring-kafka`:**
- Kafka producer with Spring abstractions
- JSON serialization out of the box
- Retry and error handling

**`postgresql`:**
- JDBC driver for PostgreSQL wire protocol
- QuestDB implements PostgreSQL protocol (subset)
- Allows standard PostgreSQL tools (`psql`, DBeaver)

**`lombok`:**
- `@Data` → generates getters/setters/equals/hashCode/toString
- `@AllArgsConstructor` / `@NoArgsConstructor` → generates constructors
- Reduces 50+ lines of boilerplate per class to 3 annotations

---

## Step 3: Create Directory Structure

```bash
mkdir -p src/main/java/com/quantstream/strategy/{config,model,framework,utils,strategies}
mkdir -p src/main/resources
mkdir -p src/test/java
```

**Result:**
```
strategy-engine/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/quantstream/strategy/
    │   │   ├── StrategyEngineApplication.java  (to be created)
    │   │   ├── config/                          (configuration classes)
    │   │   ├── model/                           (Tick, Signal POJOs)
    │   │   ├── framework/                       (TradingStrategy interface, Scheduler)
    │   │   ├── utils/                           (IndicatorUtils)
    │   │   └── strategies/                      (MaCrossover, RSI, etc.)
    │   └── resources/
    │       └── application.yml  (to be created)
    └── test/
        └── java/
```

### Package Organization Explained:

**`config/`** - Spring configuration classes
- `QuestDBConfig` - DataSource and JdbcTemplate beans
- `KafkaProducerConfig` - Kafka producer setup

**`model/`** - Data transfer objects (DTOs)
- `Tick` - Represents market data point
- `Signal` - Represents trading signal output

**`framework/`** - Core interfaces and schedulers
- `TradingStrategy` - Interface all strategies implement
- `StrategyScheduler` - Runs all strategies every minute

**`utils/`** - Shared utility classes
- `IndicatorUtils` - MA, RSI, Bollinger Bands calculations

**`strategies/`** - Strategy implementations
- `MaCrossoverStrategy`, `RsiStrategy`, etc.

---

## Step 4: Create application.yml

Create `src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: strategy-engine

  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        # Retry configuration
        retries: 3
        retry.backoff.ms: 1000

  datasource:
    url: jdbc:postgresql://localhost:8812/qdb
    username: admin
    password: quest
    driver-class-name: org.postgresql.Driver
    hikari:
      auto-commit: true
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 10000
      idle-timeout: 600000
      max-lifetime: 1800000

server:
  port: 8083

logging:
  level:
    com.quantstream: DEBUG
    org.springframework: INFO
    org.apache.kafka: WARN
    com.zaxxer.hikari: INFO
```

### Configuration Explained:

**Kafka Producer:**
- `JsonSerializer` - Automatically converts Signal objects to JSON
- `retries: 3` - Retry failed sends (network blips)
- `retry.backoff.ms` - Wait 1 second between retries

**HikariCP (Connection Pool):**
- `maximum-pool-size: 10` - Max 10 concurrent DB connections
- `minimum-idle: 2` - Keep 2 connections warm
- `connection-timeout: 10s` - Fail fast if no connection available
- `idle-timeout: 10 min` - Close idle connections
- `max-lifetime: 30 min` - Recycle connections (avoid stale)

**Port 8083:**
- Doesn't conflict with Phase 1 services:
  - 8081: data-generator
  - 8082: database-consumer
  - 8083: strategy-engine ← This service
  - 8084: signal-aggregator (next task)

### Interview Question: "Why these HikariCP settings?"

**Answer:**
Strategy engine queries QuestDB every minute for each symbol (10 symbols × 10 strategies = 100 queries/min).

- **Small pool (10 max)** - Queries are fast (<10ms), don't need many connections
- **2 minimum idle** - Keep connections warm to avoid handshake latency
- **30 min max lifetime** - QuestDB connection can become stale over time

Alternative: Single connection without pooling → Rejected because parallel strategy execution would serialize all queries.

---

## Step 5: Create Main Application Class

Create `src/main/java/com/quantstream/strategy/StrategyEngineApplication.java`:

```java
package com.quantstream.strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Strategy Engine - Analyzes market data and generates trading signals.
 * 
 * Architecture:
 * - Queries QuestDB for historical ticks
 * - Runs 10 trading strategies every minute
 * - Produces signals to Kafka "trading-signals" topic
 * 
 * Design:
 * - Interface-based (TradingStrategy interface)
 * - Spring auto-discovery of strategies
 * - Scheduled execution (@Scheduled)
 */
@SpringBootApplication
@EnableScheduling  // Enable @Scheduled annotation
public class StrategyEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(StrategyEngineApplication.java, args);
    }
}
```

### Interview Question: "What does @SpringBootApplication do?"

**Answer:**
It's a meta-annotation combining three annotations:

1. **@Configuration** - Class can define @Bean methods
2. **@EnableAutoConfiguration** - Auto-configure based on classpath
   - Sees `spring-kafka` → auto-configures Kafka
   - Sees `spring-jdbc` → auto-configures DataSource
3. **@ComponentScan** - Scans package for @Component, @Service, @Repository

**Component Scan Default:** Scans `com.quantstream.strategy` and all sub-packages. That's why our strategies in `com.quantstream.strategy.strategies` are auto-discovered.

---

### Interview Question: "What does @EnableScheduling do?"

**Answer:**
Enables Spring's `@Scheduled` annotation processing. Without it, methods annotated with `@Scheduled(fixedRate=60000)` would be ignored.

**How it works:**
1. Spring finds all beans with `@Scheduled` methods
2. Creates a TaskScheduler thread pool
3. Schedules tasks according to annotations
4. Manages execution (single-threaded by default)

**Alternative:** Quartz scheduler → Rejected as over-engineered for simple periodic execution.

---

## Step 6: Test Basic Startup

```bash
mvn clean package
```

**Expected output:**
```
[INFO] BUILD SUCCESS
[INFO] Total time:  5.123 s
```

**If build fails:**
- Check Java version: `java -version` (must be 21)
- Check Maven: `mvn -version`
- Check pom.xml syntax (XML must be well-formed)

Now start the application:

```bash
mvn spring-boot:run
```

**Expected logs:**
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

2026-07-17 10:00:00.000  INFO --- [           main] c.q.s.StrategyEngineApplication          : Starting StrategyEngineApplication
2026-07-17 10:00:01.234  INFO --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port(s): 8083 (http)
2026-07-17 10:00:01.500  INFO --- [           main] c.q.s.StrategyEngineApplication          : Started StrategyEngineApplication in 2.5 seconds
```

**Success!** Press `Ctrl+C` to stop.

**If application crashes:**
- Check Kafka is running: `docker ps | grep kafka`
- Check QuestDB is running: `curl http://localhost:9001`
- Check port 8083 is available: `lsof -i :8083`
- Check logs for specific error

---

## Step 7: Create Model Classes

### Tick Model

Create `src/main/java/com/quantstream/strategy/model/Tick.java`:

```java
package com.quantstream.strategy.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a single market data tick.
 * 
 * This is a read-only model - strategies query ticks from QuestDB
 * but never modify or write them (Phase 1 handles writes).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tick {
    private String symbol;      // Stock/crypto symbol (AAPL, BTC, etc.)
    private double price;       // Current price
    private double volume;      // Trade volume
    private Instant timestamp;  // When this tick occurred
}
```

### Signal Model

Create `src/main/java/com/quantstream/strategy/model/Signal.java`:

```java
package com.quantstream.strategy.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a trading signal generated by a strategy.
 * 
 * This is the OUTPUT of strategy analysis - produced to Kafka
 * and consumed by signal-aggregator service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Signal {
    private String symbol;         // Which asset (AAPL, BTC, etc.)
    private String action;         // BUY, SELL, or HOLD
    private String strategyName;   // Which strategy generated this (MA_CROSSOVER, RSI, etc.)
    private double confidence;     // Confidence score 0.0 to 1.0
    private Instant timestamp;     // When signal was generated
}
```

### Interview Question: "Why Lombok?"

**Without Lombok (Tick.java would be ~60 lines):**
```java
public class Tick {
    private String symbol;
    private double price;
    private double volume;
    private Instant timestamp;
    
    public Tick() {}
    
    public Tick(String symbol, double price, double volume, Instant timestamp) {
        this.symbol = symbol;
        this.price = price;
        this.volume = volume;
        this.timestamp = timestamp;
    }
    
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }
    // ... 20 more lines
}
```

**With Lombok (6 lines):**
```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tick {
    private String symbol;
    private double price;
    private double volume;
    private Instant timestamp;
}
```

**Trade-off:** Adds compile-time dependency, but saves massive boilerplate. Industry standard for DTOs.

---

## Step 8: Create Configuration Classes

### QuestDB Configuration

Create `src/main/java/com/quantstream/strategy/config/QuestDBConfig.java`:

```java
package com.quantstream.strategy.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * QuestDB-specific DataSource configuration.
 * 
 * QuestDB limitations:
 * - No transaction support (no BEGIN/COMMIT/ROLLBACK)
 * - No transaction isolation levels
 * - No savepoints
 * 
 * This config bypasses HikariCP's transaction checks to prevent errors.
 */
@Configuration
public class QuestDBConfig {

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        
        // Connection details
        config.setJdbcUrl("jdbc:postgresql://localhost:8812/qdb");
        config.setUsername("admin");
        config.setPassword("quest");
        config.setDriverClassName("org.postgresql.Driver");

        // Bypass transaction support checks
        config.setAutoCommit(true);
        config.setConnectionInitSql("SELECT 1");
        config.setConnectionTestQuery("SELECT 1");
        config.setTransactionIsolation(null);  // CRITICAL: QuestDB doesn't support isolation

        // Connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
```

### Interview Question: "Why setTransactionIsolation(null)?"

**Answer:**
Without this line:
1. HikariCP calls `connection.getTransactionIsolation()`
2. PostgreSQL driver sends: `SHOW transaction_isolation;`
3. QuestDB doesn't recognize this command → throws exception
4. Application crashes on startup

**With `setTransactionIsolation(null)`:**
- HikariCP skips isolation level detection
- Treats connection as "isolation level unknown"
- Proceeds without errors

**Alternative:** Could patch PostgreSQL driver → Rejected as maintaining fork is unsustainable.

---

### Kafka Producer Configuration

Create `src/main/java/com/quantstream/strategy/config/KafkaProducerConfig.java`:

```java
package com.quantstream.strategy.config;

import com.quantstream.strategy.model.Signal;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer configuration for sending trading signals.
 * 
 * Producer sends Signal objects as JSON to "trading-signals" topic.
 * Signal-aggregator service consumes these signals.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Signal> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // Kafka broker address
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        
        // Serializers
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        
        // Reliability settings
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");  // Wait for leader ack
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3); // Retry 3 times
        configProps.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000); // Wait 1s between retries
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, Signal> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

### Interview Question: "Why ACKS_CONFIG='1'?"

**Answer:**
Kafka has 3 acknowledgment levels:

| Level | Behavior | Latency | Reliability |
|-------|----------|---------|-------------|
| `0` | Don't wait for any ack | Fastest | Can lose data |
| `1` | Wait for leader ack | Medium | **Chosen** - good balance |
| `all` | Wait for all replicas | Slowest | Strongest guarantee |

**Our choice: `1` (leader ack)**

**Why:**
- Signals are not financial transactions (can tolerate rare loss)
- We generate new signals every minute (losing one is recoverable)
- Latency matters (100 signals/min → don't want slow produces)

**If this were production banking:** Use `all` to ensure no signal is ever lost, even at cost of higher latency.

---

## Step 9: Test Configuration

Let's verify everything connects properly.

### Create Test Endpoint

Create `src/main/java/com/quantstream/strategy/HealthCheckController.java`:

```java
package com.quantstream.strategy;

import com.quantstream.strategy.model.Signal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Health check endpoints to verify configuration.
 * 
 * DELETE THIS FILE after verifying everything works!
 */
@RestController
public class HealthCheckController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, Signal> kafkaTemplate;

    @GetMapping("/health/questdb")
    public String testQuestDB() {
        try {
            Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticks",
                Long.class
            );
            return "✅ QuestDB connected! Tick count: " + count;
        } catch (Exception e) {
            return "❌ QuestDB error: " + e.getMessage();
        }
    }

    @GetMapping("/health/kafka")
    public String testKafka() {
        try {
            Signal testSignal = new Signal(
                "TEST",
                "BUY",
                "HEALTH_CHECK",
                0.99,
                Instant.now()
            );
            kafkaTemplate.send("trading-signals", testSignal);
            return "✅ Signal sent to Kafka!";
        } catch (Exception e) {
            return "❌ Kafka error: " + e.getMessage();
        }
    }
}
```

### Run Tests

```bash
# Terminal 1: Start strategy-engine
mvn spring-boot:run

# Terminal 2: Test QuestDB
curl http://localhost:8083/health/questdb
# Expected: "✅ QuestDB connected! Tick count: 27243"

# Terminal 3: Test Kafka
curl http://localhost:8083/health/kafka
# Expected: "✅ Signal sent to Kafka!"
```

### Verify Kafka Signal

```bash
# Consume from trading-signals topic
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic trading-signals \
  --from-beginning

# Should see:
# {"symbol":"TEST","action":"BUY","strategyName":"HEALTH_CHECK","confidence":0.99,"timestamp":"2026-07-17T10:00:00Z"}
```

**Success!** All connections work.

**Now delete `HealthCheckController.java`** - it was just for testing.

---

## Step 10: Final Verification

```bash
# Build
mvn clean package

# Run
mvn spring-boot:run
```

**Expected logs:**
```
2026-07-17 10:00:00.000  INFO --- [           main] c.q.s.StrategyEngineApplication : Starting StrategyEngineApplication
2026-07-17 10:00:00.500  INFO --- [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Starting...
2026-07-17 10:00:00.600  INFO --- [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Start completed.
2026-07-17 10:00:01.000  INFO --- [           main] o.s.k.core.KafkaProducerFactory          : Created new Producer
2026-07-17 10:00:01.500  INFO --- [           main] c.q.s.StrategyEngineApplication          : Started StrategyEngineApplication in 2.5 seconds
```

**No errors!** ✅

---

## Project Structure (Final)

```
strategy-engine/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/quantstream/strategy/
│   │   │   ├── StrategyEngineApplication.java
│   │   │   ├── config/
│   │   │   │   ├── KafkaProducerConfig.java
│   │   │   │   └── QuestDBConfig.java
│   │   │   └── model/
│   │   │       ├── Signal.java
│   │   │       └── Tick.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/
└── target/
    └── strategy-engine-1.0.0.jar
```

---

## Summary

**What You Built:**
- ✅ Maven project with Spring Boot 4.0.7
- ✅ QuestDB connection with JdbcTemplate
- ✅ Kafka producer for signals
- ✅ Models (Tick, Signal)
- ✅ Configuration classes
- ✅ Verified all connections work

**Next Task:**
Proceed to **Task 2: Build Core Framework** (guide: `strategy-framework-guide.md`)

---

## Troubleshooting

### "Could not resolve dependencies"

```bash
# Clear Maven cache
rm -rf ~/.m2/repository
mvn clean install
```

### "Port 8083 already in use"

```bash
# Find process
lsof -i :8083

# Kill it
kill -9 <PID>
```

### "Failed to configure a DataSource"

- Ensure `QuestDBConfig.java` has `@Configuration` annotation
- Check `@Bean` methods are public
- Verify QuestDB is running: `curl http://localhost:9001`

### "KafkaException: Failed to construct kafka producer"

- Check Kafka is running: `docker ps | grep kafka`
- Check bootstrap servers: `localhost:9092`
- Ensure Kafka topic exists (will be created in Task 2)

### Interview Question: "How would you deploy this to production?"

**Answer:**
1. **Containerize** - Create Dockerfile, build image
2. **Environment variables** - Externalize config (Kafka URL, DB URL)
3. **Secrets management** - Use Vault/AWS Secrets for credentials
4. **Health checks** - Add `/actuator/health` endpoints
5. **Monitoring** - Prometheus metrics, Grafana dashboards
6. **Logging** - Centralized logging (ELK/Splunk)
7. **CI/CD** - Automated build/test/deploy pipeline

**Free tier deployment:**
- Railway/Render: 512 MB, sufficient for this service
- Environment variables via dashboard
- GitHub Actions for CI/CD
