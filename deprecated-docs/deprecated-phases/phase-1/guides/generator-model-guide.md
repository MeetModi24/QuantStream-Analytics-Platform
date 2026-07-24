# Creating the Tick Data Model

## What is a Tick?

**Tick** = One price update for a financial instrument at a specific moment in time.

**Example:**
```
AAPL stock at 10:00:01 → $180.50, volume 1000
```

**This is stored as:**
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

**Generator:**
```java
String message = "{\"symbol\":\"AAPL\",\"price\":180.50,\"volume\":1000.0}";
kafka.send("market-data", message);
```

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

**Generator:**
```java
Tick tick = new Tick("AAPL", 180.50, 1000.0, Instant.now());
kafka.send("market-data", tick);
```

**Consumer:**
```java
Tick tick = kafka.receive();  // Auto-deserialized
String symbol = tick.getSymbol();
double price = tick.getPrice();
```

**Benefits:**
- Type safety (compiler checks types)
- Auto-serialization (Spring handles JSON)
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
- Constructor with all fields

---

## Creating Tick.java

### Step 1: Create the File

**In IntelliJ:**

1. Right-click `src/main/java/com/quantstream/generator/model`
2. New → Java Class
3. Name: `Tick`
4. Click OK

**File created:** `src/main/java/com/quantstream/generator/model/Tick.java`

### Step 2: Write the Code

**Add this content:**

```java
package com.quantstream.generator.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a single price tick for a financial instrument.
 * <p>
 * This is sent to Kafka and stored in QuestDB.
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

**Why we need this:**
- Spring's JsonSerializer calls getters to convert object to JSON
- Logging uses `toString()` to print object
- Testing uses `equals()` to compare objects

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
- Without this, consumer can't deserialize messages

**Example:**
```java
// JSON from Kafka
{"symbol":"AAPL","price":180.5,"volume":1000,"timestamp":"2024-07-12T10:00:01Z"}

// Jackson does:
Tick tick = new Tick();      // Uses no-arg constructor
tick.setSymbol("AAPL");      // Uses setter
tick.setPrice(180.5);        // Uses setter
tick.setVolume(1000.0);      // Uses setter
tick.setTimestamp(...);      // Uses setter
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
- Convenient for creating objects in code

**Example:**
```java
// Easy to create
Tick tick = new Tick("AAPL", 180.50, 1000.0, Instant.now());

// vs without constructor:
Tick tick = new Tick();
tick.setSymbol("AAPL");
tick.setPrice(180.50);
tick.setVolume(1000.0);
tick.setTimestamp(Instant.now());
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
- QuestDB's SYMBOL type (optimized for low-cardinality strings)

**Could be enum?**
```java
enum Symbol { AAPL, MSFT, GOOGL, ... }
```

**We use String because:**
- Easier to add new symbols (no code change)
- JSON serialization is simpler
- QuestDB SYMBOL type expects String

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

**Example precision:**
```java
180.5    → 4 significant digits (OK)
65432.10 → 7 significant digits (OK)
0.00001  → 1 significant digit (OK)
```

**Why not BigDecimal?**
- `BigDecimal` is for exact decimal arithmetic (banking, accounting)
- We're simulating prices, not doing financial calculations
- `double` is faster and simpler

**Why not int?**
- Can't represent decimals
- AAPL at $180.50 would become $180 (lose precision)

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
- Stocks can have fractional shares (0.5 shares via fractional trading platforms)

**Why not long?**
- `long` is integer-only
- Can't represent 0.5 BTC

**In our project:**
- We generate random volume between 100 and 10,000
- Simulates realistic trading activity

### timestamp (Instant)

```java
private Instant timestamp;
```

**Purpose:** When this tick occurred (UTC timezone)

**Example:**
```java
Instant.now()  // 2024-07-12T10:00:01.123456Z
```

**What is Instant?**
- Java 8+ time API class
- Represents a point in time (nanosecond precision)
- Always UTC (no timezone confusion)

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

**Why Instant?**
- Modern Java time API (better than `java.util.Date`)
- Immutable (thread-safe)
- Nanosecond precision (better than millisecond)
- UTC (no timezone ambiguity)

**Why not String?**
- Can't do time arithmetic
- Can't compare times easily
- Type safety (String could be any format)

**Why not long (epoch millis)?**
- Less readable in logs
- Lose precision (milliseconds vs nanoseconds)

**How it's used:**

**Generator:**
```java
Tick tick = new Tick("AAPL", 180.50, 1000.0, Instant.now());
// timestamp = current time
```

**JSON serialization:**
```json
{
  "symbol": "AAPL",
  "price": 180.5,
  "volume": 1000.0,
  "timestamp": "2024-07-12T10:00:01.123456Z"
}
```

**QuestDB storage:**
- Stored as TIMESTAMP type
- Nanosecond precision
- Used for partitioning (by day)

---

## How Serialization Works

### From Java Object to JSON (Producer)

**Code:**
```java
Tick tick = new Tick("AAPL", 180.50, 1000.0, Instant.now());
kafkaTemplate.send("market-data", "AAPL", tick);
```

**Spring's JsonSerializer does:**

1. **Calls getters:**
   ```java
   String symbol = tick.getSymbol();        // "AAPL"
   double price = tick.getPrice();          // 180.5
   double volume = tick.getVolume();        // 1000.0
   Instant timestamp = tick.getTimestamp(); // 2024-07-12T10:00:01Z
   ```

2. **Converts to JSON:**
   ```json
   {
     "symbol": "AAPL",
     "price": 180.5,
     "volume": 1000.0,
     "timestamp": "2024-07-12T10:00:01Z"
   }
   ```

3. **Converts to bytes:**
   ```
   7B 22 73 79 6D 62 6F 6C 22 3A 22 41 41 50 4C 22 ...
   (this is what Kafka stores)
   ```

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

1. **Creates empty object:**
   ```java
   Tick tick = new Tick();  // Uses @NoArgsConstructor
   ```

2. **Calls setters:**
   ```java
   tick.setSymbol("AAPL");
   tick.setPrice(180.5);
   tick.setVolume(1000.0);
   tick.setTimestamp(Instant.parse("2024-07-12T10:00:01Z"));
   ```

3. **Returns object:**
   ```java
   @KafkaListener(topics = "market-data")
   public void consume(Tick tick) {
       // tick is now a Java object
       System.out.println(tick.getSymbol());  // "AAPL"
   }
   ```

---

## Testing the Model

### Compile and Check

**In IntelliJ:**
- Build → Build Project
- Should see no errors

**In terminal:**
```bash
cd /Users/mhiteshkumar/QuantStream/data-generator
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

### Test in Main Class

**Temporarily add to `GeneratorApplication.java`:**

```java
package com.quantstream.generator;

import com.quantstream.generator.model.Tick;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;

@SpringBootApplication
@EnableScheduling
public class GeneratorApplication {
    public static void main(String[] args) {
        // Test Tick model
        Tick tick = new Tick("AAPL", 180.50, 1000.0, Instant.now());
        System.out.println("Created tick: " + tick);
        System.out.println("Symbol: " + tick.getSymbol());
        System.out.println("Price: " + tick.getPrice());
        
        // Start Spring Boot
        SpringApplication.run(GeneratorApplication.class, args);
    }
}
```

**Run application:**

**Expected output:**
```
Created tick: Tick(symbol=AAPL, price=180.5, volume=1000.0, timestamp=2024-07-12T10:00:01.123456Z)
Symbol: AAPL
Price: 180.5

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
...
Started GeneratorApplication in 2.5 seconds
```

**Remove test code after verification.**

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

### Issue 4: Timestamp Not Serializing Correctly

**Error:** JSON shows timestamp as object instead of string

**Example wrong output:**
```json
{
  "timestamp": {
    "epochSecond": 1720786801,
    "nano": 123456000
  }
}
```

**Cause:** Jackson doesn't know how to serialize `Instant`

**Fix:** Add dependency to `pom.xml`:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

**Spring Boot auto-configures this**, so should work by default.

---

## Summary

**Tick.java:**
- **POJO** (Plain Old Java Object) representing one price update
- **4 fields:** symbol, price, volume, timestamp
- **Lombok annotations:**
  - `@Data` → generates getters, setters, toString, equals, hashCode
  - `@NoArgsConstructor` → generates empty constructor (for JSON deserialization)
  - `@AllArgsConstructor` → generates constructor with all fields (for convenience)

**Why each field:**
- **symbol (String):** Which asset ("AAPL", "BTC")
- **price (double):** Current price in USD
- **volume (double):** Trading volume (shares or coins)
- **timestamp (Instant):** When this tick occurred (UTC, nanosecond precision)

**Serialization flow:**
1. **Java object** → JsonSerializer → **JSON** → **bytes** → Kafka
2. Kafka → **bytes** → JsonDeserializer → **JSON** → **Java object**

**Next:** Create `KafkaProducerConfig.java` to configure Kafka producer.
