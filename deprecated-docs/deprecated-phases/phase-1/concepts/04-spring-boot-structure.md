# Spring Boot Project Structure

## What is Spring Boot?

**Spring Boot** is a framework that makes it easy to create stand-alone, production-ready Java applications.

**Think of it as:** A toolkit that handles all the boring infrastructure code (HTTP server, database connections, configuration) so you focus on business logic.

---

## Why Spring Boot?

### Without Spring Boot (Plain Java)

```java
// You write all this boilerplate:
public class Main {
    public static void main(String[] args) {
        // Start HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        // Create database connection pool
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:postgresql://localhost:5432/db");
        dataSource.setUsername("admin");
        dataSource.setPassword("password");
        
        // Create Kafka producer
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.JsonSerializer");
        KafkaProducer producer = new KafkaProducer(props);
        
        // Wire everything together manually
        TickService service = new TickService(dataSource, producer);
        TickController controller = new TickController(service);
        
        // Register HTTP endpoints
        server.createContext("/api/ticks", controller::handleRequest);
        
        // Start server
        server.start();
    }
}
```

**100+ lines of setup code before writing any business logic.**

### With Spring Boot

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

**3 lines.** Spring Boot auto-configures everything.

---

## Core Concepts

### 1. Dependency Injection (DI)

**Problem:**
```java
public class TickController {
    private TickService service;
    
    public TickController() {
        // Who creates the service?
        // What if service needs a database?
        // What if service needs Kafka?
        this.service = new TickService(???, ???);
    }
}
```

**Solution: Let Spring create and wire objects**

```java
@RestController
public class TickController {
    
    @Autowired  // Spring injects TickService
    private TickService service;
    
    // Spring calls this constructor
    public TickController(TickService service) {
        this.service = service;
    }
}

@Service
public class TickService {
    
    @Autowired  // Spring injects dependencies
    private TickRepository repository;
    private KafkaTemplate kafkaTemplate;
    
    public TickService(TickRepository repository, KafkaTemplate kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }
}
```

**Spring manages the lifecycle:**
1. Creates TickRepository
2. Creates KafkaTemplate
3. Creates TickService (injects repository + kafkaTemplate)
4. Creates TickController (injects service)

**You never call `new`**. Spring does it.

### 2. Annotations Drive Behavior

**Annotations = Metadata telling Spring what to do**

```java
@SpringBootApplication  // This is the entry point
public class Application { ... }

@RestController         // This class handles HTTP requests
public class TickController { ... }

@Service                // This class has business logic
public class TickService { ... }

@Repository             // This class accesses database
public class TickRepository { ... }

@Configuration          // This class provides configuration
public class KafkaConfig { ... }

@Scheduled(fixedRate = 1000)  // Run this method every 1 second
public void generateTicks() { ... }

@KafkaListener(topics = "market-data")  // This method consumes Kafka messages
public void consume(Tick tick) { ... }
```

**Spring scans your code for these annotations and sets up the application accordingly.**

### 3. Configuration via application.yml

Instead of hardcoding configuration:

```java
// Bad
String kafkaUrl = "localhost:9092";
String dbUrl = "jdbc:postgresql://localhost:5432/db";
```

Use `application.yml`:

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
  datasource:
    url: jdbc:postgresql://localhost:5432/db
    username: admin
    password: quest
```

Spring automatically reads this and configures Kafka and database.

**Benefits:**
- Change config without recompiling
- Different config for dev/prod
- Environment variables override: `--spring.kafka.bootstrap-servers=prod-kafka:9092`

---

## Standard Project Structure

```
data-generator/
├── pom.xml                          # Maven build file (dependencies)
└── src/
    └── main/
        ├── java/
        │   └── com/quantstream/generator/
        │       ├── GeneratorApplication.java       # Main class
        │       ├── config/
        │       │   └── KafkaProducerConfig.java   # Kafka configuration
        │       ├── model/
        │       │   └── Tick.java                  # Data model
        │       └── service/
        │           ├── MarketDataGenerator.java   # Business logic
        │           └── PriceSimulator.java        # GBM implementation
        └── resources/
            └── application.yml                     # Configuration file
```

### Package Naming Convention

**Base package:** `com.quantstream.generator`
- Reverse domain name style (like Java packages)
- `com.quantstream` = your organization/project
- `generator` = this specific service

**Sub-packages:**
- `config` = Configuration classes (@Configuration)
- `model` = Data classes (POJOs)
- `service` = Business logic (@Service)
- `controller` = HTTP endpoints (@RestController) - not in generator
- `repository` = Database access (@Repository) - not in generator
- `consumer` = Kafka consumers (@KafkaListener) - not in generator

---

## Key Annotations Explained

### @SpringBootApplication

```java
@SpringBootApplication
public class GeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeneratorApplication.class, args);
    }
}
```

**What it does:**
- `@Configuration`: Marks this as a config source
- `@EnableAutoConfiguration`: Auto-configures Spring based on classpath
- `@ComponentScan`: Scans for @Component, @Service, @Repository, etc.

**In simple terms:** "This is a Spring Boot app, set up everything automatically"

### @Configuration

```java
@Configuration
public class KafkaProducerConfig {
    
    @Bean
    public KafkaTemplate<String, Tick> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
    
    @Bean
    public ProducerFactory<String, Tick> producerFactory() {
        // Configure and return ProducerFactory
    }
}
```

**What it does:**
- Tells Spring "this class provides beans (objects)"
- `@Bean` methods return objects that Spring manages
- Other classes can `@Autowired` these beans

**When to use:** Creating Kafka producers/consumers, database connections, custom objects

### @Service

```java
@Service
public class MarketDataGenerator {
    
    @Autowired
    private KafkaTemplate<String, Tick> kafkaTemplate;
    
    @Scheduled(fixedRate = 1000)
    public void generateTicks() {
        // Business logic here
    }
}
```

**What it does:**
- Marks this class as a service (business logic)
- Spring creates one instance (singleton)
- Can be injected into other classes

**When to use:** Business logic classes

### @Component

```java
@Component
public class PriceSimulator {
    // Helper class
}
```

**What it does:**
- Generic annotation for any Spring-managed class
- Similar to @Service, but for non-service classes

**When to use:** Utility classes, helpers

**Note:** `@Service` is just `@Component` with better semantics (tells you it's a service layer)

### @Scheduled

```java
@Scheduled(fixedRate = 1000)  // Every 1 second
public void generateTicks() {
    // This runs automatically every 1 second
}
```

**What it does:**
- Spring calls this method on a schedule
- `fixedRate = 1000`: Every 1000ms (1 second)
- `cron = "0 0 * * * *"`: Cron expression (e.g., every hour)

**When to use:** Periodic tasks (our data generator)

**Important:** Add `@EnableScheduling` to main application class:

```java
@SpringBootApplication
@EnableScheduling
public class GeneratorApplication { ... }
```

### @KafkaListener

```java
@KafkaListener(topics = "market-data", groupId = "database-group")
public void consume(Tick tick) {
    // Called automatically when message arrives
}
```

**What it does:**
- Spring calls this method when Kafka message arrives
- Automatically deserializes message to Tick object
- Manages offsets (tracks which messages processed)

**When to use:** Kafka consumers

---

## How Spring Boot Starts Up

### 1. Main Method Runs

```java
public static void main(String[] args) {
    SpringApplication.run(GeneratorApplication.class, args);
}
```

### 2. Spring Scans for Components

Scans `com.quantstream.generator` package and sub-packages:
- Finds `@Configuration` classes
- Finds `@Service` classes
- Finds `@Component` classes
- Finds `@Bean` methods

### 3. Spring Creates Beans

```
1. Creates KafkaProducerConfig
2. Calls producerFactory() @Bean method → creates ProducerFactory
3. Calls kafkaTemplate() @Bean method → creates KafkaTemplate
4. Creates PriceSimulator (has @Component)
5. Creates MarketDataGenerator (has @Service)
   - Injects KafkaTemplate (created in step 3)
```

### 4. Spring Starts Scheduled Tasks

Finds methods with `@Scheduled`:
- `MarketDataGenerator.generateTicks()`
- Starts timer: calls method every 1 second

### 5. Application Ready

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.3.0)

2024-07-12 10:00:00 INFO  o.s.b.w.e.t.TomcatWebServer : Tomcat started on port(s): 8080
2024-07-12 10:00:00 INFO  c.q.g.GeneratorApplication  : Started GeneratorApplication in 2.5 seconds
```

Application is now running!

---

## Dependency Injection Example

Let's trace how objects are created:

**Code:**
```java
// Config
@Configuration
public class KafkaProducerConfig {
    @Bean
    public KafkaTemplate<String, Tick> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}

// Service
@Service
public class MarketDataGenerator {
    @Autowired
    private KafkaTemplate<String, Tick> kafkaTemplate;
    
    @Scheduled(fixedRate = 1000)
    public void generateTicks() {
        kafkaTemplate.send("market-data", "AAPL", tick);
    }
}
```

**What Spring does:**

1. **Startup:**
   ```
   Spring: "I see KafkaProducerConfig with @Configuration"
   Spring: "I'll call kafkaTemplate() method"
   Spring: "Now I have a KafkaTemplate bean"
   Spring: "I'll store this in my application context"
   ```

2. **Creating Service:**
   ```
   Spring: "I see MarketDataGenerator with @Service"
   Spring: "It needs KafkaTemplate"
   Spring: "I have KafkaTemplate in my context!"
   Spring: "I'll inject it"
   Spring: "MarketDataGenerator is ready"
   ```

3. **Runtime:**
   ```
   Timer: "1 second passed"
   Spring: "Call generateTicks() on MarketDataGenerator"
   MarketDataGenerator: "Use kafkaTemplate to send message"
   KafkaTemplate: "Message sent to Kafka"
   ```

**You never manually created any objects.** Spring did everything.

---

## Common Patterns

### Constructor Injection (Recommended)

```java
@Service
public class TickService {
    private final TickRepository repository;
    private final KafkaTemplate kafkaTemplate;
    
    // Spring calls this constructor
    public TickService(TickRepository repository, KafkaTemplate kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }
}
```

**Why constructor?**
- Dependencies are immutable (final)
- Clear what dependencies are required
- Easy to test (can pass mocks)

### Field Injection (Not Recommended)

```java
@Service
public class TickService {
    @Autowired
    private TickRepository repository;  // Injected by Spring
    
    @Autowired
    private KafkaTemplate kafkaTemplate;
}
```

**Works, but:**
- Can't make fields final
- Harder to test
- Dependencies are hidden

### Lombok (Simplifies Code)

```java
@Service
@RequiredArgsConstructor  // Lombok generates constructor for final fields
public class TickService {
    private final TickRepository repository;
    private final KafkaTemplate kafkaTemplate;
    
    // Constructor auto-generated by Lombok
}
```

**We'll use this pattern** (cleaner code)

---

## pom.xml (Maven Dependencies)

```xml
<dependencies>
    <!-- Spring Boot Starter (includes Spring Core, DI, etc.) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    
    <!-- Spring Kafka (includes Kafka clients + Spring integration) -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>
    
    <!-- Lombok (reduces boilerplate) -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

**Maven downloads these JARs automatically** when you run `mvn install`

---

## Application Configuration

### application.yml

```yaml
spring:
  application:
    name: data-generator
  
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

logging:
  level:
    com.quantstream: DEBUG    # Our code: debug level
    org.springframework: INFO  # Spring: info level
    org.apache.kafka: WARN     # Kafka: only warnings
```

**Spring reads this automatically** and configures Kafka producer

---

## Summary

**Spring Boot = Framework for building Java applications**

**Key concepts:**
1. **Dependency Injection:** Spring creates and wires objects
2. **Annotations:** Drive behavior (@Service, @Scheduled, @KafkaListener)
3. **Auto-configuration:** Spring sets up infrastructure automatically
4. **Configuration:** application.yml for settings

**Project structure:**
- `Application.java`: Main class with @SpringBootApplication
- `config/`: Configuration classes with @Configuration
- `model/`: Data classes (POJOs)
- `service/`: Business logic with @Service
- `application.yml`: Configuration

**For Phase 1:**
- **Generator:** Uses @Scheduled to generate ticks, @Service for logic
- **Consumer:** Uses @KafkaListener to consume ticks, @Repository for database

**Next:** Implementation guides to actually write this code!
