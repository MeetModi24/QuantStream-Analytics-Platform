# Geometric Brownian Motion (GBM) Explained

## Why We Need Realistic Price Generation

### The Problem with Random Walks

**Simple approach:**

```java
double newPrice = currentPrice + random(-1, 1);
```

**What happens:**
```
Time 0: $100.00
Time 1: $100.50  (+$0.50)
Time 2: $100.20  (-$0.30)
Time 3: $101.10  (+$0.90)
Time 4: $100.80  (-$0.30)
```

**Looks okay, but problems:**

1. **Can go negative:**
   ```
   $100 → $99 → $98 → ... → $1 → $0 → -$1 ❌
   Stock prices never go negative in reality
   ```

2. **Fixed dollar moves:**
   ```
   $10 stock moves ±$1 = 10% move (volatile)
   $1000 stock moves ±$1 = 0.1% move (barely moving)
   ```
   Real markets: volatility is percentage-based, not dollar-based

3. **No trend:**
   ```
   Moves are random: +1, -1, +1, -1
   Price oscillates around starting point
   Real markets have trends (bull/bear markets)
   ```

---

## What is Geometric Brownian Motion?

**GBM is the mathematical model** used in finance to simulate stock prices.

**Formula:**
```
dS = μ × S × dt + σ × S × dW

Where:
S  = current stock price
dS = change in stock price
μ  = drift (expected return, trend)
σ  = volatility (randomness)
dt = time step (e.g., 1 second / 86400 seconds in a day)
dW = random Gaussian number (normal distribution with mean=0, std=1)
```

**In plain English:**
> Price changes are proportional to current price, with a trend (drift) and random noise (volatility).

---

## Understanding Each Component

### 1. S = Current Price

The price at this moment.

```
If BTC is $50,000 right now:
S = 50000
```

### 2. dt = Time Step

How much time passes between updates.

```
We update every 1 second.
There are 86,400 seconds in a day.
dt = 1 / 86400 = 0.0000115741 days
```

**Why express as fraction of a day?**
Because drift (μ) and volatility (σ) are typically quoted as "per year", and we need consistent units.

### 3. μ = Drift (Trend)

The **expected return** or **trend direction**.

```
μ = 0.05 means 5% annual growth
μ = 0.10 means 10% annual growth
μ = -0.05 means 5% annual decline
```

**Example:**
```
Stock at $100 with μ = 0.10 (10% annual drift)
After 1 year, expected price: $110
After 2 years, expected price: $121 (compound growth)
```

**In our project:**
- Stocks: μ = 0.08 (8% annual growth, S&P 500 historical average)
- Crypto: μ = 0.15 (15% annual growth, more bullish)

### 4. σ = Volatility (Randomness)

The **standard deviation** of returns, measuring how much the price jumps around.

```
σ = 0.20 means 20% annual volatility (moderate)
σ = 0.50 means 50% annual volatility (very volatile)
σ = 0.10 means 10% annual volatility (stable)
```

**Example:**
```
Stock at $100 with σ = 0.20 (20% volatility)
68% of daily returns will be within: -1.25% to +1.25%
95% of daily returns will be within: -2.5% to +2.5%
```

**In our project:**
- Large cap stocks (AAPL, MSFT): σ = 0.25 (25% volatility)
- Small cap stocks (TSLA): σ = 0.40 (40% volatility)
- Major crypto (BTC, ETH): σ = 0.50 (50% volatility)
- Altcoins: σ = 0.80 (80% volatility)

**Why volatility matters:**
- Low volatility = smooth, predictable price movements
- High volatility = wild swings, exciting charts

### 5. dW = Random Component

A **random number from a Gaussian (normal) distribution**.

```java
Random random = new Random();
double dW = random.nextGaussian();
// Returns: ~68% of the time between -1 and +1
//          ~95% of the time between -2 and +2
//          ~99.7% of the time between -3 and +3
```

**Why Gaussian?**
- Real market returns follow a roughly normal distribution
- Most days are "normal" (small moves)
- Rare days have large moves (tail events)

**Distribution visualization:**
```
            *
          * * *
        * * * * *
      * * * * * * *
    * * * * * * * * *
  * * * * * * * * * * *
-3  -2  -1   0  +1  +2  +3

Most random numbers are near 0 (small moves)
Few random numbers are near ±3 (large moves)
```

---

## Putting It Together: The Full Formula

### Discrete Form (What We Implement)

```
newPrice = currentPrice × exp((μ - 0.5 × σ²) × dt + σ × √dt × dW)
```

**Why `exp()`?**
- Ensures price never goes negative (e^x is always positive)
- Models multiplicative growth (compound returns)

**Why `μ - 0.5 × σ²`?**
- This is the **drift adjustment** for continuous compounding
- Without it, higher volatility would artificially inflate prices
- (Mathematical detail: Ito's lemma correction term)

**Why `√dt`?**
- Scales the random component correctly over time
- Makes volatility consistent regardless of time step

### Step-by-Step Example

**Setup:**
```
Current price (S): $100.00
Drift (μ): 0.10 (10% annual)
Volatility (σ): 0.20 (20% annual)
Time step (dt): 1 / 86400 (1 second)
Random number (dW): 0.5 (from Gaussian)
```

**Calculate:**

1. **Drift component:**
   ```
   drift = (μ - 0.5 × σ²) × dt
        = (0.10 - 0.5 × 0.20²) × (1/86400)
        = (0.10 - 0.02) × 0.0000115741
        = 0.08 × 0.0000115741
        = 0.00000092593
   ```

2. **Volatility component:**
   ```
   vol = σ × √dt × dW
      = 0.20 × √(1/86400) × 0.5
      = 0.20 × 0.003401 × 0.5
      = 0.00034006
   ```

3. **Total change:**
   ```
   total = drift + vol
        = 0.00000092593 + 0.00034006
        = 0.00034099
   ```

4. **New price:**
   ```
   newPrice = currentPrice × exp(total)
           = 100 × exp(0.00034099)
           = 100 × 1.00034105
           = $100.034
   ```

**Price went from $100.00 to $100.034** (small move over 1 second)

---

## Why GBM Is Realistic

### 1. Prices Never Go Negative

```
exp(anything) > 0

Even if dW = -1000:
newPrice = currentPrice × exp(-1000)
        = currentPrice × 0.00000...001
        ≈ $0 (approaches zero, never negative)
```

### 2. Percentage-Based Moves

```
$10 stock with 20% volatility:
  Moves ±$2 typical (20% of $10)

$1000 stock with 20% volatility:
  Moves ±$200 typical (20% of $1000)
```

Both have same volatility, but dollar moves scale with price.

### 3. Compound Growth

```
Year 1: $100 → $110 (10% growth)
Year 2: $110 → $121 (10% growth on $110, not $100)
Year 3: $121 → $133.10 (compound continues)
```

### 4. Realistic Charts

**Random walk:**
```
Price over time (random walk):
105 |     *
100 |*  *   *
 95 |  *      *
    └─────────────
    Jagged, unrealistic
```

**GBM:**
```
Price over time (GBM):
120 |          *****
110 |      ****
100 |  ****
 90 |**
    └─────────────
    Smooth trend with noise, realistic
```

---

## Implementation in Java

```java
public class PriceSimulator {
    private final Random random = new Random();
    private double currentPrice;
    private final double drift;        // μ (mu)
    private final double volatility;   // σ (sigma)
    
    public PriceSimulator(double initialPrice, double drift, double volatility) {
        this.currentPrice = initialPrice;
        this.drift = drift;
        this.volatility = volatility;
    }
    
    public double generateNextPrice() {
        // Time step (1 second as fraction of a year)
        double dt = 1.0 / (86400.0 * 365.0);  // seconds in a year
        
        // Random Gaussian number
        double dW = random.nextGaussian();
        
        // GBM formula
        double driftTerm = (drift - 0.5 * volatility * volatility) * dt;
        double diffusionTerm = volatility * Math.sqrt(dt) * dW;
        
        // Calculate new price
        double changeMultiplier = Math.exp(driftTerm + diffusionTerm);
        currentPrice = currentPrice * changeMultiplier;
        
        return currentPrice;
    }
}
```

**Usage:**

```java
// Create simulator for AAPL
PriceSimulator aapl = new PriceSimulator(
    180.00,   // initial price
    0.08,     // 8% annual drift
    0.25      // 25% annual volatility
);

// Generate prices every second
while (true) {
    double newPrice = aapl.generateNextPrice();
    System.out.println("AAPL: $" + newPrice);
    Thread.sleep(1000);  // Wait 1 second
}
```

**Output:**
```
AAPL: $180.05
AAPL: $180.12
AAPL: $180.08
AAPL: $180.15
AAPL: $180.11
...
```

---

## Tuning Parameters for Different Assets

### Large Cap Stocks (AAPL, MSFT, GOOGL)

```java
drift = 0.08        // 8% annual growth (S&P 500 average)
volatility = 0.25   // 25% annual volatility (moderate)
```

**Characteristics:**
- Steady upward trend
- Moderate daily swings
- Looks like real blue-chip stock

### High-Growth Stocks (TSLA)

```java
drift = 0.15        // 15% annual growth (aggressive)
volatility = 0.40   // 40% annual volatility (high)
```

**Characteristics:**
- Strong upward trend
- Large daily swings
- Exciting, meme-stock behavior

### Major Crypto (BTC, ETH)

```java
drift = 0.20        // 20% annual growth (bullish)
volatility = 0.50   // 50% annual volatility (very high)
```

**Characteristics:**
- Strong trend
- Wild swings
- Crypto-like behavior

### Altcoins (SOL, AVAX, MATIC)

```java
drift = 0.30        // 30% annual growth (very bullish)
volatility = 0.80   // 80% annual volatility (extreme)
```

**Characteristics:**
- Very strong trend
- Extreme volatility
- Typical altcoin behavior

### Stable Assets (USD, Bonds)

```java
drift = 0.02        // 2% annual growth (inflation)
volatility = 0.05   // 5% annual volatility (very low)
```

**Characteristics:**
- Slow, steady growth
- Very small movements
- Boring but stable

---

## Common Misconceptions

### Misconception 1: "GBM predicts future prices"

**Wrong.** GBM is a **simulation model**, not a prediction.

It generates realistic-looking random paths. Each run produces different results.

**Think of it like:**
- Weather simulation: Models realistic weather, doesn't predict tomorrow's rain
- Dice rolling: Models probability, doesn't predict next roll

### Misconception 2: "Higher drift = always goes up"

**Not quite.** Drift is **expected** return, but volatility adds noise.

```
drift = 0.10, volatility = 0.50

Some paths: go up 20%
Some paths: go down 10%
Average: up 10%
```

Over long periods, drift dominates. Over short periods, volatility dominates.

### Misconception 3: "Real markets follow GBM exactly"

**No.** Real markets have:
- Fat tails (more extreme events than GBM predicts)
- Volatility clustering (high volatility persists)
- Jumps (sudden large moves, not smooth GBM)
- Mean reversion (prices tend to return to average)

**GBM is good enough for:**
- Portfolio simulation
- Options pricing (Black-Scholes)
- Teaching/learning
- Our project (we need realistic-looking data, not perfect prediction)

---

## Testing Your Implementation

### Test 1: Price Never Goes Negative

```java
// Run for 1 million steps
for (int i = 0; i < 1_000_000; i++) {
    double price = simulator.generateNextPrice();
    assert price > 0 : "Price went negative!";
}
```

Should never fail.

### Test 2: Average Return Matches Drift

```java
double initialPrice = 100.0;
PriceSimulator sim = new PriceSimulator(initialPrice, 0.10, 0.20);

// Run for 1 year (365 days × 86400 seconds)
for (int i = 0; i < 365 * 86400; i++) {
    sim.generateNextPrice();
}

double finalPrice = sim.getCurrentPrice();
double actualReturn = (finalPrice - initialPrice) / initialPrice;

// Should be around 0.10 (10% annual drift)
// Might be 0.08 or 0.12 due to randomness, but close
```

### Test 3: Volatility Check

```java
// Calculate daily returns for 100 days
List<Double> returns = new ArrayList<>();
double previousPrice = simulator.getCurrentPrice();

for (int day = 0; day < 100; day++) {
    // Simulate 1 day (86400 seconds)
    for (int sec = 0; sec < 86400; sec++) {
        simulator.generateNextPrice();
    }
    double currentPrice = simulator.getCurrentPrice();
    double dailyReturn = (currentPrice - previousPrice) / previousPrice;
    returns.add(dailyReturn);
    previousPrice = currentPrice;
}

// Calculate standard deviation of returns
double mean = returns.stream().mapToDouble(d -> d).average().orElse(0);
double variance = returns.stream()
    .mapToDouble(r -> Math.pow(r - mean, 2))
    .average()
    .orElse(0);
double stdDev = Math.sqrt(variance);

// Annualize (252 trading days per year)
double annualizedVol = stdDev * Math.sqrt(252);

// Should be around 0.20 (20% volatility parameter)
System.out.println("Annualized volatility: " + annualizedVol);
```

---

## Summary

**GBM Formula:**
```
newPrice = currentPrice × exp((μ - 0.5σ²)dt + σ√dt × dW)
```

**Key Points:**
1. **Prices never go negative** (exp always positive)
2. **Percentage-based moves** (scales with price)
3. **Has trend** (drift parameter μ)
4. **Has randomness** (volatility parameter σ)
5. **Looks realistic** (smooth with noise)

**For Our Project:**
- Use GBM to generate prices for 10 tokens
- Different drift/volatility for stocks vs crypto
- Update every second
- Results in realistic-looking charts

**Next:** Implement this in `PriceSimulator.java` during Phase 1, Task 4.
