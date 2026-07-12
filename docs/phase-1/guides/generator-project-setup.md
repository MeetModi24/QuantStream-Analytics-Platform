# Market Data Generator - Project Setup Guide

## What You're Building

**Market Data Generator** is a Spring Boot application that:
1. Generates realistic stock/crypto prices using Geometric Brownian Motion (GBM)
2. Sends price updates to Kafka every 1 second
3. Runs 10 simulators (one per token)
4. Produces 10 messages/second to Kafka topic "market-data"

**This is the "heart" of your system** — it creates the data stream that everything else processes.

---

## Project Structure (What You'll Create)

```
data-generator/
├── pom.xml                                  # Maven build file
└── src/
    └── main/
        ├── java/
        │   └── com/quantstream/generator/
        │       ├── GeneratorApplication.java      # Main class
        │       ├── config/
        │       │   └── KafkaProducerConfig.java  # Kafka configuration
        │       ├── model/
        │       │   └── Tick.java                 # Data model (POJO)
        │       └── service/
        │           ├── MarketDataGenerator.java  # Main business logic
        │           └── PriceSimulator.java       # GBM implementation
        └── resources/
            └── application.yml                    # Configuration file
```

---

## Step 1: Create Project Using Spring Initializr

### Why Spring Initializr?

**Spring Initializr** is a web tool that generates a Spring Boot project with:
- Correct folder structure
- Maven/Gradle build files
- Dependencies pre-configured
- Ready-to-run skeleton

**Think of it as:** "Create React App" but for Spring Boot.

### Option A: Web Browser (Recommended)

1. **Open:** https://start.spring.io/

2. **Configure project:**

   **Project Metadata:**
   ```
   Project:       Maven
   Language:      Java
   Spring Boot:   3.3.5 (or latest 3.3.x)
   
   Group:         com.quantstream
   Artifact:      data-generator
   Name:          Data Generator
   Description:   Market data generator using Geometric Brownian Motion
   Package name:  com.quantstream.generator
   Packaging:     Jar
   Java:          21
   ```

   **Dependencies (click "Add Dependencies" button):**
   - Spring for Apache Kafka
   - Lombok

3. **Click "Generate"**

   Downloads `data-generator.zip`

4. **Extract:**
   ```bash
   cd /Users/mhiteshkumar/QuantStream
   unzip ~/Downloads/data-generator.zip
   ```

   Creates `data-generator/` folder

### Option B: Command Line (Alternative)

```bash
cd /Users/mhiteshkumar/QuantStream

curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.3.5 \
  -d groupId=com.quantstream \
  -d artifactId=data-generator \
  -d name=Data\ Generator \
  -d description=Market\ data\ generator\ using\ Geometric\ Brownian\ Motion \
  -d packageName=com.quantstream.generator \
  -d packaging=jar \
  -d javaVersion=21 \
  -d dependencies=kafka,lombok \
  -o data-generator.zip

unzip data-generator.zip -d data-generator
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
   - Navigate to `/Users/mhiteshkumar/QuantStream/data-generator`
   - Click "Open"

3. **Wait for Indexing:**
   - IntelliJ scans project files
   - Downloads Maven dependencies
   - Takes 1-2 minutes first time
   - Progress bar at bottom of screen

4. **Verify:**
   - Left panel shows project structure
   - `src/main/java/com/quantstream/generator` folder exists
   - `GeneratorApplication.java` exists

---

## Step 3: Understand Generated Files

### pom.xml (Maven Build File)

**Location:** `/Users/mhiteshkumar/QuantStream/data-generator/pom.xml`

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
        <version>3.3.5</version>
        <relativePath/>
    </parent>
    
    <!-- Project coordinates -->
    <groupId>com.quantstream</groupId>
    <artifactId>data-generator</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>Data Generator</name>
    <description>Market data generator using Geometric Brownian Motion</description>
    
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
        
        <!-- Spring Kafka (Kafka producer/consumer support) -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
        
        <!-- Lombok (reduces boilerplate code) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        
        <!-- Spring Boot Test (for testing, we'll use in Phase 6) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        
        <!-- Spring Kafka Test (for testing Kafka) -->
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

**Key parts:**

**parent:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>
```

Inherits dependency versions from Spring Boot (no need to specify version for each dependency).

**dependencies:**
- `spring-boot-starter`: Core Spring Boot (DI, auto-configuration)
- `spring-kafka`: Kafka client + Spring integration
- `lombok`: Generates getters/setters/constructors

**build plugin:**
- `spring-boot-maven-plugin`: Packages app as executable JAR
- `java -jar data-generator.jar` will run the app

### GeneratorApplication.java (Main Class)

**Location:** `src/main/java/com/quantstream/generator/GeneratorApplication.java`

**Generated code:**

```java
package com.quantstream.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeneratorApplication.class, args);
    }
}
```

**What it does:**
- `@SpringBootApplication`: Tells Spring this is the entry point
- `main()`: Standard Java entry point
- `SpringApplication.run()`: Starts Spring Boot

**This is the file you run to start the app.**

### application.properties (Empty Config File)

**Location:** `src/main/resources/application.properties`

**Generated content:** Empty file

**We'll replace this with `application.yml`** (YAML is easier to read than properties format).

---

## Step 4: Add Scheduling Support

Our generator needs to run every 1 second. Spring provides `@Scheduled` annotation for this.

**Edit `GeneratorApplication.java`:**

```java
package com.quantstream.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // ← Add this annotation
public class GeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeneratorApplication.class, args);
    }
}
```

**What `@EnableScheduling` does:**
- Activates Spring's task scheduler
- Methods annotated with `@Scheduled` will run automatically
- Without this, `@Scheduled` methods are ignored

---

## Step 5: Create Package Structure

### Why Packages?

**Packages = Folders for organizing code by purpose.**

**Our structure:**
```
com.quantstream.generator
├── config/    # Configuration classes (Kafka setup)
├── model/     # Data classes (Tick POJO)
└── service/   # Business logic (generators, simulators)
```

### Create Packages in IntelliJ

**Method 1: Right-click in Project Explorer**

1. Right-click `src/main/java/com/quantstream/generator`
2. New → Package
3. Enter: `config`
4. Repeat for `model` and `service`

**Method 2: Create All at Once**

1. Right-click `src/main/java/com/quantstream/generator`
2. New → Package
3. Enter: `config`
4. Right-click again, New → Package, enter: `model`
5. Right-click again, New → Package, enter: `service`

**Result:**
```
com.quantstream.generator/
├── GeneratorApplication.java
├── config/     (empty)
├── model/      (empty)
└── service/    (empty)
```

---

## Step 6: Enable Lombok Annotation Processing

### Why This Is Needed

**Lombok generates code at compile time:**
- `@Data` → generates getters, setters, toString, equals, hashCode
- `@RequiredArgsConstructor` → generates constructor

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
   - ✅ Check "Enable annotation processing"

5. **Click:** "Apply" → "OK"

6. **Rebuild project:**
   - Menu: Build → Rebuild Project

**Without this:**
- Lombok annotations won't work
- IntelliJ will show errors like "Cannot resolve method getSymbol()"
- Code won't compile

---

## Step 7: Create application.yml

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
    name: data-generator
  
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3

logging:
  level:
    com.quantstream: DEBUG
    org.springframework: INFO
    org.apache.kafka: WARN
```

### Understanding Each Section

**spring.application.name:**
```yaml
name: data-generator
```

Application name (used in logs, metrics).

**spring.kafka.bootstrap-servers:**
```yaml
bootstrap-servers: localhost:9092
```

Where Kafka is running (our Docker Compose setup).

**spring.kafka.producer.key-serializer:**
```yaml
key-serializer: org.apache.kafka.common.serialization.StringSerializer
```

How to convert message key (symbol like "AAPL") to bytes.

**spring.kafka.producer.value-serializer:**
```yaml
value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

How to convert message value (Tick object) to bytes.

**Spring's JsonSerializer:**
- Converts Java object to JSON
- JSON sent to Kafka
- Consumer deserializes JSON back to object

**spring.kafka.producer.acks:**
```yaml
acks: all
```

Wait for all replicas to acknowledge message (most durable).

**Options:**
- `acks: 0` — Don't wait (fastest, least durable)
- `acks: 1` — Wait for leader only (medium)
- `acks: all` — Wait for all replicas (slowest, most durable)

**We use `all` for learning** (see durability in action).

**spring.kafka.producer.retries:**
```yaml
retries: 3
```

If send fails, retry 3 times before giving up.

**logging.level:**
```yaml
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
DEBUG c.q.g.s.MarketDataGenerator : Generated tick: AAPL -> $180.52
DEBUG c.q.g.s.MarketDataGenerator : Sent to Kafka: AAPL
```

---

## Step 8: Verify Setup

### Build Project

**In IntelliJ:**
1. Click "Maven" tab (right side)
2. Expand "data-generator"
3. Expand "Lifecycle"
4. Double-click "clean"
5. Double-click "install"

**Or in terminal:**
```bash
cd /Users/mhiteshkumar/QuantStream/data-generator
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
1. Open `GeneratorApplication.java`
2. Click green arrow next to `main()` method
3. Select "Run 'GeneratorApplication'"

**Or in terminal:**
```bash
cd /Users/mhiteshkumar/QuantStream/data-generator
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
 :: Spring Boot ::                (v3.3.5)

2024-07-12 10:00:00 INFO  c.q.g.GeneratorApplication : Starting GeneratorApplication
2024-07-12 10:00:02 INFO  c.q.g.GeneratorApplication : Started GeneratorApplication in 2.5 seconds
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

**Fix:** Follow Step 6 above

### Issue 4: Port 9092 Connection Error

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

---

## Project Checklist

Before moving to next guide, verify:

- [ ] Project created via Spring Initializr
- [ ] Opened in IntelliJ IDEA
- [ ] Packages created: `config`, `model`, `service`
- [ ] Lombok annotation processing enabled
- [ ] `@EnableScheduling` added to main class
- [ ] `application.yml` created with Kafka config
- [ ] `mvn clean install` succeeds
- [ ] Application starts without errors
- [ ] Docker Compose running (Kafka accessible on `localhost:9092`)

---

## What's Next?

Now that the project skeleton is ready, you'll create:

1. **Tick.java** (model) — Data class representing one price update
2. **KafkaProducerConfig.java** (config) — Kafka producer configuration
3. **PriceSimulator.java** (service) — GBM price generation logic
4. **MarketDataGenerator.java** (service) — Main generator that runs every 1 second

**Next guide:** `generator-model-guide.md` (creating Tick.java)
