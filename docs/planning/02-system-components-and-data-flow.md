# System Components and Data Flow

## Overview

This document explains how all system components connect and how data flows through the quantitative trading dashboard platform. Written at a beginner level for those new to HFT system architecture.

---

## 1. What Data Do We Generate?

### **Core Data Unit: Order Book Snapshot**

Every 1 second, for each token, we generate a complete order book snapshot:

```json
{
  "token": "AAPL",
  "timestamp": "2026-07-24T10:30:00.000Z",
  
  "bids": [
    {"price": 180.50, "volume": 5000, "level": 1},
    {"price": 180.49, "volume": 3200, "level": 2},
    {"price": 180.48, "volume": 4100, "level": 3},
    {"price": 180.47, "volume": 2800, "level": 4},
    {"price": 180.46, "volume": 3500, "level": 5}
  ],
  
  "asks": [
    {"price": 180.51, "volume": 4200, "level": 1},
    {"price": 180.52, "volume": 3800, "level": 2},
    {"price": 180.53, "volume": 2900, "level": 3},
    {"price": 180.54, "volume": 4500, "level": 4},
    {"price": 180.55, "volume": 3100, "level": 5}
  ]
}
```

**Key Points:**
- **5 levels** on each side (bids and asks)
- **Bids** = Buy orders (people want to buy at these prices)
- **Asks** = Sell orders (people want to sell at these prices)
- **Level 1** = Best bid/ask (top of book)
- **Volume** = Number of shares/units at each price

---

## 2. System Components

### **Component Architecture (7 Services)**

```
┌─────────────────────────────────────────────────────────┐
│  1. ORDER BOOK GENERATOR (Java Spring Boot)            │
│     - Generates 100 tokens × 1 msg/sec                  │
│     - Simulates realistic order book dynamics           │
│     - Uses stochastic models (GBM, Poisson, etc.)       │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ↓ Kafka Topic: "order-book-data"
                  │ (100 msg/sec)
                  │
        ┌─────────┼─────────┐
        ↓         ↓         ↓
   ┌─────────┐ ┌──────────┐ ┌─────────────┐
   │2. Feature│ │3. Database│ │(Optional:   │
   │Calculator│ │Writer    │ │ Direct feed │
   │(Java)   │ │(Java)    │ │ to dash)    │
   └────┬────┘ └────┬─────┘ └─────────────┘
        │           │
        ↓           ↓
   Kafka Topic:  QuestDB
   "features"    ├─ order_book_snapshots
   (100 msg/s)   └─ features
        │
        ↓
   ┌──────────────────────────────────────┐
   │4. STRATEGY ENGINE (Java Spring Boot) │
   │   - 30 quantitative strategies       │
   │   - Analyzes features                │
   │   - Generates BUY/SELL signals       │
   └──────────┬───────────────────────────┘
              │
              ↓ Kafka Topic: "signals"
              │
   ┌──────────┴─────────────────────────┐
   │5. SIGNAL AGGREGATOR (Java)         │
   │   - Deduplicates signals           │
   │   - Tracks positions               │
   │   - Calculates PnL                 │
   │   - Detects conflicts              │
   └──────────┬─────────────────────────┘
              │
              ↓
   ┌──────────────────────────────────┐
   │   QuestDB Time-Series Database   │
   │   ├─ signals                     │
   │   ├─ strategy_pnl                │
   │   └─ positions                   │
   └──────────┬───────────────────────┘
              │
              ↓
   ┌──────────────────────────────────┐
   │6. API GATEWAY (Java Spring Boot) │
   │   - REST API (historical data)   │
   │   - WebSocket (live updates)     │
   └──────────┬───────────────────────┘
              │
              ↓ HTTP/WebSocket
              │
   ┌──────────────────────────────────┐
   │7. REACT DASHBOARD (TypeScript)   │
   │   - Live monitoring UI           │
   │   - Strategy performance         │
   │   - Intraday charts              │
   └──────────────────────────────────┘
```

---

## 3. Component Details

### **Component 1: Order Book Generator**

**Purpose:** Creates simulated order book data

**Technology:** Java Spring Boot

**How It Works:**
```python
# Simplified logic (actual implementation in Java)
while True:
    for token in 100_tokens:
        # Step 1: Update price (random walk)
        price = price + random_normal(0, volatility)
        
        # Step 2: Generate bids (below price)
        bids = [
            {"price": price - 0.01, "volume": random(3000, 7000)},
            {"price": price - 0.02, "volume": random(2000, 5000)},
            # ... 5 levels
        ]
        
        # Step 3: Generate asks (above price)
        asks = [
            {"price": price + 0.01, "volume": random(3000, 7000)},
            {"price": price + 0.02, "volume": random(2000, 5000)},
            # ... 5 levels
        ]
        
        # Step 4: Send to Kafka
        kafka.send("order-book-data", {
            "token": token,
            "timestamp": now(),
            "bids": bids,
            "asks": asks
        })
    
    sleep(1 second)
```

**Output Rate:** 100 messages/second (1 per token)

**Message Size:** ~500 bytes per message → 50 KB/sec total

---

### **Component 2: Feature Calculator**

**Purpose:** Calculates derived metrics from raw order book

**Technology:** Java Spring Boot + Kafka Streams

**Input (from Kafka):**
```json
{
  "token": "AAPL",
  "bids": [{"price": 180.50, "volume": 5000}, ...],
  "asks": [{"price": 180.51, "volume": 4200}, ...]
}
```

**Processing:**
```python
def calculate_features(order_book):
    # Extract top of book
    best_bid_price = order_book['bids'][0]['price']      # 180.50
    best_bid_volume = order_book['bids'][0]['volume']    # 5000
    best_ask_price = order_book['asks'][0]['price']      # 180.51
    best_ask_volume = order_book['asks'][0]['volume']    # 4200
    
    # 1. Order Book Imbalance (OBI)
    obi = (best_bid_volume - best_ask_volume) / (best_bid_volume + best_ask_volume)
    # obi = (5000 - 4200) / 9200 = 0.087
    
    # 2. Microprice (volume-weighted mid)
    microprice = (best_bid_price * best_ask_volume + best_ask_price * best_bid_volume) / \
                 (best_bid_volume + best_ask_volume)
    # microprice = (180.50*4200 + 180.51*5000) / 9200 = 180.507
    
    # 3. Spread
    spread = best_ask_price - best_bid_price  # 0.01
    
    # 4. Mid-price
    mid_price = (best_bid_price + best_ask_price) / 2  # 180.505
    
    return {
        "obi": obi,
        "microprice": microprice,
        "spread": spread,
        "mid_price": mid_price
    }
```

**Output (to Kafka):**
```json
{
  "token": "AAPL",
  "timestamp": "2026-07-24T10:30:00.000Z",
  "obi": 0.087,
  "microprice": 180.507,
  "spread": 0.01,
  "mid_price": 180.505,
  "spread_bps": 5.54
}
```

**Output Rate:** 100 messages/second (matches input rate)

---

### **Component 3: Database Writer**

**Purpose:** Persists raw order book data to QuestDB

**Technology:** Java Spring Boot + QuestDB JDBC

**Tables Created:**
```sql
CREATE TABLE order_book_snapshots (
    token SYMBOL,
    timestamp TIMESTAMP,
    best_bid_price DOUBLE,
    best_bid_volume DOUBLE,
    best_ask_price DOUBLE,
    best_ask_volume DOUBLE,
    bid_depth_l5 DOUBLE,      -- Total volume across 5 levels
    ask_depth_l5 DOUBLE
) TIMESTAMP(timestamp) PARTITION BY DAY;

CREATE TABLE features (
    token SYMBOL,
    timestamp TIMESTAMP,
    obi DOUBLE,
    microprice DOUBLE,
    spread DOUBLE,
    mid_price DOUBLE
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

**Write Pattern:** Batch writes every 1 second (100 rows per batch)

---

### **Component 4: Strategy Engine**

**Purpose:** Implements 30 quantitative strategies

**Technology:** Java Spring Boot + Kafka Streams

**Input (from Kafka):**
```json
{
  "token": "AAPL",
  "obi": 0.087,
  "microprice": 180.507,
  "spread": 0.01
}
```

**Strategy Examples:**

#### **Strategy 1: OBI Market Making**
```python
def obi_strategy(features):
    obi = features['obi']
    
    if obi > 0.5:
        return {
            "action": "BUY",
            "reason": "Strong buy pressure (OBI > 0.5)",
            "confidence": 0.85
        }
    elif obi < -0.5:
        return {
            "action": "SELL",
            "reason": "Strong sell pressure (OBI < -0.5)",
            "confidence": 0.85
        }
    else:
        return None  # No signal
```

#### **Strategy 2: Pairs Trading**
```python
def pairs_strategy(aapl_price, msft_price, historical_spread):
    current_spread = aapl_price - msft_price
    z_score = (current_spread - mean(historical_spread)) / std(historical_spread)
    
    if z_score > 2.0:
        return [
            {"token": "AAPL", "action": "SELL"},
            {"token": "MSFT", "action": "BUY"}
        ]
    elif z_score < -2.0:
        return [
            {"token": "AAPL", "action": "BUY"},
            {"token": "MSFT", "action": "SELL"}
        ]
```

**Output (to Kafka):**
```json
{
  "strategy": "OBI_Market_Making",
  "token": "AAPL",
  "action": "BUY",
  "price": 180.507,
  "confidence": 0.85,
  "reason": "Strong buy pressure (OBI > 0.5)",
  "timestamp": "2026-07-24T10:30:00.000Z"
}
```

**Output Rate:** Variable (only when signals generated, typically 5-20/sec)

---

### **Component 5: Signal Aggregator**

**Purpose:** Consolidates signals, tracks positions, calculates PnL

**Technology:** Java Spring Boot + Kafka Streams

**Responsibilities:**

1. **Deduplication**
   ```python
   # If 3 strategies all say BUY AAPL, consolidate to 1 signal
   signals = [
       {"strategy": "OBI", "token": "AAPL", "action": "BUY"},
       {"strategy": "Kalman", "token": "AAPL", "action": "BUY"},
       {"strategy": "Momentum", "token": "AAPL", "action": "BUY"}
   ]
   
   consolidated = {
       "token": "AAPL",
       "action": "BUY",
       "consensus_count": 3,
       "strategies": ["OBI", "Kalman", "Momentum"]
   }
   ```

2. **Conflict Detection**
   ```python
   # If 2 strategies disagree
   signals = [
       {"strategy": "OBI", "token": "AAPL", "action": "BUY"},
       {"strategy": "MeanRevert", "token": "AAPL", "action": "SELL"}
   ]
   
   # Flag as conflict, don't execute
   alert = {
       "type": "CONFLICT",
       "token": "AAPL",
       "strategies": ["OBI says BUY", "MeanRevert says SELL"]
   }
   ```

3. **Position Tracking**
   ```python
   # Track what each strategy is holding
   positions = {
       "OBI_Market_Making": {
           "AAPL": {"shares": 100, "avg_price": 180.50, "unrealized_pnl": 0.70}
       },
       "Pairs_Trading": {
           "AAPL": {"shares": -50, "avg_price": 181.00, "unrealized_pnl": -25.00},
           "MSFT": {"shares": 50, "avg_price": 420.00, "unrealized_pnl": 10.00}
       }
   }
   ```

4. **PnL Calculation**
   ```python
   def calculate_pnl(position, current_price):
       unrealized_pnl = position['shares'] * (current_price - position['avg_price'])
       return unrealized_pnl
   
   # Example: Bought 100 AAPL @ 180.50, now 180.57
   pnl = 100 * (180.57 - 180.50) = +$7.00
   ```

**Output (to Database):**
```sql
INSERT INTO signals VALUES ('OBI_Market_Making', 'AAPL', 'BUY', 180.507, ...);
INSERT INTO positions VALUES ('OBI_Market_Making', 'AAPL', 100, 180.507, 0.70);
INSERT INTO strategy_pnl VALUES ('OBI_Market_Making', 'AAPL', 0, 0.70, now());
```

---

### **Component 6: API Gateway**

**Purpose:** Exposes data to frontend dashboard

**Technology:** Java Spring Boot + WebSocket

**REST API Endpoints:**
```
GET  /api/tokens                     → List all 100 tokens
GET  /api/tokens/{symbol}            → Current state of AAPL
GET  /api/tokens/{symbol}/history    → Intraday price history
GET  /api/strategies                 → List all 30 strategies
GET  /api/strategies/{name}/pnl      → Strategy performance
GET  /api/signals                    → Recent signals (last 1 hour)
POST /api/strategies/{name}/disable  → Turn strategy off
```

**WebSocket Channels:**
```
ws://localhost:8080/ws/live-prices   → Real-time price updates
ws://localhost:8080/ws/signals       → Real-time signal feed
ws://localhost:8080/ws/pnl           → Real-time PnL updates
```

**Example WebSocket Message:**
```json
{
  "channel": "live-prices",
  "data": {
    "token": "AAPL",
    "price": 180.507,
    "obi": 0.087,
    "timestamp": "2026-07-24T10:30:00.000Z"
  }
}
```

---

### **Component 7: React Dashboard**

**Purpose:** User interface for monitoring

**Technology:** React + TypeScript + WebSocket

**Pages:**

1. **Live Overview** (`/`)
   - Grid of all 100 tokens
   - Current price, OBI, spread
   - Color-coded by signal (green = buy, red = sell)
   - Auto-updates via WebSocket

2. **Strategy Performance** (`/strategies`)
   - Table of 30 strategies
   - Realized PnL, Unrealized PnL
   - Win rate, Sharpe ratio
   - Charts showing PnL over time

3. **Token Detail** (`/token/AAPL`)
   - Order book visualization (bid/ask levels)
   - Intraday price chart
   - Recent signals for this token
   - Which strategies are trading it

4. **Signals Feed** (`/signals`)
   - Real-time log of all signals
   - Filterable by strategy, token, action
   - Shows reasoning for each signal

---

## 4. Complete Data Flow Example

### **Scenario: AAPL Order Book Update → Signal → Dashboard**

**Timeline:**

```
t=0ms: Order Book Generator creates AAPL snapshot
┌────────────────────────────────────────────────┐
│ Order Book Generator                           │
│ Creates: {"token": "AAPL",                     │
│          "bids": [180.50: 5000, ...],          │
│          "asks": [180.51: 4200, ...]}          │
└──────────────────┬─────────────────────────────┘
                   │
                   ↓ Send to Kafka (1ms)
                   
t=1ms: Message arrives in Kafka
┌────────────────────────────────────────────────┐
│ Kafka Topic: "order-book-data"                 │
│ Message stored in memory                       │
└──────┬─────────────────────┬───────────────────┘
       │                     │
       ↓ (3 consumers)       ↓
       
t=2ms: Consumers receive message (parallel)
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│Feature Calc  │  │Database      │  │(Dashboard    │
│Reads message │  │Writer reads  │  │ optional)    │
└──────┬───────┘  └──────┬───────┘  └──────────────┘
       │                 │
       ↓                 ↓
       
t=7ms: Feature Calculator processes
┌────────────────────────────────────────────────┐
│ Feature Calculator                             │
│ Calculates: OBI = 0.087                        │
│            microprice = 180.507                │
│ Sends to Kafka topic "features"                │
└──────────────────┬─────────────────────────────┘
                   │
                   ↓
                   
t=8ms: Database Writer persists
┌────────────────────────────────────────────────┐
│ Database Writer                                │
│ INSERT INTO order_book_snapshots VALUES (...)  │
└────────────────────────────────────────────────┘

t=10ms: Strategy Engine receives features
┌────────────────────────────────────────────────┐
│ Strategy Engine - 30 strategies run            │
│ ├─ OBI Strategy: obi=0.087 → No signal        │
│ ├─ Pairs Trading: checking AAPL vs MSFT...    │
│ ├─ Kalman Filter: Detects uptrend → BUY!      │
│ └─ ... (27 other strategies)                  │
└──────────────────┬─────────────────────────────┘
                   │
                   ↓ Send signal to Kafka
                   
t=20ms: Signal sent to Kafka
┌────────────────────────────────────────────────┐
│ Kafka Topic: "signals"                         │
│ {"strategy": "Kalman_Trend",                   │
│  "token": "AAPL",                              │
│  "action": "BUY",                              │
│  "price": 180.507}                             │
└──────────────────┬─────────────────────────────┘
                   │
                   ↓
                   
t=22ms: Signal Aggregator processes
┌────────────────────────────────────────────────┐
│ Signal Aggregator                              │
│ - Check for conflicts: None                    │
│ - Update position: Kalman now long 100 AAPL   │
│ - Calculate PnL: 0 (just entered)              │
│ - Store to database                            │
└──────────────────┬─────────────────────────────┘
                   │
                   ↓
                   
t=25ms: Database write complete
┌────────────────────────────────────────────────┐
│ QuestDB                                        │
│ INSERT INTO signals VALUES (...)               │
│ INSERT INTO positions VALUES (...)             │
└────────────────────────────────────────────────┘

t=30ms: API Gateway broadcasts via WebSocket
┌────────────────────────────────────────────────┐
│ API Gateway                                    │
│ WebSocket broadcast:                           │
│ ws://localhost:8080/ws/signals                 │
│ → {"strategy": "Kalman_Trend", "action": "BUY"}│
└──────────────────┬─────────────────────────────┘
                   │
                   ↓ Internet/localhost
                   
t=35ms: React Dashboard receives update
┌────────────────────────────────────────────────┐
│ React Dashboard (Browser)                      │
│ WebSocket message received                     │
│ → Update UI: Show "Kalman bought AAPL"         │
│ → Highlight AAPL row in green                  │
│ → Add to signals feed                          │
└────────────────────────────────────────────────┘

TOTAL TIME: 35 milliseconds from generation to display
```

---

## 5. Data Formats

### **5.1 Order Book Snapshot (Kafka Message)**

**Topic:** `order-book-data`

**Schema:**
```json
{
  "token": "string",           // Symbol: AAPL, BTC, etc.
  "timestamp": "ISO-8601",     // 2026-07-24T10:30:00.000Z
  "bids": [                    // Buy orders (descending price)
    {
      "price": "double",       // Price level
      "volume": "double",      // Shares/units at this price
      "level": "int"           // 1-5 (1 = best)
    }
  ],
  "asks": [                    // Sell orders (ascending price)
    {
      "price": "double",
      "volume": "double",
      "level": "int"
    }
  ]
}
```

**Message Size:** ~500 bytes

**Frequency:** 1 message/second/token = 100 messages/second total

---

### **5.2 Calculated Features (Kafka Message)**

**Topic:** `features`

**Schema:**
```json
{
  "token": "string",
  "timestamp": "ISO-8601",
  "obi": "double",              // Order Book Imbalance: -1.0 to +1.0
  "microprice": "double",       // Volume-weighted mid-price
  "spread": "double",           // Best ask - best bid
  "spread_bps": "double",       // Spread in basis points
  "mid_price": "double",        // (Best bid + best ask) / 2
  "bid_depth_l5": "double",     // Total bid volume (5 levels)
  "ask_depth_l5": "double"      // Total ask volume (5 levels)
}
```

---

### **5.3 Trading Signal (Kafka Message)**

**Topic:** `signals`

**Schema:**
```json
{
  "strategy": "string",         // Strategy name
  "token": "string",            // Symbol
  "action": "enum",             // BUY, SELL, CLOSE
  "price": "double",            // Execution price
  "confidence": "double",       // 0.0 - 1.0
  "reason": "string",           // Human-readable explanation
  "timestamp": "ISO-8601"
}
```

---

### **5.4 Position (Database Table)**

**Table:** `positions`

```sql
CREATE TABLE positions (
    strategy SYMBOL,              -- Strategy name
    token SYMBOL,                 -- Symbol
    shares DOUBLE,                -- Positive = long, negative = short
    avg_entry_price DOUBLE,       -- Average entry price
    unrealized_pnl DOUBLE,        -- Current profit/loss
    timestamp TIMESTAMP
) TIMESTAMP(timestamp);
```

---

### **5.5 Strategy PnL (Database Table)**

**Table:** `strategy_pnl`

```sql
CREATE TABLE strategy_pnl (
    strategy SYMBOL,
    timestamp TIMESTAMP,
    realized_pnl DOUBLE,          -- Closed position P&L
    unrealized_pnl DOUBLE,        -- Open position P&L
    total_pnl DOUBLE,             -- realized + unrealized
    num_trades INT,
    win_rate DOUBLE
) TIMESTAMP(timestamp) PARTITION BY HOUR;
```

---

## 6. Data Source Strategy

### **Why Simulation Instead of Real Data?**

| Aspect | Real Exchange Data | Simulated Data |
|--------|-------------------|----------------|
| **Cost** | $1,000-$10,000/month | Free |
| **Complexity** | Exchange APIs, auth, rate limits | Full control |
| **Consistency** | Market hours only (stocks) | 24/7 |
| **Reproducibility** | Can't replay exact scenarios | Deterministic |
| **Development** | Need production credentials | Works locally |

**Decision: Use simulation for development**

---

### **How We Simulate Order Books**

#### **Model 1: Price Process (Geometric Brownian Motion)**

```python
# Price follows random walk with drift
price[t] = price[t-1] * exp((drift - 0.5*volatility^2)*dt + volatility*sqrt(dt)*Z)

# Where:
# drift = 0.05  (5% annual return)
# volatility = 0.015  (1.5% daily vol for tech stocks)
# dt = 1 second / (252 trading days * 6.5 hours * 3600 seconds) ≈ 1.7e-7
# Z ~ Normal(0, 1)
```

#### **Model 2: Spread Generation**

```python
# Spread depends on token category
spread = {
    "AAPL": 0.01,      # Tech: 1 cent
    "BTC": 0.50,       # Crypto: 50 cents
    "NEE": 0.03        # Utilities: 3 cents
}

# Add noise
actual_spread = spread * (1 + random_normal(0, 0.1))
```

#### **Model 3: Volume Distribution**

```python
# Volumes decrease exponentially with level
base_volume = random(3000, 7000)  # Level 1
volumes = [
    base_volume * 1.0,   # Level 1: 100%
    base_volume * 0.7,   # Level 2: 70%
    base_volume * 0.5,   # Level 3: 50%
    base_volume * 0.4,   # Level 4: 40%
    base_volume * 0.3    # Level 5: 30%
]
```

#### **Model 4: Order Book Imbalance (Mean-Reverting)**

```python
# OBI fluctuates but reverts to zero
OBI[t] = 0.9 * OBI[t-1] + random_normal(0, 0.1)

# Then adjust bid/ask volumes to match OBI
bid_volume = base_volume * (1 + OBI)
ask_volume = base_volume * (1 - OBI)
```

---

## 7. Frequency and Timing

### **7.1 Data Generation**

| Metric | Value |
|--------|-------|
| Tokens | 100 |
| Messages per token per second | 1 |
| **Total messages per second** | **100** |
| Message size | ~500 bytes |
| **Total throughput** | **50 KB/sec** |

**Is this realistic?**
- Real exchanges: 100,000+ messages/sec/token
- Our simulation: 1 message/sec/token
- **300x slower than reality, but sufficient for learning**

---

### **7.2 Processing Latency**

| Stage | Time |
|-------|------|
| Generate order book | 0ms |
| Send to Kafka | 1ms |
| Feature calculation | 5ms |
| Strategy analysis | 10ms |
| Signal aggregation | 5ms |
| Database write | 3ms |
| WebSocket broadcast | 5ms |
| **Total end-to-end** | **~30-50ms** |

---

### **7.3 Storage Requirements**

#### **Order Book Snapshots**
```
100 tokens × 1 msg/sec × 86,400 sec/day × 500 bytes = 4.3 GB/day
```

#### **Features**
```
100 tokens × 1 msg/sec × 86,400 sec/day × 200 bytes = 1.7 GB/day
```

#### **Signals** (assume 10/sec average)
```
10 signals/sec × 86,400 sec/day × 300 bytes = 260 MB/day
```

**Total: ~6 GB/day** (manageable with QuestDB compression)

---

## 8. Technology Stack

| Component | Technology | Reason |
|-----------|-----------|--------|
| **Order Book Generator** | Java Spring Boot | Performance, threading, Kafka integration |
| **Message Broker** | Kafka | Industry standard for streaming, decouples services |
| **Feature Calculator** | Kafka Streams | Stream processing, exactly-once semantics |
| **Strategy Engine** | Java Spring Boot | Complex logic, multi-threading, fast execution |
| **Signal Aggregator** | Kafka Streams | Stateful aggregation, windowing |
| **Database** | QuestDB | Time-series optimized, fast ingestion, SQL queries |
| **API Gateway** | Spring Boot + WebSocket | REST + real-time streaming |
| **Dashboard** | React + TypeScript | Modern UI, real-time updates, type safety |
| **Orchestration** | Docker Compose | Local development, easy setup |

---

## 9. Key Design Decisions

### **Decision 1: Order Book Format**
- **Chosen:** 5 levels (bids + asks)
- **Alternatives:** 1 level (too simple), 10 levels (unnecessary overhead)
- **Rationale:** 5 levels capture depth without excessive data

### **Decision 2: Data Source**
- **Chosen:** Simulated/generated
- **Alternatives:** Real exchange feeds
- **Rationale:** No cost, full control, reproducible, works locally

### **Decision 3: Generation Frequency**
- **Chosen:** 1 message/second/token
- **Alternatives:** 10/sec (too much), 0.1/sec (too slow)
- **Rationale:** Balance between realism and laptop resources

### **Decision 4: Message Broker**
- **Chosen:** Kafka
- **Alternatives:** RabbitMQ, Redis Streams, AWS Kinesis
- **Rationale:** Already familiar, industry standard for HFT-like systems

### **Decision 5: Database**
- **Chosen:** QuestDB
- **Alternatives:** TimescaleDB, InfluxDB, Cassandra
- **Rationale:** Already in docker-compose, SQL interface, fast ingestion

---

## 10. Development vs Production Differences

| Aspect | Development (Local) | Production (Server) |
|--------|---------------------|---------------------|
| **Tokens** | 100 | 30,000 (300x) |
| **Throughput** | 100 msg/sec | 30,000 msg/sec |
| **Hardware** | 1 laptop | Cluster (5-10 servers) |
| **Kafka** | 1 broker | 3+ brokers (replication) |
| **QuestDB** | 1 instance | Sharded/replicated |
| **Strategies** | All 30 in 1 process | Distributed (5 instances × 6 strategies) |
| **Storage** | 6 GB/day | 1.8 TB/day |

**Scaling strategy:** Horizontal partitioning by token (shard by symbol)

---

## 11. Next Steps

1. ✅ **Components defined** (this document)
2. ✅ **Data formats specified** (this document)
3. ⏭️ **Implement Order Book Generator** - Java service to generate snapshots
4. ⏭️ **Implement Feature Calculator** - Kafka Streams app for OBI, microprice
5. ⏭️ **Implement 1-2 sample strategies** - Prove the pipeline works
6. ⏭️ **Implement Signal Aggregator** - Position tracking, PnL
7. ⏭️ **Build basic React dashboard** - Display live data

---

## 12. Questions Answered

### **Q1: How is order book different from simple price ticks?**
**A:** See `docs/concepts/01-order-book-fundamentals.md` - order book shows ALL pending orders (supply/demand), not just last trade

### **Q2: Where does the data come from?**
**A:** We simulate it using stochastic models (GBM for price, Poisson for arrivals) - realistic but generated locally

### **Q3: What does "1 message/second/token" mean?**
**A:** Every 1 second, each of the 100 tokens gets a new order book snapshot sent to Kafka = 100 total messages per second

### **Q4: How do components connect?**
**A:** All communication via Kafka topics - producer/consumer pattern, fully decoupled

### **Q5: How fast is the system?**
**A:** ~30-50ms from data generation to dashboard display (end-to-end latency)

---

## References

- **Kafka Documentation:** https://kafka.apache.org/documentation/
- **QuestDB Documentation:** https://questdb.io/docs/
- **Spring Boot + Kafka:** https://spring.io/projects/spring-kafka
- **React WebSocket:** https://developer.mozilla.org/en-US/docs/Web/API/WebSocket
- **Market Microstructure:** Lehalle & Laruelle (2013)
