# Technical Indicators Explained

## Overview

Technical indicators are mathematical calculations based on historical price, volume, or open interest data. They help identify patterns, trends, and potential reversal points.

**Think of indicators as:**
- Price data = Raw sensor readings
- Indicators = Processed signals that reveal hidden patterns

---

## 1. Moving Average (MA)

### Concept

Average price over a specific time period. Smooths out noise, reveals trends.

### Calculation

```java
public double calculateMA(List<Double> prices, int period) {
    return prices.stream()
        .limit(period)
        .mapToDouble(Double::doubleValue)
        .average()
        .orElse(0.0);
}
```

### Example

```
AAPL prices (last 10 days): [180, 181, 179, 182, 180, 183, 181, 184, 182, 185]

MA(10) = (180 + 181 + 179 + 182 + 180 + 183 + 181 + 184 + 182 + 185) / 10
       = 1817 / 10
       = 181.7
```

### Interpretation

- **Price > MA** → Uptrend (bullish)
- **Price < MA** → Downtrend (bearish)
- **MA sloping up** → Trend strengthening
- **MA sloping down** → Trend weakening

### Usage in Strategy

**Moving Average Crossover:**
- **MA(10) crosses above MA(50)** → Golden Cross → BUY
- **MA(10) crosses below MA(50)** → Death Cross → SELL

---

## 2. RSI (Relative Strength Index)

### Concept

Momentum oscillator measuring speed and magnitude of price changes. Identifies overbought/oversold conditions.

**Range:** 0 to 100

### Calculation

```java
public double calculateRSI(List<Double> prices, int period) {
    List<Double> gains = new ArrayList<>();
    List<Double> losses = new ArrayList<>();
    
    // Calculate price changes
    for (int i = 0; i < period; i++) {
        double change = prices.get(i) - prices.get(i + 1);
        if (change > 0) {
            gains.add(change);
            losses.add(0.0);
        } else {
            gains.add(0.0);
            losses.add(Math.abs(change));
        }
    }
    
    // Average gain and loss
    double avgGain = gains.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    double avgLoss = losses.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    
    // Relative Strength
    if (avgLoss == 0) return 100.0;
    double rs = avgGain / avgLoss;
    
    // RSI
    return 100.0 - (100.0 / (1.0 + rs));
}
```

### Example

```
AAPL prices (last 14 days): [180, 182, 181, 183, 185, 184, 186, 188, 187, 189, 190, 189, 191, 192]

Price changes: [+2, -1, +2, +2, -1, +2, +2, -1, +2, +1, -1, +2, +1]
Gains: [2, 0, 2, 2, 0, 2, 2, 0, 2, 1, 0, 2, 1]
Losses: [0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0]

Average gain = 16 / 14 = 1.14
Average loss = 4 / 14 = 0.29

RS = 1.14 / 0.29 = 3.93
RSI = 100 - (100 / (1 + 3.93)) = 100 - 20.3 = 79.7
```

### Interpretation

- **RSI > 70** → Overbought → Potential SELL signal
- **RSI < 30** → Oversold → Potential BUY signal
- **RSI = 50** → Neutral (equal gains and losses)

### Usage in Strategy

```java
if (rsi < 30) {
    return new Signal(symbol, "BUY", "RSI", 0.80); // Oversold
}
if (rsi > 70) {
    return new Signal(symbol, "SELL", "RSI", 0.80); // Overbought
}
```

---

## 3. Bollinger Bands

### Concept

Volatility bands around a moving average. Shows when price is "stretched" too far from average.

**Components:**
- **Middle Band** = 20-day MA
- **Upper Band** = Middle Band + (2 × Standard Deviation)
- **Lower Band** = Middle Band - (2 × Standard Deviation)

### Calculation

```java
public BollingerBands calculateBollingerBands(List<Double> prices, int period, double stdDevMultiplier) {
    // Middle band (MA)
    double middleBand = prices.stream()
        .limit(period)
        .mapToDouble(Double::doubleValue)
        .average()
        .orElse(0.0);
    
    // Standard deviation
    double variance = prices.stream()
        .limit(period)
        .mapToDouble(price -> Math.pow(price - middleBand, 2))
        .average()
        .orElse(0.0);
    double stdDev = Math.sqrt(variance);
    
    // Upper and lower bands
    double upperBand = middleBand + (stdDevMultiplier * stdDev);
    double lowerBand = middleBand - (stdDevMultiplier * stdDev);
    
    return new BollingerBands(upperBand, middleBand, lowerBand);
}
```

### Example

```
AAPL prices (last 20 days): [180, 182, 181, 183, 179, 184, 180, 185, 182, 186, 
                              181, 187, 183, 188, 184, 189, 185, 190, 186, 191]

Middle Band (MA) = 184.0
Standard Deviation = 3.5

Upper Band = 184.0 + (2 × 3.5) = 191.0
Lower Band = 184.0 - (2 × 3.5) = 177.0

Current price = 176 (below lower band!)
```

### Interpretation

- **Price touches upper band** → Overbought → Consider SELL
- **Price touches lower band** → Oversold → Consider BUY
- **Bands narrow** → Low volatility → Breakout coming
- **Bands widen** → High volatility → Large moves happening

### Usage in Strategy

```java
double currentPrice = prices.get(0);
BollingerBands bb = calculateBollingerBands(prices, 20, 2.0);

if (currentPrice <= bb.lowerBand) {
    return new Signal(symbol, "BUY", "BOLLINGER_BANDS", 0.75); // Oversold
}
if (currentPrice >= bb.upperBand) {
    return new Signal(symbol, "SELL", "BOLLINGER_BANDS", 0.75); // Overbought
}
```

---

## 4. MACD (Moving Average Convergence Divergence)

### Concept

Trend-following momentum indicator showing relationship between two moving averages.

**Components:**
- **MACD Line** = EMA(12) - EMA(26)
- **Signal Line** = EMA(9) of MACD Line
- **Histogram** = MACD Line - Signal Line

### Calculation

```java
public MACD calculateMACD(List<Double> prices) {
    // Calculate EMAs
    double ema12 = calculateEMA(prices, 12);
    double ema26 = calculateEMA(prices, 26);
    
    // MACD line
    double macdLine = ema12 - ema26;
    
    // Signal line (9-day EMA of MACD)
    List<Double> macdHistory = getMACD History(); // Previous MACD values
    double signalLine = calculateEMA(macdHistory, 9);
    
    // Histogram
    double histogram = macdLine - signalLine;
    
    return new MACD(macdLine, signalLine, histogram);
}

private double calculateEMA(List<Double> prices, int period) {
    double multiplier = 2.0 / (period + 1);
    double ema = prices.get(prices.size() - 1); // Start with oldest price
    
    for (int i = prices.size() - 2; i >= 0; i--) {
        ema = (prices.get(i) * multiplier) + (ema * (1 - multiplier));
    }
    
    return ema;
}
```

### Interpretation

- **MACD crosses above signal line** → Bullish → BUY
- **MACD crosses below signal line** → Bearish → SELL
- **Histogram positive and growing** → Uptrend strengthening
- **Histogram negative and shrinking** → Downtrend weakening

### Usage in Strategy

```java
MACD macd = calculateMACD(prices);
MACD previousMACD = getPreviousMACD();

if (macd.line > macd.signal && previousMACD.line <= previousMACD.signal) {
    return new Signal(symbol, "BUY", "MACD", 0.80); // Bullish crossover
}
if (macd.line < macd.signal && previousMACD.line >= previousMACD.signal) {
    return new Signal(symbol, "SELL", "MACD", 0.80); // Bearish crossover
}
```

---

## 5. Stochastic Oscillator

### Concept

Momentum indicator comparing closing price to price range over time.

**Formula:** Where is current price relative to recent high-low range?

**Components:**
- **%K** = Current momentum
- **%D** = 3-day MA of %K (signal line)

### Calculation

```java
public Stochastic calculateStochastic(List<Double> prices, int period) {
    double currentClose = prices.get(0);
    
    // Find highest high and lowest low in period
    double highestHigh = prices.stream().limit(period).max(Double::compare).orElse(0.0);
    double lowestLow = prices.stream().limit(period).min(Double::compare).orElse(0.0);
    
    // %K calculation
    double percentK = ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100;
    
    // %D (3-day MA of %K)
    List<Double> kHistory = getKHistory(); // Previous %K values
    double percentD = kHistory.stream().limit(3).mapToDouble(Double::doubleValue).average().orElse(0.0);
    
    return new Stochastic(percentK, percentD);
}
```

### Example

```
AAPL prices (last 14 days): [180, 182, 181, 183, 179, 184, 180, 185, 182, 186, 
                              181, 187, 183, 188]

Current close = 188
Highest high (14 days) = 188
Lowest low (14 days) = 179

%K = ((188 - 179) / (188 - 179)) × 100 = (9 / 9) × 100 = 100

%K history (last 3): [95, 97, 100]
%D = (95 + 97 + 100) / 3 = 97.3
```

### Interpretation

- **%K > 80** → Overbought
- **%K < 20** → Oversold
- **%K crosses above %D** → Bullish → BUY
- **%K crosses below %D** → Bearish → SELL

### Usage in Strategy

```java
Stochastic stoch = calculateStochastic(prices, 14);

if (stoch.percentK < 20) {
    return new Signal(symbol, "BUY", "STOCHASTIC", 0.75); // Oversold
}
if (stoch.percentK > 80) {
    return new Signal(symbol, "SELL", "STOCHASTIC", 0.75); // Overbought
}
```

---

## 6. ADX (Average Directional Index)

### Concept

Measures **strength** of trend (not direction). Tells you if market is trending or ranging.

**Range:** 0 to 100

### Interpretation

- **ADX < 20** → Weak or no trend (ranging market)
- **ADX 20-40** → Moderate trend
- **ADX > 40** → Strong trend
- **ADX > 60** → Very strong trend

### Usage in Strategy

```java
double adx = calculateADX(prices, 14);
double ma20 = calculateMA(prices, 20);
double currentPrice = prices.get(0);

// Only trade when strong trend exists
if (adx > 25) {
    if (currentPrice > ma20) {
        return new Signal(symbol, "BUY", "ADX", 0.80); // Strong uptrend
    } else {
        return new Signal(symbol, "SELL", "ADX", 0.80); // Strong downtrend
    }
}
```

---

## 7. Williams %R

### Concept

Momentum indicator similar to Stochastic, but inverted scale.

**Range:** -100 to 0 (note the negative!)

### Calculation

```java
public double calculateWilliamsR(List<Double> prices, int period) {
    double currentClose = prices.get(0);
    double highestHigh = prices.stream().limit(period).max(Double::compare).orElse(0.0);
    double lowestLow = prices.stream().limit(period).min(Double::compare).orElse(0.0);
    
    return ((highestHigh - currentClose) / (highestHigh - lowestLow)) * -100;
}
```

### Interpretation

- **%R > -20** → Overbought → Consider SELL
- **%R < -80** → Oversold → Consider BUY

### Usage in Strategy

```java
double williamsR = calculateWilliamsR(prices, 14);

if (williamsR < -80) {
    return new Signal(symbol, "BUY", "WILLIAMS_R", 0.75); // Oversold
}
if (williamsR > -20) {
    return new Signal(symbol, "SELL", "WILLIAMS_R", 0.75); // Overbought
}
```

---

## 8. ROC (Rate of Change)

### Concept

Momentum indicator measuring percentage change in price over time.

### Calculation

```java
public double calculateROC(List<Double> prices, int period) {
    double currentPrice = prices.get(0);
    double priceNPeriodsAgo = prices.get(period);
    
    return ((currentPrice - priceNPeriodsAgo) / priceNPeriodsAgo) * 100;
}
```

### Example

```
Current price = 185
Price 10 days ago = 180

ROC(10) = ((185 - 180) / 180) × 100 = (5 / 180) × 100 = 2.78%
```

### Interpretation

- **ROC > 0** → Upward momentum
- **ROC < 0** → Downward momentum
- **ROC crossing above 0** → BUY signal
- **ROC crossing below 0** → SELL signal

### Usage in Strategy

```java
double roc = calculateROC(prices, 10);
double previousROC = getPreviousROC();

if (roc > 0 && previousROC <= 0) {
    return new Signal(symbol, "BUY", "ROC", 0.75); // Momentum turning positive
}
if (roc < 0 && previousROC >= 0) {
    return new Signal(symbol, "SELL", "ROC", 0.75); // Momentum turning negative
}
```

---

## 9. Donchian Channel

### Concept

Identifies breakouts by tracking highest high and lowest low over a period.

**Components:**
- **Upper Band** = Highest high in N periods
- **Lower Band** = Lowest low in N periods
- **Middle Band** = Average of upper and lower

### Calculation

```java
public DonchianChannel calculateDonchianChannel(List<Double> prices, int period) {
    double upper = prices.stream().limit(period).max(Double::compare).orElse(0.0);
    double lower = prices.stream().limit(period).min(Double::compare).orElse(0.0);
    double middle = (upper + lower) / 2;
    
    return new DonchianChannel(upper, middle, lower);
}
```

### Interpretation

- **Price breaks above upper band** → Bullish breakout → BUY
- **Price breaks below lower band** → Bearish breakout → SELL

### Usage in Strategy

```java
DonchianChannel channel = calculateDonchianChannel(prices, 20);
double currentPrice = prices.get(0);
double previousPrice = prices.get(1);

if (currentPrice > channel.upper && previousPrice <= channel.upper) {
    return new Signal(symbol, "BUY", "DONCHIAN", 0.85); // Breakout above
}
```

---

## 10. VWAP (Volume-Weighted Average Price)

### Concept

Average price weighted by volume. Shows "true" average price accounting for transaction size.

### Calculation

```java
public double calculateVWAP(List<Tick> ticks) {
    double sumPriceVolume = 0;
    double sumVolume = 0;
    
    for (Tick tick : ticks) {
        sumPriceVolume += tick.getPrice() * tick.getVolume();
        sumVolume += tick.getVolume();
    }
    
    return sumPriceVolume / sumVolume;
}
```

### Example

```
Trades:
- $180 × 1000 shares = $180,000
- $182 × 500 shares  = $91,000
- $181 × 1500 shares = $271,500

VWAP = ($180,000 + $91,000 + $271,500) / (1000 + 500 + 1500)
     = $542,500 / 3000
     = $180.83
```

### Interpretation

- **Price < VWAP** → Undervalued → BUY
- **Price > VWAP** → Overvalued → SELL

### Usage in Strategy

```java
double vwap = calculateVWAP(ticks);
double currentPrice = prices.get(0);

double deviation = (currentPrice - vwap) / vwap;

if (deviation < -0.02) { // 2% below VWAP
    return new Signal(symbol, "BUY", "VWAP", 0.70);
}
if (deviation > 0.02) { // 2% above VWAP
    return new Signal(symbol, "SELL", "VWAP", 0.70);
}
```

---

## Indicator Comparison

| Indicator | Type | Best For | Lookback Period | Complexity |
|-----------|------|----------|-----------------|------------|
| MA | Trend | Trending markets | 10-200 days | Low |
| RSI | Momentum | Overbought/oversold | 14 days | Medium |
| Bollinger Bands | Volatility | Breakouts, reversals | 20 days | Medium |
| MACD | Trend + Momentum | Trend changes | 26 days | Medium |
| Stochastic | Momentum | Reversals | 14 days | Medium |
| ADX | Trend Strength | Filter ranging markets | 14 days | High |
| Williams %R | Momentum | Short-term reversals | 14 days | Low |
| ROC | Momentum | Momentum shifts | 10 days | Low |
| Donchian | Breakout | Breakout trading | 20 days | Low |
| VWAP | Volume | Intraday trading | 1 day | Medium |

---

## Key Takeaways

1. **No single indicator is perfect** - Each has strengths and weaknesses
2. **Combine indicators** - Use multiple to confirm signals
3. **Context matters** - Same indicator works differently in different markets
4. **Lagging vs. leading** - Most indicators lag price (react to past moves)
5. **Parameterization** - Period lengths matter (14-day RSI ≠ 7-day RSI)
6. **Overfitting risk** - Don't optimize too much on historical data

---

## Next Steps

Learn how to implement these indicators in Java and integrate them into the strategy engine!
