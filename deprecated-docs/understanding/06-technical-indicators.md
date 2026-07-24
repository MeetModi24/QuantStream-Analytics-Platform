# Understanding Technical Indicators

## What are Technical Indicators?

**Mathematical calculations** based on price and volume data used to predict future price movements.

Traders use indicators to make **buy/sell decisions**.

---

## Why Indicators Matter

### Problem: How to Decide When to Trade?

**Random guessing:**
- 50% win rate
- No edge

**Technical analysis:**
- Use patterns and math to find edge
- Example: "Price is below average and momentum is positive → likely to rise"

**Our system will:**
1. Calculate indicators in real-time
2. Generate trading signals (BUY/SELL/HOLD)
3. Track which strategies perform best

---

## Common Indicators

### 1. Moving Average (MA)

**What it is:** Average price over last N periods

**Purpose:** Smooth out noise, show trend direction

**Formula:**
```
SMA = (Price[0] + Price[1] + ... + Price[N-1]) / N
```

**Example:** 20-period SMA (20 minutes of 1-minute candles)
```
Prices: [50000, 50010, 49990, 50020, 50030, ...]
20-SMA: (50000 + 50010 + ... + Price[19]) / 20 = 50015
```

**Types:**

#### Simple Moving Average (SMA)
All prices have equal weight

#### Exponential Moving Average (EMA)
Recent prices have more weight
```
EMA[today] = (Price[today] × multiplier) + (EMA[yesterday] × (1 - multiplier))

multiplier = 2 / (N + 1)
```

EMA reacts faster to price changes.

**Trading Strategy:**
```
IF price crosses ABOVE 20-MA:
    SIGNAL = BUY (uptrend starting)

IF price crosses BELOW 20-MA:
    SIGNAL = SELL (downtrend starting)
```

**Visual:**
```
Price:  ────────────────────╱───
                           ╱
MA:     ─────────────────────────
                         ╱
                        ╱ (Golden Cross = BUY)
```

---

### 2. Relative Strength Index (RSI)

**What it is:** Momentum indicator (measures speed of price changes)

**Purpose:** Identify overbought/oversold conditions

**Formula:**
```
RSI = 100 - (100 / (1 + RS))

RS = Average Gain / Average Loss over last N periods
```

**Range:** 0 to 100

**Interpretation:**
- **RSI > 70** → Overbought (price might fall)
- **RSI < 30** → Oversold (price might rise)
- **RSI = 50** → Neutral

**Example Calculation (14 periods):**

```
Day | Price | Change | Gain | Loss
----|-------|--------|------|-----
1   | 100   | -      | -    | -
2   | 102   | +2     | 2    | 0
3   | 101   | -1     | 0    | 1
4   | 103   | +2     | 2    | 0
5   | 105   | +2     | 2    | 0
... (14 days total)

Average Gain = 1.5
Average Loss = 0.5
RS = 1.5 / 0.5 = 3
RSI = 100 - (100 / (1 + 3)) = 75 (Overbought!)
```

**Trading Strategy:**
```
IF RSI < 30 AND price > 20-MA:
    SIGNAL = BUY (oversold but trend is up)

IF RSI > 70:
    SIGNAL = SELL (overbought)
```

---

### 3. MACD (Moving Average Convergence Divergence)

**What it is:** Relationship between two moving averages

**Purpose:** Identify trend changes and momentum

**Formula:**
```
MACD Line = 12-EMA - 26-EMA
Signal Line = 9-EMA of MACD Line
Histogram = MACD Line - Signal Line
```

**Components:**

1. **MACD Line** (fast)
   - Difference between short and long EMAs
   - Shows momentum

2. **Signal Line** (slow)
   - EMA of MACD line
   - Triggers trade signals

3. **Histogram**
   - Distance between MACD and Signal
   - Shows strength of trend

**Visual:**
```
MACD Line:    ───╱────╲───
Signal Line:  ────────╱──
                     ╱
                    ╱ (MACD crosses above Signal = BUY)
```

**Trading Strategy:**
```
IF MACD crosses ABOVE Signal:
    SIGNAL = BUY (bullish crossover)

IF MACD crosses BELOW Signal:
    SIGNAL = SELL (bearish crossover)
```

---

### 4. Bollinger Bands

**What it is:** Volatility bands around moving average

**Purpose:** Measure volatility and identify breakouts

**Formula:**
```
Middle Band = 20-SMA
Upper Band = 20-SMA + (2 × Standard Deviation)
Lower Band = 20-SMA - (2 × Standard Deviation)
```

**Visual:**
```
Upper Band:  ─────────────────  (Price touching = overbought)
Middle (MA): ───────────────── 
Lower Band:  ─────────────────  (Price touching = oversold)

Price: ──╱────────────╲───
        ╱              ╲
```

**Interpretation:**
- **Price at upper band** → Overbought
- **Price at lower band** → Oversold
- **Bands narrow** → Low volatility (breakout coming)
- **Bands widen** → High volatility (trending)

**Trading Strategy:**
```
IF price touches LOWER band AND RSI < 30:
    SIGNAL = BUY (oversold + at support)

IF price touches UPPER band AND RSI > 70:
    SIGNAL = SELL (overbought + at resistance)
```

---

### 5. Volume Indicators

**What it is:** Analysis of trading volume

**Purpose:** Confirm price movements

**Common patterns:**

#### Volume Spike
```
Price: ───╱────
Volume:  ─┃───  (High volume = strong move)
```

High volume with price increase = buyers in control

#### Divergence
```
Price:  ───────╱──  (New high)
Volume: ───╲─────  (Lower volume)
```

Price makes new high but volume decreases = weak move (reversal coming)

**Trading Strategy:**
```
IF price breaks resistance AND volume > 2× average:
    SIGNAL = BUY (breakout confirmed)

IF price makes new high AND volume < average:
    SIGNAL = SELL (weak rally, likely to reverse)
```

---

## Combining Indicators

### Strategy Example: "Trend + Momentum + Volume"

```java
public Signal generateSignal(MarketData data) {
    double price = data.getClose();
    double ma20 = calculateMA(data, 20);
    double rsi = calculateRSI(data, 14);
    double avgVolume = calculateAverageVolume(data, 20);
    double currentVolume = data.getVolume();
    
    // BUY conditions
    if (price > ma20                    // Price above trend
        && rsi < 40                     // Slight pullback (not oversold)
        && currentVolume > avgVolume    // Volume confirms
    ) {
        return Signal.BUY;
    }
    
    // SELL conditions
    if (price < ma20                    // Price below trend
        || rsi > 70                     // Overbought
    ) {
        return Signal.SELL;
    }
    
    return Signal.HOLD;
}
```

**Why combine?**
- **MA** confirms trend direction
- **RSI** confirms momentum
- **Volume** confirms conviction

No single indicator is perfect. Combining reduces false signals.

---

## Our Implementation (ta4j Library)

We'll use **ta4j** - Java library for technical analysis.

```java
// Build a bar series (price data)
BarSeries series = new BaseBarSeries("BTC");
series.addBar(ZonedDateTime.now(), 50000, 50100, 49900, 50050, 1000);

// Calculate SMA
SMAIndicator sma = new SMAIndicator(new ClosePriceIndicator(series), 20);
double sma20 = sma.getValue(series.getEndIndex()).doubleValue();

// Calculate RSI
RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series), 14);
double rsi14 = rsi.getValue(series.getEndIndex()).doubleValue();

// Calculate MACD
MACDIndicator macd = new MACDIndicator(new ClosePriceIndicator(series), 12, 26);
double macdValue = macd.getValue(series.getEndIndex()).doubleValue();
```

---

## Strategy Evaluation Metrics

### 1. Win Rate
```
Win Rate = (Winning Trades / Total Trades) × 100%

Example:
60 winning trades out of 100 total = 60% win rate
```

### 2. Profit & Loss (PnL)
```
PnL = (Exit Price - Entry Price) × Quantity

Example:
Buy 1 BTC at $50,000
Sell 1 BTC at $52,000
PnL = ($52,000 - $50,000) × 1 = +$2,000
```

### 3. Sharpe Ratio
```
Sharpe Ratio = (Average Return - Risk-Free Rate) / Standard Deviation of Returns

Example:
Average return = 10% per month
Risk-free rate = 0.5% (treasury bonds)
Std dev = 5%
Sharpe = (10% - 0.5%) / 5% = 1.9

Interpretation:
> 1.0 = Good risk-adjusted returns
> 2.0 = Very good
> 3.0 = Excellent
```

### 4. Max Drawdown
```
Max Drawdown = Largest peak-to-trough decline

Example:
Portfolio: $100,000 → $120,000 → $90,000 → $110,000
Max Drawdown = ($120,000 - $90,000) / $120,000 = 25%
```

Lower drawdown = less risky strategy

---

## Our Strategies

We'll implement 5-10 simple strategies:

### Strategy 1: MA Crossover
```
BUY when price crosses above 20-MA
SELL when price crosses below 20-MA
```

### Strategy 2: RSI Mean Reversion
```
BUY when RSI < 30 (oversold)
SELL when RSI > 70 (overbought)
```

### Strategy 3: MACD Momentum
```
BUY when MACD crosses above Signal
SELL when MACD crosses below Signal
```

### Strategy 4: Bollinger Bounce
```
BUY when price touches lower Bollinger Band
SELL when price touches upper Bollinger Band
```

### Strategy 5: Trend + Momentum
```
BUY when price > 50-MA AND RSI < 45
SELL when price < 50-MA OR RSI > 70
```

---

## Key Takeaways

1. **Technical indicators** = math on price/volume data
2. **Moving averages** show trend direction
3. **RSI** shows overbought/oversold conditions
4. **MACD** shows momentum changes
5. **Bollinger Bands** show volatility
6. **Volume** confirms price movements
7. **Combine indicators** to reduce false signals
8. **Backtest** to evaluate strategy performance

---

## Summary: What We've Learned

You now understand:
1. **Kafka** - Message broker for decoupling services
2. **Time-Series DB** - Optimized storage for time-stamped data
3. **OHLC Candles** - Aggregated price data
4. **Kafka Streams** - Stream processing with windowing
5. **WebSocket** - Real-time updates to browser
6. **Technical Indicators** - Math for trading decisions

Next: We'll see how these fit together in the project architecture.

---

## Next: Project Architecture

See: `../architecture/ARCHITECTURE.md`
