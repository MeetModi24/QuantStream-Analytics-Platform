# Order Book Fundamentals

## What is an Order Book?

An **order book** is the complete list of **all buy and sell orders waiting to be executed** for a particular token at any given moment.

Think of it as a **live auction** where buyers and sellers post their intentions.

---

## Simple Price vs Order Book: The Key Difference

### **What We Had Before (Simple Tick Data)**

```json
{
  "token": "AAPL",
  "price": 180.50,
  "volume": 100,
  "timestamp": "2026-07-24T10:30:00Z"
}
```

**What this tells us:**
- ✅ Latest trade price: $180.50
- ✅ Volume of that trade: 100 shares
- ❌ **We DON'T know:** What prices buyers and sellers are willing to trade at RIGHT NOW

**Problem for HFT:**
- Can't predict next price move
- Can't see market depth (liquidity)
- Can't detect order book imbalance
- Can't implement market making

---

### **What We Need (Order Book / Limit Order Book - LOB)**

```json
{
  "token": "AAPL",
  "timestamp": "2026-07-24T10:30:00.123Z",
  
  "bids": [
    {"price": 180.50, "volume": 5000},   // Level 1: Best bid
    {"price": 180.49, "volume": 3200},   // Level 2
    {"price": 180.48, "volume": 4100},   // Level 3
    {"price": 180.47, "volume": 2800},   // Level 4
    {"price": 180.46, "volume": 3500}    // Level 5
  ],
  
  "asks": [
    {"price": 180.51, "volume": 4200},   // Level 1: Best ask
    {"price": 180.52, "volume": 3800},   // Level 2
    {"price": 180.53, "volume": 2900},   // Level 3
    {"price": 180.54, "volume": 4500},   // Level 4
    {"price": 180.55, "volume": 3100}    // Level 5
  ]
}
```

**What this tells us:**
- ✅ **Best bid:** $180.50 (buyers willing to pay this)
- ✅ **Best ask:** $180.51 (sellers willing to accept this)
- ✅ **Spread:** $0.01 (180.51 - 180.50)
- ✅ **Depth at each level:** How many shares at each price
- ✅ **Order book imbalance:** 5000 buyers vs 4200 sellers at best levels
- ✅ **Predict next move:** More buy pressure → price likely to go up

---

## Visual Representation

### **Order Book Snapshot**

```
AAPL Order Book at 10:30:00.123

         SELL SIDE (ASKS)                     BUY SIDE (BIDS)
    Price    |  Volume  |              |  Volume  |  Price
    ---------|----------|              |----------|--------
             |          |              |          |
    180.55   |   3,100  | ◄────────────|          |        
    180.54   |   4,500  | ◄────────────|          |        
    180.53   |   2,900  | ◄────────────|          |        
    180.52   |   3,800  | ◄────────────|          |        
    180.51   |   4,200  | ◄──── BEST ASK          |        
    ---------|----------|              |----------|--------
                        |  SPREAD = $0.01          |
    ---------|----------|              |----------|--------
             |          |  BEST BID ───►| 5,000    | 180.50
             |          | ──────────────►| 3,200    | 180.49
             |          | ──────────────►| 4,100    | 180.48
             |          | ──────────────►| 2,800    | 180.47
             |          | ──────────────►| 3,500    | 180.46
```

**Key Terms:**

- **Best Bid (180.50):** Highest price buyers are willing to pay
- **Best Ask (180.51):** Lowest price sellers are willing to accept
- **Spread (0.01):** Difference between best bid and best ask
- **Mid-price (180.505):** Average of best bid and best ask
- **Level 1 (L1):** Best bid and best ask only
- **Level 2 (L2):** Top 5 or 10 levels on each side (what we use)
- **Market Depth:** Total volume available at each price level

---

## Why Order Books Matter for HFT

### **1. Order Book Imbalance (OBI)**

**Definition:** Ratio of buy pressure to sell pressure at top of book

```python
bid_volume = 5000  # At 180.50
ask_volume = 4200  # At 180.51

OBI = (bid_volume - ask_volume) / (bid_volume + ask_volume)
    = (5000 - 4200) / (5000 + 4200)
    = 800 / 9200
    = 0.087  (positive = bullish)
```

**Interpretation:**
- **OBI > 0:** More buyers than sellers → Price likely to move UP
- **OBI < 0:** More sellers than buyers → Price likely to move DOWN
- **OBI ≈ 0:** Balanced → No clear direction

**HFT Strategy:** Buy when OBI > 0.5, sell when OBI < -0.5

---

### **2. Microprice (Better than Mid-Price)**

**Simple mid-price:**
```python
mid = (best_bid + best_ask) / 2
    = (180.50 + 180.51) / 2
    = 180.505
```

**Microprice (volume-weighted):**
```python
microprice = (best_bid * ask_volume + best_ask * bid_volume) / (bid_volume + ask_volume)
           = (180.50 * 4200 + 180.51 * 5000) / (5000 + 4200)
           = (758100 + 902550) / 9200
           = 180.507
```

**Why microprice is better:**
- Takes volume into account
- More predictive of next trade price
- Used by HFT algorithms for execution

---

### **3. Market Depth (Liquidity)**

**Definition:** How much volume is available at different price levels

```
Cumulative Buy Volume (Bid Side):
Level 1: 5,000 shares
Level 2: 5,000 + 3,200 = 8,200 shares
Level 3: 8,200 + 4,100 = 12,300 shares
Level 4: 12,300 + 2,800 = 15,100 shares
Level 5: 15,100 + 3,500 = 18,600 shares

Cumulative Sell Volume (Ask Side):
Level 1: 4,200 shares
Level 2: 4,200 + 3,800 = 8,000 shares
Level 3: 8,000 + 2,900 = 10,900 shares
Level 4: 10,900 + 4,500 = 15,400 shares
Level 5: 15,400 + 3,100 = 18,500 shares
```

**Depth Imbalance:**
```python
bid_depth_L5 = 18,600
ask_depth_L5 = 18,500

depth_imbalance = bid_depth_L5 / ask_depth_L5 = 1.005
```

**Interpretation:**
- **Ratio > 1:** More buy liquidity → Support strong
- **Ratio < 1:** More sell liquidity → Resistance strong

---

## Order Book Dynamics (How It Changes)

Order books are **constantly changing** as:

### **1. New Orders Arrive**
```
Before:
Bids: [180.50: 5000, 180.49: 3200, ...]

New limit buy order: 200 shares at 180.50

After:
Bids: [180.50: 5200, 180.49: 3200, ...]
```

### **2. Orders Get Filled (Executed)**
```
Before:
Bids: [180.50: 5000, ...]
Asks: [180.51: 4200, ...]

Someone sells 1000 shares at market (hits the bid)

After:
Bids: [180.50: 4000, ...]  // 1000 shares filled
Last Trade: 180.50 @ 1000
```

### **3. Orders Get Cancelled**
```
Before:
Bids: [180.50: 5000, ...]

Trader cancels 2000 shares at 180.50

After:
Bids: [180.50: 3000, ...]
```

### **4. Orders Get Modified**
```
Before:
Bids: [180.50: 5000, ...]

Trader moves order: 5000 shares from 180.50 → 180.51

After:
Bids: [180.50: 0, ...]
Asks: [180.51: 4200 + 5000 = 9200, ...]
```

---

## Example: Order Book Over Time (1 Second)

### **t = 0ms (Initial State)**
```
Asks: [180.51: 4200, 180.52: 3800]
Bids: [180.50: 5000, 180.49: 3200]
OBI = +0.087 (bullish)
```

### **t = 250ms (Large buy order arrives)**
```
New: 3000 shares bid at 180.50

Asks: [180.51: 4200, 180.52: 3800]
Bids: [180.50: 8000, 180.49: 3200]  // Volume increased
OBI = +0.312 (very bullish!)
```

### **t = 500ms (Market buy hits the ask)**
```
Someone buys 4200 shares at market → fills 180.51

Asks: [180.52: 3800, 180.53: 2900]  // 180.51 consumed
Bids: [180.50: 8000, 180.49: 3200]
Last Trade: 180.51 @ 4200
Price moved up!
```

### **t = 750ms (New ask appears)**
```
New: 5000 shares ask at 180.52

Asks: [180.52: 8800, 180.53: 2900]  // Volume increased
Bids: [180.50: 8000, 180.49: 3200]
OBI = +0.156 (still bullish but weakening)
```

### **t = 1000ms (Final State)**
```
Asks: [180.52: 8800, 180.53: 2900]
Bids: [180.50: 8000, 180.49: 3200]
Price: 180.51 → 180.52 (moved up 1 tick)
```

**HFT Algorithm Action:**
- Detected OBI spike at t=250ms
- Bought at 180.51 (best ask)
- Price moved to 180.52
- Profit: $0.01/share on 1000 shares = $10 in 500ms

---

## What We Generate vs Real Exchange Data

### **Real Exchange (Actual Market)**

**Data Feed:** ITCH, OUCH, FIX protocol
```
Message Type: ADD ORDER
Order ID: 12345
Side: BUY
Price: 180.50
Volume: 1000
Timestamp: 10:30:00.123456

Message Type: TRADE
Order ID: 12345
Price: 180.50
Volume: 500
Timestamp: 10:30:00.234567

Message Type: CANCEL
Order ID: 12345
Remaining: 500
Timestamp: 10:30:00.345678
```

**Characteristics:**
- 100,000+ messages per second per token
- Microsecond timestamps
- Each order has unique ID
- Full audit trail

---

### **Our Simulation (Synthetic Order Book)**

**Data Feed:** Kafka messages
```json
{
  "token": "AAPL",
  "timestamp": "2026-07-24T10:30:00.123Z",
  "bids": [
    {"price": 180.50, "volume": 5000},
    {"price": 180.49, "volume": 3200}
  ],
  "asks": [
    {"price": 180.51, "volume": 4200},
    {"price": 180.52, "volume": 3800}
  ]
}
```

**Characteristics:**
- **1 message per second per token** (much slower than real)
- Millisecond timestamps (not microseconds)
- No individual order IDs (aggregate snapshot)
- Realistic order book **dynamics** but simplified

**Why this is sufficient for learning:**
- ✅ Contains all necessary data for HFT strategies
- ✅ Shows order book imbalance
- ✅ Demonstrates depth
- ✅ Enables OBI, microprice, VPIN calculations
- ✅ Runs on laptop (real exchange feed needs server cluster)

---

## Order Book Generation Strategy

### **How We'll Simulate Realistic Order Books**

We'll use **stochastic models** to generate order book dynamics:

#### **1. Price Process (Geometric Brownian Motion)**
```python
# Base price follows GBM
price[t] = price[t-1] * exp((drift - 0.5*volatility^2)*dt + volatility*sqrt(dt)*random_normal())
```

#### **2. Spread Model**
```python
# Spread is mean-reverting around typical spread
spread[t] = typical_spread + spread_noise[t]
typical_spread = 0.01  # For AAPL (1 cent)
```

#### **3. Order Arrival (Poisson Process)**
```python
# Orders arrive at random times
arrival_rate = 10 orders/second
inter_arrival_time ~ Exponential(1/arrival_rate)
```

#### **4. Order Size (Power Law Distribution)**
```python
# Most orders are small, few are large
order_size ~ Pareto(alpha=1.5, scale=100)
# Produces: 100, 200, 500, 1000, occasionally 10000
```

#### **5. Order Book Imbalance (Mean-Reverting)**
```python
# OBI fluctuates but reverts to zero
OBI[t] = 0.9 * OBI[t-1] + random_normal(0, 0.1)
```

#### **6. Volume Distribution**
```python
# Volumes at each level decrease exponentially
level_1_volume = base_volume
level_2_volume = base_volume * 0.7
level_3_volume = base_volume * 0.5
level_4_volume = base_volume * 0.4
level_5_volume = base_volume * 0.3
```

---

## Example: Complete Order Book Message

```json
{
  "token": "AAPL",
  "timestamp": "2026-07-24T10:30:00.123Z",
  
  "bids": [
    {
      "price": 180.50,
      "volume": 5000,
      "num_orders": 23,
      "level": 1
    },
    {
      "price": 180.49,
      "volume": 3200,
      "num_orders": 15,
      "level": 2
    },
    {
      "price": 180.48,
      "volume": 4100,
      "num_orders": 19,
      "level": 3
    },
    {
      "price": 180.47,
      "volume": 2800,
      "num_orders": 12,
      "level": 4
    },
    {
      "price": 180.46,
      "volume": 3500,
      "num_orders": 16,
      "level": 5
    }
  ],
  
  "asks": [
    {
      "price": 180.51,
      "volume": 4200,
      "num_orders": 21,
      "level": 1
    },
    {
      "price": 180.52,
      "volume": 3800,
      "num_orders": 18,
      "level": 2
    },
    {
      "price": 180.53,
      "volume": 2900,
      "num_orders": 14,
      "level": 3
    },
    {
      "price": 180.54,
      "volume": 4500,
      "num_orders": 20,
      "level": 4
    },
    {
      "price": 180.55,
      "volume": 3100,
      "num_orders": 13,
      "level": 5
    }
  ],
  
  "derived_metrics": {
    "best_bid": 180.50,
    "best_ask": 180.51,
    "mid_price": 180.505,
    "microprice": 180.507,
    "spread": 0.01,
    "spread_bps": 5.54,
    "obi_l1": 0.087,
    "obi_l5": 0.003,
    "bid_depth_l5": 18600,
    "ask_depth_l5": 18500,
    "depth_imbalance": 1.005
  },
  
  "last_trade": {
    "price": 180.51,
    "volume": 100,
    "timestamp": "2026-07-24T10:29:59.987Z",
    "side": "BUY"
  }
}
```

---

## Comparison Table: Tick Data vs Order Book Data

| Aspect | Simple Tick Data | Order Book Data |
|--------|------------------|-----------------|
| **Information** | Last trade price & volume | All pending orders (bids/asks) |
| **Predictive Power** | Low (lagging) | High (shows intent) |
| **Data Size** | Small (~100 bytes) | Larger (~1 KB with 5 levels) |
| **Update Frequency** | Every trade | Every order book change |
| **HFT Strategies** | Limited | Full range (OBI, microprice, VPIN) |
| **Spread Visibility** | No | Yes |
| **Depth Visibility** | No | Yes |
| **Liquidity Info** | No | Yes |
| **Example Use** | Chart display | Market making, arbitrage |

---

## Key Takeaways

1. **Order Book = Complete picture of supply and demand**
   - Not just last trade price
   - Shows all pending buy/sell orders

2. **HFT Requires Order Book Data**
   - Can't do OBI without knowing bid/ask volumes
   - Can't calculate microprice without order book
   - Can't detect toxicity without order flow

3. **Order Books Are Dynamic**
   - Change every millisecond
   - Orders arrive, fill, cancel constantly
   - Imbalance shifts predict price moves

4. **Our Simulation Is Realistic Enough**
   - 1 message/sec (vs 100k/sec real)
   - Contains all necessary data
   - Enables HFT strategy development
   - Runs on laptop

5. **Next Steps**
   - Implement order book generator
   - Generate realistic bid/ask levels
   - Calculate derived metrics (OBI, microprice)
   - Feed to strategies

---

## Further Reading

- **Concepts:** `02-order-book-features.md` (OBI, microprice, VPIN formulas)
- **Implementation:** `../planning/03-order-book-data-format.md` (technical spec)
- **Literature:** 
  - "Market Microstructure in Practice" - Lehalle & Laruelle
  - "Algorithmic and High-Frequency Trading" - Cartea, Jaimungal, Penalva
  - "The Science of Algorithmic Trading" - Kissell
