# Creating the Tick Data Model (Consumer Side)

## Why Duplicate the Model?

**Wait, didn't we already create Tick.java in the generator?**

Yes! And now we're creating it again in the consumer.

**Why?**

### Microservices Independence Principle

In microservices architecture, each service should be **independently deployable** and **loosely coupled**.

**Bad (Shared Code):**
```
shared-models/
  └── Tick.java

data-generator/
  └── depends on → shared-models.jar

data-consumer/
  └── depends on → shared-models.jar
```

**Problems:**
- Change `Tick.java` → Must rebuild BOTH services
- Deploy generator → Must deploy consumer too (or risk version mismatch)
- One team owns the shared library (creates bottleneck)
- Version conflicts (generator needs v1.2, consumer needs v1.1)

**Good (Duplicated):**
```
data-generator/
  └── model/Tick.java  (independent)

data-consumer/
  └── model/Tick.java  (independent)
```

**Benefits:**
- Change consumer's Tick → Only rebuild consumer
- Deploy independently
- No version conflicts
- Each team owns their code

### The Contract is the Message Format

**What's shared:** JSON structure on Kafka

```json
{
  "symbol": "AAPL",
  "price": 180.50,
  "volume": 1000.0,
  "timestamp": "2024-07-12T10:00:01Z"
}
```

**As long as both services agree on this JSON format, they're compatible.**

**Consumer's `Tick.java` could even be different:**

**Generator:**
```java
public class Tick {
    private String symbol;
    private double price;
    private double volume;
    private Instant timestamp;
}
```

**Consumer:**
```java
public class Tick {
    private String symbol;
    private double price;
    private double volume;
    private Instant timestamp;
    private String exchange;  // Extra field (ignored if not in JSON)
}
```

**Still works!** Jackson deserializes only matching fields.

### Real-World: Shared Schemas

**In production systems, you'd use:**

**Protocol Buffers (protobuf):**
```protobuf
message Tick {
  string symbol = 1;
  double price = 2;
  double volume = 3;
  int64 timestamp = 4;
}
```

- Generates code for Java, Python, Go, etc.
- Binary format (smaller, faster)
- Schema evolution (add fields without breaking)

**Avro (common with Kafka):**
```json
{
  "type": "record",
  "name": "Tick",
  "fields": [
    {"name": "symbol", "type": "string"},
    {"name": "price", "type": "double"},
    {"name": "volume", "type": "double"},
    {"name": "timestamp", "type": "long"}
  ]
}
```

- Schema registry (Confluent Schema Registry)
- Schema versioning
- Backward/forward compatibility

**Shared library (internal company):**
```java
// Published to internal Maven/Artifactory
com.company:trading-models:1.0.0
```

- Used by multiple services
- Version pinning
- Semantic versioning

### For Learning: Keep It Simple

**We're duplicating to:**
- Understand how consumer deserializes messages
- Keep services independent
- Avoid complexity of shared libraries
- Focus on Kafka/Spring Boot concepts

**It's only 10 lines of code.** In real projects with 50+ models, shared libraries make sense.

---

## What is a Tick?

**Tick** = One price update for a financial instrument at a specific moment in time.

**Example:**
```
AAPL stock at 10:00:01 → $180.50, volume 1000
```

**This is received from Kafka as:**
```json
{
  "symbol": "AAPL",
  "price": 180.50,
  "volume": 1000.0,
  "timestamp": "2024-07-12T10:00:01Z"
}
```

---

## Why We Need a Model Class?

### Without a Model (Bad)

**Consumer:**
```java
String message = kafka.receive();
// Now what? Parse JSON manually?
String symbol = // extract from string...
double price = // parse double...
```

**Problems:**
- Manual JSON parsing (error-prone)
- No type safety (what if price is a string?)
- Hard to test
- Hard to maintain

### With a Model (Good)

**Consumer:**
```java
Tick tick = kafka.receive();  // Auto-deserialized
String symbol = tick.getSymbol();
double price = tick.getPrice();
```

**Benefits:**
- Type safety (compiler checks types)
- Auto-deserialization (Spring handles JSON)
- Clean, readable code
- Easy to test

---

## What is a POJO?

**POJO** = Plain Old Java Object

**Definition:** A simple Java class with:
- Private fields
- Public getters/setters
- Constructor
- No business logic (just data)

**Example (without Lombok):**

```java
public class Tick {
    private String symbol;
    private double price;
    private double volume;
    private Instant timestamp;
    
    // Constructor
    public Tick(String symbol, double price, double volume, Instant timestamp) {
        this.symbol = symbol;
        this.price = price;
        this.volume = volume;
        this.timestamp = timestamp;
    }
    
    // Getters
    public String getSymbol() { return symbol; }
    public double getPrice() { return price; }
    public double getVolume() { return volume; }
    public Instant getTimestamp() { return timestamp; }
    
    // Setters
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public void setPrice(double price) { this.price = price; }
    public void setVolume(double volume) { this.volume = volume; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    
    // toString
    @Override
    public String toString() {
        return "Tick{symbol='" + symbol + "', price=" + price + 
               ", volume=" + volume + ", timestamp=" + timestamp + "}";
    }
    
    // equals
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tick tick = (Tick) o;
        return Double.compare(tick.price, price) == 0 &&
               Double.compare(tick.volume, volume) == 0 &&
               Objects.equals(symbol, tick.symbol) &&
               Objects.equals(timestamp, tick.timestamp);
    }
    
    // hashCode
    @Override
    public int hashCode() {
        return Objects.hash(symbol, price, volume, timestamp);
    }
}
```

**60+ lines of boilerplate!** 😱

---

## Lombok to the Rescue

**Lombok** = Library that generates boilerplate code at compile time.

**Same class with Lombok:**

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

**10 lines instead of 60!** ✅

**Lombok generates:**
- All getters (`getSymbol()`, `getPrice()`, etc.)
- All setters (`setSymbol()`, `setPrice()`, etc.)
- `toString()` method
- `equals()` method
- `hashCode()` method
- No-arg constructor
- Constructor with all fields

---

## Creating Tick.java

### Step 1: Create the File

**In IntelliJ:**

1. Right-click `src/main/java/com/quantstream/consumer/model`
2. New → Java Class
3. Name: `Tick`
4. Click OK

**File created:** `src/main/java/com/quantstream/consumer/model/Tick.java`

### Step 2: Write the Code

**Add this content:**

```java
package com.quantstream.consumer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a single price tick for a financial instrument.
 * <p>
 * This is received from Kafka and stored in QuestDB.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tick {
    
    /**
     * Symbol of the instrument (e.g., "AAPL", "BTC")
     */
    private String symbol;
    
    /**
     * Current price in USD
     */
    private double price;
    
    /**
     * Trading volume (for stocks: shares, for crypto: coins)
     */
    private double volume;
    
    /**
     * When this tick occurred (ISO-8601 timestamp)
     */
    private Instant timestamp;
}
```

**Note:** This is identical to the generator's `Tick.java`, but in a different package.

---

## Understanding Each Annotation

### @Data

```java
@Data
public class Tick {
    private String symbol;
    private double price;
    // ...
}
```

**Generates:**
- `getSymbol()`, `getPrice()`, `getVolume()`, `getTimestamp()` (getters)
- `setSymbol()`, `setPrice()`, `setVolume()`, `setTimestamp()` (setters)
- `toString()` → `"Tick(symbol=AAPL, price=180.5, volume=1000.0, timestamp=2024-07-12T10:00:01Z)"`
- `equals()` → Compares all fields
- `hashCode()` → Based on all fields

**Why we need this for consumer:**
- Jackson's JsonDeserializer calls setters to populate object from JSON
- Logging uses `toString()` to print received messages
- Testing uses `equals()` to compare expected vs actual

### @NoArgsConstructor

```java
@NoArgsConstructor
public class Tick {
    // ...
}
```

**Generates:**
```java
public Tick() {
    // Empty constructor
}
```

**Why we need this:**
- Jackson (JSON library) needs a no-arg constructor to deserialize JSON → Java object
- This is CRITICAL for the consumer to work

**How Jackson uses it:**
```java
// JSON from Kafka
{"symbol":"AAPL","price":180.5,"volume":1000,"timestamp":"2024-07-12T10:00:01Z"}

// Jackson does:
Tick tick = new Tick();      // Uses no-arg constructor
tick.setSymbol("AAPL");      // Uses setter (from @Data)
tick.setPrice(180.5);        // Uses setter
tick.setVolume(1000.0);      // Uses setter
tick.setTimestamp(...);      // Uses setter
```

**Without @NoArgsConstructor, you get:**
```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException: 
Cannot construct instance of `Tick` (no Creators, like default constructor, exist)
```

### @AllArgsConstructor

```java
@AllArgsConstructor
public class Tick {
    private String symbol;
    private double price;
    private double volume;
    private Instant timestamp;
}
```

**Generates:**
```java
public Tick(String symbol, double price, double volume, Instant timestamp) {
    this.symbol = symbol;
    this.price = price;
    this.volume = volume;
    this.timestamp = timestamp;
}
```

**Why we need this:**
- Convenient for creating test data

**Example:**
```java
// Easy to create test data
Tick expected = new Tick("AAPL", 180.50, 1000.0, Instant.parse("2024-07-12T10:00:01Z"));

// In tests
@Test
public void testDeserialize() {
    Tick tick = deserialize(json);
    assertEquals(new Tick("AAPL", 180.5, 1000.0, timestamp), tick);
}
```

---

## Understanding Each Field

### symbol (String)

```java
private String symbol;
```

**Purpose:** Identifies which asset this tick is for

**Examples:**
- Stocks: `"AAPL"`, `"MSFT"`, `"GOOGL"`, `"TSLA"`, `"AMZN"`
- Crypto: `"BTC"`, `"ETH"`, `"SOL"`, `"AVAX"`, `"MATIC"`

**Why String?**
- Symbols are text identifiers
- JSON uses strings for symbols
- QuestDB's SYMBOL type (optimized for low-cardinality strings)

**Consumer receives:**
```json
{"symbol": "AAPL", ...}
```

**Deserializes to:**
```java
tick.getSymbol() // "AAPL"
```

### price (double)

```java
private double price;
```

**Purpose:** Current price in USD

**Examples:**
- AAPL: `180.50`
- BTC: `65000.00`
- SOL: `150.75`

**Why double?**
- Floating-point number (supports decimals)
- Range: ±1.7 × 10^308 (more than enough for prices)
- Precision: 15-17 significant digits

**Consumer receives:**
```json
{"price": 180.5, ...}
```

**Deserializes to:**
```java
tick.getPrice() // 180.5
```

**Why not BigDecimal?**
- `BigDecimal` is for exact decimal arithmetic (banking, accounting)
- For reading/logging prices, `double` is sufficient
- Generator uses `double`, so we match it

### volume (double)

```java
private double volume;
```

**Purpose:** Trading volume (how much was traded)

**Examples:**
- Stocks: `1000.0` (1,000 shares)
- Crypto: `0.5` (0.5 BTC)

**Why double?**
- Crypto can have fractional amounts (0.0001 BTC)
- Matches generator's field type

**Consumer receives:**
```json
{"volume": 1000.0, ...}
```

**Deserializes to:**
```java
tick.getVolume() // 1000.0
```

### timestamp (Instant)

```java
private Instant timestamp;
```

**Purpose:** When this tick occurred (UTC timezone)

**Example:**
```java
Instant.parse("2024-07-12T10:00:01.123456Z")
```

**What is Instant?**
- Java 8+ time API class
- Represents a point in time (nanosecond precision)
- Always UTC (no timezone confusion)

**Consumer receives:**
```json
{"timestamp": "2024-07-12T10:00:01.123456Z", ...}
```

**Jackson automatically converts string → Instant:**
```java
tick.getTimestamp() // Instant object
```

**Why Instant?**
- Modern Java time API (better than `java.util.Date`)
- Immutable (thread-safe)
- Nanosecond precision
- QuestDB expects `Instant` for TIMESTAMP columns

**Format:**
```
2024-07-12T10:00:01.123456789Z
 │    │  │ │  │  │  └─────────── Nanoseconds
 │    │  │ │  │  └────────────── Seconds
 │    │  │ │  └───────────────── Minutes
 │    │  │ └──────────────────── Hours (24-hour)
 │    │  └─────────────────────── Day
 │    └────────────────────────── Month
 └─────────────────────────────── Year
```

---

## How Deserialization Works

### From JSON to Java Object (Consumer)

**Kafka message:**
```json
{
  "symbol": "AAPL",
  "price": 180.5,
  "volume": 1000.0,
  "timestamp": "2024-07-12T10:00:01Z"
}
```

**Spring's JsonDeserializer does:**

1. **Reads JSON bytes from Kafka**
   ```
   7B 22 73 79 6D 62 6F 6C 22 3A 22 41 41 50 4C 22 ...
   ```

2. **Parses JSON to object structure**
   ```json
   {
     "symbol": "AAPL",
     "price": 180.5,
     "volume": 1000.0,
     "timestamp": "2024-07-12T10:00:01Z"
   }
   ```

3. **Creates empty Tick object**
   ```java
   Tick tick = new Tick();  // Uses @NoArgsConstructor
   ```

4. **Calls setters for each field**
   ```java
   tick.setSymbol("AAPL");
   tick.setPrice(180.5);
   tick.setVolume(1000.0);
   tick.setTimestamp(Instant.parse("2024-07-12T10:00:01Z"));
   ```

5. **Returns populated object**
   ```java
   @KafkaListener(topics = "market-data")
   public void consume(Tick tick) {
       // tick is now a fully populated Java object
       System.out.println(tick.getSymbol());  // "AAPL"
       System.out.println(tick.getPrice());   // 180.5
   }
   ```

### What if JSON has Extra Fields?

**Kafka message:**
```json
{
  "symbol": "AAPL",
  "price": 180.5,
  "volume": 1000.0,
  "timestamp": "2024-07-12T10:00:01Z",
  "exchange": "NASDAQ"  ← Extra field
}
```

**Result:**
```java
Tick tick = // deserialized
tick.getSymbol()  // "AAPL" ✅
tick.getPrice()   // 180.5 ✅
// "exchange" is ignored (no field in Tick.java)
```

**Jackson ignores unknown fields by default.**

### What if JSON has Missing Fields?

**Kafka message:**
```json
{
  "symbol": "AAPL",
  "price": 180.5
}
```

**Result:**
```java
Tick tick = // deserialized
tick.getSymbol()  // "AAPL" ✅
tick.getPrice()   // 180.5 ✅
tick.getVolume()  // 0.0 (default double value)
tick.getTimestamp()  // null
```

**Missing fields get default values (0, null).**

### What if JSON has Wrong Type?

**Kafka message:**
```json
{
  "symbol": "AAPL",
  "price": "not-a-number",
  "volume": 1000.0,
  "timestamp": "2024-07-12T10:00:01Z"
}
```

**Result:**
```
com.fasterxml.jackson.databind.exc.InvalidFormatException: 
Cannot deserialize value of type `double` from String "not-a-number"
```

**Jackson throws exception → Kafka listener error handler is called.**

---

## Testing the Model

### Compile and Check

**In IntelliJ:**
- Build → Build Project
- Should see no errors

**In terminal:**
```bash
cd /Users/mhiteshkumar/QuantStream/data-consumer
mvn compile
```

**Expected output:**
```
[INFO] BUILD SUCCESS
```

### Verify Lombok Generated Methods

**Open `Tick.java` in IntelliJ**

**View generated code:**
1. Navigate → Structure (Cmd+7 on Mac)
2. See generated methods:
   ```
   Tick
   ├── symbol: String
   ├── price: double
   ├── volume: double
   ├── timestamp: Instant
   ├── Tick()                    ← @NoArgsConstructor
   ├── Tick(String, double, double, Instant)  ← @AllArgsConstructor
   ├── getSymbol(): String       ← @Data
   ├── getPrice(): double        ← @Data
   ├── getVolume(): double       ← @Data
   ├── getTimestamp(): Instant   ← @Data
   ├── setSymbol(String)         ← @Data
   ├── setPrice(double)          ← @Data
   ├── setVolume(double)         ← @Data
   ├── setTimestamp(Instant)     ← @Data
   ├── toString(): String        ← @Data
   ├── equals(Object): boolean   ← @Data
   └── hashCode(): int           ← @Data
   ```

### Test Deserialization Manually

**Create a test class:**

`src/test/java/com/quantstream/consumer/model/TickTest.java`

```java
package com.quantstream.consumer.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TickTest {
    
    @Test
    public void testDeserialization() throws Exception {
        // JSON from Kafka
        String json = """
            {
              "symbol": "AAPL",
              "price": 180.5,
              "volume": 1000.0,
              "timestamp": "2024-07-12T10:00:01Z"
            }
            """;
        
        // Deserialize
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        Tick tick = mapper.readValue(json, Tick.class);
        
        // Verify
        assertEquals("AAPL", tick.getSymbol());
        assertEquals(180.5, tick.getPrice());
        assertEquals(1000.0, tick.getVolume());
        assertEquals(Instant.parse("2024-07-12T10:00:01Z"), tick.getTimestamp());
        
        System.out.println("Deserialized: " + tick);
    }
}
```

**Run test:**
```bash
mvn test -Dtest=TickTest
```

**Expected output:**
```
Deserialized: Tick(symbol=AAPL, price=180.5, volume=1000.0, timestamp=2024-07-12T10:00:01Z)
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

---

## Common Issues

### Issue 1: "Cannot resolve symbol @Data"

**Error:** Red underline on `@Data`

**Cause:** Lombok not added to project

**Fix:** Check `pom.xml` has Lombok dependency:
```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

Run: `mvn clean install`

### Issue 2: "Cannot resolve method getSymbol()"

**Error:** When calling `tick.getSymbol()`, IntelliJ shows error

**Cause:** Annotation processing not enabled

**Fix:** Follow "Enable Lombok Annotation Processing" in project setup guide

### Issue 3: "Cannot find symbol Instant"

**Error:** Red underline on `Instant`

**Cause:** Import missing

**Fix:** IntelliJ auto-imports, or manually add:
```java
import java.time.Instant;
```

### Issue 4: InvalidDefinitionException on Deserialization

**Error:**
```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException: 
Cannot construct instance of `Tick` (no Creators, like default constructor, exist)
```

**Cause:** Missing `@NoArgsConstructor`

**Fix:** Ensure you have `@NoArgsConstructor` annotation on `Tick.java`

### Issue 5: Timestamp Not Deserializing Correctly

**Error:**
```
Cannot deserialize value of type `java.time.Instant` from String "2024-07-12T10:00:01Z"
```

**Cause:** Jackson doesn't know how to deserialize `Instant`

**Fix:** Add dependency to `pom.xml`:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

**Spring Boot auto-configures this**, so should work by default.

### Issue 6: Fields are Null After Deserialization

**Symptom:**
```java
tick.getSymbol() // null
tick.getPrice()  // 0.0
```

**Cause:** Field names in JSON don't match Java field names

**Check:**
- JSON: `"Symbol"` vs Java: `"symbol"` (case matters!)
- JSON: `"tickSymbol"` vs Java: `"symbol"` (name mismatch)

**Fix:** Ensure JSON field names exactly match Java field names

**Or use @JsonProperty:**
```java
@JsonProperty("tickSymbol")
private String symbol;
```

---

## Summary

**Tick.java:**
- **POJO** (Plain Old Java Object) representing one price update
- **4 fields:** symbol, price, volume, timestamp
- **Identical to generator's Tick.java**, but in consumer package
- **Lombok annotations:**
  - `@Data` → generates getters, setters, toString, equals, hashCode
  - `@NoArgsConstructor` → generates empty constructor (CRITICAL for JSON deserialization)
  - `@AllArgsConstructor` → generates constructor with all fields (for test data)

**Why each field:**
- **symbol (String):** Which asset ("AAPL", "BTC")
- **price (double):** Current price in USD
- **volume (double):** Trading volume (shares or coins)
- **timestamp (Instant):** When this tick occurred (UTC, nanosecond precision)

**Deserialization flow:**
1. Kafka → **bytes** → JsonDeserializer → **JSON object**
2. Jackson calls `new Tick()` (no-arg constructor)
3. Jackson calls setters for each JSON field
4. Returns fully populated **Java object**

**Why duplicate?**
- **Microservices independence:** Each service owns its own code
- **Deploy independently:** Change consumer without touching generator
- **Contract is JSON:** As long as JSON format matches, services are compatible
- **For learning:** Simpler than shared libraries/protobuf

**Next:** Create `KafkaConsumerConfig.java` to configure Kafka consumer.
