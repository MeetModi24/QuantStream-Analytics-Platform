# GBM Price Simulator Implementation Guide

## What You're Building

**PriceSimulator** is a class that generates realistic price movements using Geometric Brownian Motion (GBM).

**Input:** Current price, drift (μ), volatility (σ)  
**Output:** Next price (one second later)

**Example:**
```java
PriceSimulator aapl = new PriceSimulator(180.00, 0.08, 0.25);
double price1 = aapl.generateNextPrice();  // 180.05
double price2 = aapl.generateNextPrice();  // 180.12
double price3 = aapl.generateNextPrice();  // 180.08
```

---

## GBM Formula Recap

From `concepts/03-gbm-explained.md`:

```
newPrice = currentPrice × exp((μ - 0.5 × σ²) × dt + σ × √dt × dW)

Where:
- μ = drift (expected return, e.g., 0.08 = 8% annual)
- σ = volatility (randomness, e.g., 0.25 = 25% annual)
- dt = time step (1 second as fraction of year)
- dW = random Gaussian number (mean=0, std=1)
- exp = exponential function (ensures positive prices)
```

---

## Creating PriceSimulator.java

### Step 1: Create the File

**In IntelliJ:**

1. Right-click `src/main/java/com/quantstream/generator/service`
2. New → Java Class
3. Name: `PriceSimulator`
4. Click OK

### Step 2: Write the Code

```java
package com.quantstream.generator.service;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Generates realistic price movements using Geometric Brownian Motion (GBM).
 * <p>
 * GBM Formula: S(t+dt) = S(t) × exp((μ - 0.5σ²)dt + σ√dt × dW)
 * Where:
 * - S(t) = current price
 * - μ = drift (expected return, e.g., 0.08 = 8% annual)
 * - σ = volatility (randomness, e.g., 0.25 = 25% annual)
 * - dt = time step (1 second as fraction of year)
 * - dW = random Gaussian number from N(0,1)
 */
public class PriceSimulator {
    
    private static final Logger log = LoggerFactory.getLogger(PriceSimulator.class);
    
    // Number of seconds in a year (365 days × 24 hours × 60 minutes × 60 seconds)
    private static final double SECONDS_PER_YEAR = 365.0 * 24.0 * 60.0 * 60.0;
    
    // Time step: 1 second as fraction of year
    private static final double DT = 1.0 / SECONDS_PER_YEAR;
    
    // Random number generator for Gaussian (normal) distribution
    private final Random random = new Random();
    
    // Current price (updated after each tick)
    @Getter
    private double currentPrice;
    
    // Drift: expected return (annual)
    private final double drift;
    
    // Volatility: standard deviation of returns (annual)
    private final double volatility;
    
    // Pre-calculated drift term (optimization)
    private final double driftTerm;
    
    // Pre-calculated volatility term (optimization)
    private final double volatilityTerm;
    
    /**
     * Creates a new price simulator.
     *
     * @param initialPrice Starting price (e.g., 180.00 for AAPL)
     * @param drift        Annual drift/trend (e.g., 0.08 = 8% annual growth)
     * @param volatility   Annual volatility (e.g., 0.25 = 25% annual volatility)
     */
    public PriceSimulator(double initialPrice, double drift, double volatility) {
        if (initialPrice <= 0) {
            throw new IllegalArgumentException("Initial price must be positive, got: " + initialPrice);
        }
        if (volatility < 0) {
            throw new IllegalArgumentException("Volatility cannot be negative, got: " + volatility);
        }
        
        this.currentPrice = initialPrice;
        this.drift = drift;
        this.volatility = volatility;
        
        // Pre-calculate constant terms (avoid recalculating every tick)
        this.driftTerm = (drift - 0.5 * volatility * volatility) * DT;
        this.volatilityTerm = volatility * Math.sqrt(DT);
        
        log.debug("Created PriceSimulator: initialPrice={}, drift={}, volatility={}", 
                  initialPrice, drift, volatility);
    }
    
    /**
     * Generates the next price using GBM.
     * <p>
     * This method:
     * 1. Generates a random Gaussian number (dW)
     * 2. Calculates price change using GBM formula
     * 3. Updates and returns current price
     *
     * @return New price (one second later)
     */
    public double generateNextPrice() {
        // Generate random Gaussian number: mean=0, std=1
        // ~68% of values between -1 and +1
        // ~95% of values between -2 and +2
        double dW = random.nextGaussian();
        
        // GBM formula: newPrice = currentPrice × exp(driftTerm + volatilityTerm × dW)
        // 
        // Breaking it down:
        // 1. driftTerm = (μ - 0.5σ²) × dt
        //    This is the deterministic component (trend)
        // 
        // 2. volatilityTerm × dW = σ × √dt × dW
        //    This is the random component (noise)
        // 
        // 3. exp(driftTerm + volatilityTerm × dW)
        //    Exponential ensures price is always positive
        //    Converts additive changes to multiplicative (compound growth)
        double changeMultiplier = Math.exp(driftTerm + volatilityTerm * dW);
        
        // Update current price
        currentPrice = currentPrice * changeMultiplier;
        
        return currentPrice;
    }
    
    /**
     * Generates the next price with a specified volume.
     * Volume varies randomly between 50% and 150% of base volume.
     *
     * @param baseVolume Base volume (e.g., 1000.0)
     * @return Random volume around base volume
     */
    public double generateVolume(double baseVolume) {
        // Random multiplier between 0.5 and 1.5
        // 0.5 = 50% of base volume
        // 1.5 = 150% of base volume
        double multiplier = 0.5 + random.nextDouble();
        return baseVolume * multiplier;
    }
}
```

---

## Understanding Each Part

### Class Fields

#### SECONDS_PER_YEAR

```java
private static final double SECONDS_PER_YEAR = 365.0 * 24.0 * 60.0 * 60.0;
```

**Value:** 31,536,000 seconds

**Why we need this:**
- Drift and volatility are **annual** parameters
- We update every **second**
- Need to convert annual to per-second

**Example:**
```
μ = 0.08 (8% annual drift)
dt = 1 / 31,536,000 (one second)
μ × dt = 0.08 / 31,536,000 = 0.00000000254
(tiny change per second)
```

#### DT

```java
private static final double DT = 1.0 / SECONDS_PER_YEAR;
```

**Value:** 0.0000000317 (approximately 3.17 × 10⁻⁸)

**What it means:** One second as a fraction of a year

**Why static final:**
- Same for all simulators
- Never changes
- Calculated once at class load

#### random

```java
private final Random random = new Random();
```

**What:** Java's random number generator

**Why per-instance:**
- Each simulator has independent randomness
- AAPL and BTC move independently

**Methods used:**
- `random.nextGaussian()` → returns number from normal distribution (mean=0, std=1)

#### currentPrice

```java
@Getter
private double currentPrice;
```

**What:** Current price (updated after each tick)

**Why @Getter:**
- Lombok generates `getCurrentPrice()` method
- External code can read (but not write) current price

**Lifecycle:**
```
Initial: 180.00
After tick 1: 180.05
After tick 2: 180.12
After tick 3: 180.08
...
```

#### drift and volatility

```java
private final double drift;
private final double volatility;
```

**drift:** Expected annual return (μ)
- AAPL: 0.08 (8% annual growth)
- BTC: 0.20 (20% annual growth)

**volatility:** Annual standard deviation (σ)
- AAPL: 0.25 (25% annual volatility)
- BTC: 0.50 (50% annual volatility)

**Why final:**
- Set once in constructor
- Never changes for this simulator

#### driftTerm and volatilityTerm

```java
private final double driftTerm;
private final double volatilityTerm;
```

**Optimization:** Pre-calculate constant parts of formula

**driftTerm:**
```java
this.driftTerm = (drift - 0.5 * volatility * volatility) * DT;
```

This is `(μ - 0.5σ²) × dt` from GBM formula.

**Example (AAPL: drift=0.08, volatility=0.25):**
```
driftTerm = (0.08 - 0.5 × 0.25²) × (1 / 31,536,000)
         = (0.08 - 0.03125) × 3.17 × 10⁻⁸
         = 0.04875 × 3.17 × 10⁻⁸
         = 1.545 × 10⁻⁹
```

**volatilityTerm:**
```java
this.volatilityTerm = volatility * Math.sqrt(DT);
```

This is `σ × √dt` from GBM formula.

**Example (AAPL: volatility=0.25):**
```
volatilityTerm = 0.25 × √(1 / 31,536,000)
              = 0.25 × √(3.17 × 10⁻⁸)
              = 0.25 × 0.000178
              = 0.0000445
```

**Why pre-calculate?**
- These values never change
- Avoid recalculating every tick (10 times/second × many tokens)
- Tiny optimization, but good practice

### Constructor

```java
public PriceSimulator(double initialPrice, double drift, double volatility) {
    if (initialPrice <= 0) {
        throw new IllegalArgumentException("Initial price must be positive, got: " + initialPrice);
    }
    if (volatility < 0) {
        throw new IllegalArgumentException("Volatility cannot be negative, got: " + volatility);
    }
    
    this.currentPrice = initialPrice;
    this.drift = drift;
    this.volatility = volatility;
    
    this.driftTerm = (drift - 0.5 * volatility * volatility) * DT;
    this.volatilityTerm = volatility * Math.sqrt(DT);
    
    log.debug("Created PriceSimulator: initialPrice={}, drift={}, volatility={}", 
              initialPrice, drift, volatility);
}
```

**Validation:**
- **initialPrice > 0:** Can't have negative or zero price
- **volatility >= 0:** Can't have negative volatility (though 0 is valid for constant price)

**Why throw IllegalArgumentException?**
- Constructor failure is programmer error
- Better to fail fast than silently accept bad values

**Logging:**
```
DEBUG c.q.g.s.PriceSimulator : Created PriceSimulator: initialPrice=180.0, drift=0.08, volatility=0.25
```

Helps debug if prices look wrong.

### generateNextPrice() Method

```java
public double generateNextPrice() {
    double dW = random.nextGaussian();
    double changeMultiplier = Math.exp(driftTerm + volatilityTerm * dW);
    currentPrice = currentPrice * changeMultiplier;
    return currentPrice;
}
```

**Step-by-step execution:**

**Example: AAPL starting at $180.00**

**Step 1: Generate random Gaussian**
```java
double dW = random.nextGaussian();
// dW = 0.5 (example value, actually random)
```

**What is nextGaussian()?**
- Returns number from normal distribution
- Mean = 0, Standard deviation = 1
- ~68% of values between -1 and +1
- ~95% of values between -2 and +2
- ~99.7% of values between -3 and +3

**Distribution:**
```
        *
      * * *
    * * * * *
  * * * * * * *
* * * * * * * * *
-3 -2 -1 0 +1 +2 +3
```

**Step 2: Calculate change multiplier**
```java
double changeMultiplier = Math.exp(driftTerm + volatilityTerm * dW);
```

**Substituting values:**
```
driftTerm = 1.545 × 10⁻⁹      (pre-calculated)
volatilityTerm = 0.0000445     (pre-calculated)
dW = 0.5                       (random)

changeMultiplier = exp(1.545 × 10⁻⁹ + 0.0000445 × 0.5)
                = exp(1.545 × 10⁻⁹ + 0.00002225)
                = exp(0.00002225)
                = 1.000022250
```

**Why exp()?**
- Converts additive change → multiplicative change
- Ensures result is always positive (exp(x) > 0 for all x)
- Models compound growth

**Step 3: Update current price**
```java
currentPrice = currentPrice * changeMultiplier;
// currentPrice = 180.00 × 1.000022250
// currentPrice = 180.00400 ≈ $180.00
```

**Price increased by ~$0.004 (tiny because dt is tiny)**

**Over time:**
```
Second 0:    $180.00
Second 1:    $180.00  (dW = 0.5)
Second 2:    $180.05  (dW = 1.2)
Second 3:    $180.08  (dW = 0.8)
Second 4:    $180.03  (dW = -1.0)
...
After 86400 seconds (1 day): $180.30 (small drift up)
After 31536000 seconds (1 year): $194.40 (8% growth)
```

### generateVolume() Method

```java
public double generateVolume(double baseVolume) {
    double multiplier = 0.5 + random.nextDouble();
    return baseVolume * multiplier;
}
```

**What it does:** Adds randomness to volume

**Example:**
```java
baseVolume = 1000.0

random.nextDouble() returns: 0.0 to 1.0 (uniform distribution)
multiplier = 0.5 + 0.7 = 1.2
volume = 1000.0 × 1.2 = 1200.0

Next call:
multiplier = 0.5 + 0.3 = 0.8
volume = 1000.0 × 0.8 = 800.0
```

**Range:** 0.5× to 1.5× base volume (50% to 150%)

**Why random volume?**
- Real markets have varying volume
- Adds realism to data

**Why not use another Gaussian?**
- Volume doesn't follow normal distribution in reality
- Uniform is simpler and good enough

---

## Testing the Simulator

### Step 1: Compile

```bash
mvn compile
```

**Expected:**
```
[INFO] BUILD SUCCESS
```

### Step 2: Unit Test (Temporary)

**Add to `GeneratorApplication.java` main method:**

```java
public static void main(String[] args) {
    // Test PriceSimulator
    System.out.println("=== Testing PriceSimulator ===");
    
    PriceSimulator aapl = new PriceSimulator(180.00, 0.08, 0.25);
    
    System.out.println("AAPL starting price: $" + aapl.getCurrentPrice());
    
    for (int i = 1; i <= 10; i++) {
        double newPrice = aapl.generateNextPrice();
        double volume = aapl.generateVolume(1000.0);
        System.out.printf("Tick %d: $%.2f, volume=%.0f%n", i, newPrice, volume);
    }
    
    System.out.println("\n=== Starting Spring Boot ===");
    SpringApplication.run(GeneratorApplication.class, args);
}
```

**Run:**
```bash
mvn spring-boot:run
```

**Expected output:**
```
=== Testing PriceSimulator ===
AAPL starting price: $180.0
Tick 1: $180.05, volume=1200
Tick 2: $180.12, volume=850
Tick 3: $180.08, volume=1350
Tick 4: $180.15, volume=950
Tick 5: $180.11, volume=1100
Tick 6: $180.18, volume=1400
Tick 7: $180.14, volume=700
Tick 8: $180.20, volume=1250
Tick 9: $180.16, volume=900
Tick 10: $180.23, volume=1150

=== Starting Spring Boot ===
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
...
```

**Verify:**
- ✅ Prices move up and down (not just one direction)
- ✅ Prices never negative
- ✅ Changes are small (not jumping $10)
- ✅ Volume varies around 1000

**Remove test code after verification.**

### Step 3: Long-Term Drift Test

**Test that drift works over many ticks:**

```java
PriceSimulator aapl = new PriceSimulator(180.00, 0.08, 0.25);

// Simulate 1 year (365 days × 86400 seconds)
int secondsPerYear = 365 * 86400;
for (int i = 0; i < secondsPerYear; i++) {
    aapl.generateNextPrice();
}

double finalPrice = aapl.getCurrentPrice();
double actualReturn = (finalPrice - 180.00) / 180.00;

System.out.printf("After 1 year: $%.2f%n", finalPrice);
System.out.printf("Return: %.2f%% (expected ~8%%)%n", actualReturn * 100);
```

**Expected output:**
```
After 1 year: $194.20
Return: 7.89% (expected ~8%)
```

**Note:** Won't be exactly 8% due to randomness (could be 5-12%)

---

## Common Issues

### Issue 1: Prices Going Negative

**Symptom:** Price becomes negative or NaN

**Cause:** Using wrong formula (additive instead of multiplicative)

**Wrong:**
```java
currentPrice = currentPrice + driftTerm + volatilityTerm * dW;  // ❌
```

**Correct:**
```java
currentPrice = currentPrice * Math.exp(driftTerm + volatilityTerm * dW);  // ✅
```

### Issue 2: Prices Not Changing

**Symptom:** All prices stay exactly at initial value

**Cause:** Missing `random.nextGaussian()` call

**Wrong:**
```java
double dW = 0;  // ❌ No randomness
```

**Correct:**
```java
double dW = random.nextGaussian();  // ✅
```

### Issue 3: Prices Jumping Too Much

**Symptom:** Price jumps $50 in one second

**Cause:** Forgot to use `DT` (time step)

**Wrong:**
```java
double driftTerm = drift - 0.5 * volatility * volatility;  // ❌ Missing × DT
```

**Correct:**
```java
double driftTerm = (drift - 0.5 * volatility * volatility) * DT;  // ✅
```

### Issue 4: Drift Not Working

**Symptom:** After 1 year, price didn't grow 8%

**Possible causes:**
1. **Volatility too high:** High volatility can mask drift
2. **Bad luck:** Random walk can go down even with positive drift
3. **Wrong DT calculation:** Check `SECONDS_PER_YEAR` is correct

**Solution:** Run multiple simulations, average should be ~8%

---

## Tuning Parameters

### Stock Profiles

**Large Cap (AAPL, MSFT, GOOGL):**
```java
new PriceSimulator(180.00, 0.08, 0.25);
```
- Moderate drift (8%)
- Moderate volatility (25%)

**Growth Stock (TSLA, AMZN):**
```java
new PriceSimulator(250.00, 0.15, 0.40);
```
- Higher drift (15%)
- Higher volatility (40%)

### Crypto Profiles

**Major Crypto (BTC, ETH):**
```java
new PriceSimulator(50000.00, 0.20, 0.50);
```
- High drift (20%)
- High volatility (50%)

**Altcoin (SOL, AVAX, MATIC):**
```java
new PriceSimulator(150.00, 0.30, 0.80);
```
- Very high drift (30%)
- Very high volatility (80%)

---

## Summary

**PriceSimulator class:**
- Implements GBM formula for realistic price generation
- **generateNextPrice():** Returns next price (one second later)
- **generateVolume():** Returns random volume around base value

**Key components:**
- **dW:** Random Gaussian number (randomness)
- **driftTerm:** Pre-calculated `(μ - 0.5σ²) × dt` (trend)
- **volatilityTerm:** Pre-calculated `σ × √dt` (scaling)
- **Math.exp():** Ensures positive prices, models compound growth

**Formula:**
```java
changeMultiplier = Math.exp(driftTerm + volatilityTerm * dW);
currentPrice = currentPrice * changeMultiplier;
```

**Parameters:**
- **drift (μ):** 0.08 = 8% annual growth
- **volatility (σ):** 0.25 = 25% annual volatility
- **DT:** 1 second / 31,536,000 seconds = 3.17 × 10⁻⁸

**Next:** Implement MarketDataGenerator service that uses this simulator (`guides/generator-service-implementation.md`)
