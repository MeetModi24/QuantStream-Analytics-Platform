# Token Selection Strategy for HFT System

## Overview

This document defines the 100-token portfolio for the development environment of our quantitative trading dashboard platform.

---

## Why Token Selection Matters in HFT

Real HFT firms don't trade everything—they focus on:
- **High liquidity** (tight spreads, deep order books)
- **High volatility** (enough movement to profit from)
- **Known correlations** (for statistical arbitrage)
- **Diverse sectors** (avoid correlated risk)

---

## 100-Token Portfolio Breakdown

### **Category 1: Large-Cap Tech Stocks (20 tokens)**
*Highest liquidity, tight spreads, HFT favorites*

```
AAPL  - Apple
MSFT  - Microsoft
GOOGL - Alphabet
AMZN  - Amazon
META  - Meta
NVDA  - NVIDIA
TSLA  - Tesla
NFLX  - Netflix
AMD   - Advanced Micro Devices
INTC  - Intel
QCOM  - Qualcomm
AVGO  - Broadcom
ADBE  - Adobe
CRM   - Salesforce
ORCL  - Oracle
CSCO  - Cisco
IBM   - IBM
SHOP  - Shopify
UBER  - Uber
PYPL  - PayPal
```

**Characteristics:**
- Tightest spreads (0.01% or less)
- Massive volume (millions of shares/day)
- High correlation within sector (good for pairs trading)
- Best for: Pairs trading, momentum strategies

---

### **Category 2: Major Crypto Assets (15 tokens)**
*24/7 trading, high volatility, crypto-specific strategies*

```
BTC   - Bitcoin
ETH   - Ethereum
BNB   - Binance Coin
SOL   - Solana
XRP   - Ripple
ADA   - Cardano
AVAX  - Avalanche
MATIC - Polygon
DOT   - Polkadot
LINK  - Chainlink
UNI   - Uniswap
ATOM  - Cosmos
LTC   - Litecoin
BCH   - Bitcoin Cash
ALGO  - Algorand
```

**Characteristics:**
- Different microstructure than stocks
- Higher volatility (more signals)
- 24/7 trading (no market close)
- Best for: Volatility strategies, OBI market making

---

### **Category 3: Financial Stocks (15 tokens)**
*Banks, payment processors—correlated during macro events*

```
JPM   - JPMorgan Chase
BAC   - Bank of America
WFC   - Wells Fargo
C     - Citigroup
GS    - Goldman Sachs
MS    - Morgan Stanley
V     - Visa
MA    - Mastercard
AXP   - American Express
BLK   - BlackRock
SCHW  - Charles Schwab
USB   - U.S. Bancorp
PNC   - PNC Financial
TFC   - Truist Financial
COF   - Capital One
```

**Characteristics:**
- High intra-sector correlation
- Perfect for pairs trading (JPM vs BAC, V vs MA)
- Sensitive to interest rate news (tests VPIN)
- Best for: Pairs trading, mean reversion

---

### **Category 4: Energy & Commodities (10 tokens)**
*Mean-reverting, different market dynamics*

```
XOM   - Exxon Mobil
CVX   - Chevron
COP   - ConocoPhillips
SLB   - Schlumberger
MPC   - Marathon Petroleum
PSX   - Phillips 66
VLO   - Valero Energy
OXY   - Occidental Petroleum
HAL   - Halliburton
BP    - BP
```

**Characteristics:**
- Mean-reverting behavior (O-U strategy target)
- Lower correlation with tech
- Oil price driven (macro factor)
- Best for: Ornstein-Uhlenbeck mean reversion

---

### **Category 5: Healthcare/Pharma (10 tokens)**
*Stable, less volatile, good for market making*

```
JNJ   - Johnson & Johnson
UNH   - UnitedHealth
PFE   - Pfizer
ABBV  - AbbVie
TMO   - Thermo Fisher
ABT   - Abbott Labs
MRK   - Merck
LLY   - Eli Lilly
BMY   - Bristol Myers Squibb
AMGN  - Amgen
```

**Characteristics:**
- Lower volatility (stable spreads)
- Good for market making strategies
- Defensive sector (different risk profile)
- Best for: Market making, stable order book strategies

---

### **Category 6: Consumer Discretionary (10 tokens)**
*Retail-driven, seasonal patterns*

```
HD    - Home Depot
MCD   - McDonald's
NKE   - Nike
SBUX  - Starbucks
TGT   - Target
LOW   - Lowe's
TJX   - TJ Maxx
CMG   - Chipotle
BKNG  - Booking Holdings
MAR   - Marriott
```

**Characteristics:**
- Consumer behavior driven
- Different volatility profile
- Tests sector rotation strategies
- Best for: Trend following, seasonal strategies

---

### **Category 7: Industrial/Manufacturing (10 tokens)**
*Cyclical, macro-sensitive*

```
CAT   - Caterpillar
BA    - Boeing
HON   - Honeywell
UPS   - UPS
DE    - Deere & Company
GE    - General Electric
MMM   - 3M
RTX   - Raytheon
LMT   - Lockheed Martin
NOC   - Northrop Grumman
```

**Characteristics:**
- Economic cycle sensitive
- Lower trading frequency (longer hold times)
- Tests trend-following strategies
- Best for: Kalman filters, trend detection

---

### **Category 8: Telecom & Utilities (10 tokens)**
*Stable, dividend-focused, low volatility*

```
T     - AT&T
VZ    - Verizon
TMUS  - T-Mobile
NEE   - NextEra Energy
DUK   - Duke Energy
SO    - Southern Company
D     - Dominion Energy
EXC   - Exelon
AEP   - American Electric
XEL   - Xcel Energy
```

**Characteristics:**
- Lowest volatility
- Tests market making in stable environments
- Different order book characteristics
- Best for: Market making, stable strategies

---

## Token Distribution Summary

| Category | Count | Avg Daily Volume | Typical Spread | Strategy Focus |
|----------|-------|------------------|----------------|----------------|
| Tech | 20 | Very High | 0.01% | Pairs, Momentum |
| Crypto | 15 | High (24/7) | 0.05-0.10% | Volatility, OBI |
| Financials | 15 | High | 0.01-0.02% | Pairs, Mean Reversion |
| Energy | 10 | Medium | 0.02% | Mean Reversion |
| Healthcare | 10 | Medium | 0.01% | Market Making |
| Consumer | 10 | Medium | 0.02% | Trend Following |
| Industrial | 10 | Medium | 0.02% | Kalman Filters |
| Utilities | 10 | Low | 0.03% | Stable Market Making |
| **TOTAL** | **100** | | | |

---

## Strategy-to-Token Mapping

### **Strategy 1: Pairs Trading (Statistical Arbitrage)**
**Optimal Tokens:** Tech + Financials (40 tokens)

**Example Pairs:**
```python
pairs = [
    ("AAPL", "MSFT"),    # Tech giants
    ("GOOGL", "META"),   # Ad-driven
    ("JPM", "BAC"),      # Banks
    ("V", "MA"),         # Payment processors
    ("NVDA", "AMD"),     # Semiconductors
    ("GS", "MS"),        # Investment banks
    ("WFC", "USB"),      # Regional banks
    ("AMZN", "SHOP"),    # E-commerce
    ("NFLX", "DIS"),     # Streaming
    ("INTC", "QCOM"),    # Chip makers
]
```

**Why These Tokens:**
- Strong historical correlations (0.7+)
- Similar market cap and liquidity
- Respond to same sector news
- Tight spreads enable frequent trading

---

### **Strategy 2: Mean Reversion (Ornstein-Uhlenbeck)**
**Optimal Tokens:** Energy + Utilities (20 tokens)

**Target Tokens:**
```python
mean_reverting = [
    # Energy (oil-driven, commodity-linked)
    "XOM", "CVX", "COP", "SLB", "MPC",
    
    # Utilities (stable dividends, regulated)
    "T", "VZ", "NEE", "DUK", "SO",
    "D", "EXC", "AEP", "XEL"
]
```

**Why These Tokens:**
- Exhibit clear mean-reverting behavior
- Lower volatility → cleaner signal
- Strong fundamentals → predictable reversion
- Less influenced by momentum traders

---

### **Strategy 3: Trend Following (Kalman Filter)**
**Optimal Tokens:** Tech + Crypto (35 tokens)

**Target Tokens:**
```python
trending = [
    # High-volatility tech
    "TSLA", "NVDA", "AMD", "META", "NFLX",
    
    # Growth stocks
    "SHOP", "UBER", "SQ", "PYPL",
    
    # Major crypto
    "BTC", "ETH", "SOL", "AVAX", "MATIC",
    "BNB", "ADA", "DOT", "LINK", "UNI"
]
```

**Why These Tokens:**
- High volatility → strong trends
- Momentum-driven price action
- Less mean-reverting, more trending
- Crypto adds 24/7 coverage

---

### **Strategy 4: OBI Market Making**
**Optimal Tokens:** Top 20 by volume

**Target Tokens:**
```python
high_liquidity = [
    # Ultra-liquid stocks
    "AAPL", "MSFT", "GOOGL", "AMZN", "TSLA",
    "NVDA", "META", "JPM", "BAC", "V",
    
    # Major crypto
    "BTC", "ETH", "BNB", "SOL", "XRP"
]
```

**Why These Tokens:**
- Tightest spreads (maximize edge)
- Deepest order books (less slippage)
- Highest update frequency (more signals)
- Best for market making strategies

---

### **Strategy 5: VPIN (Volume Toxicity Detection)**
**Optimal Tokens:** All 100 tokens

**Purpose:**
- Monitors for informed trading across entire portfolio
- Detects when institutional orders enter market
- Triggers risk-off mode when toxicity high
- Cross-asset toxicity analysis

---

## Order Book Characteristics by Category

### **Tech Stocks Example: AAPL**
```json
{
  "symbol": "AAPL",
  "spread_bps": 1,        // 0.01%
  "depth_level1": 50000,  // 50k shares at best bid/ask
  "depth_level5": 200000, // 200k shares top 5 levels
  "tick_size": 0.01,
  "update_frequency": "high",  // Many updates/sec
  "avg_daily_volume": 50000000,
  "typical_obi_range": [-0.3, 0.3]
}
```

### **Crypto Example: BTC**
```json
{
  "symbol": "BTC",
  "spread_bps": 5,        // 0.05% (wider than stocks)
  "depth_level1": 10,     // 10 BTC at best bid/ask
  "depth_level5": 50,     // 50 BTC top 5 levels
  "tick_size": 0.01,
  "update_frequency": "very_high",  // 24/7, constant updates
  "avg_daily_volume": 25000,
  "typical_obi_range": [-0.5, 0.5]
}
```

### **Utilities Example: NEE**
```json
{
  "symbol": "NEE",
  "spread_bps": 3,        // 0.03%
  "depth_level1": 5000,   // Lower liquidity
  "depth_level5": 20000,
  "tick_size": 0.01,
  "update_frequency": "low",  // Fewer updates
  "avg_daily_volume": 3000000,
  "typical_obi_range": [-0.1, 0.1]
}
```

---

## Development Configuration Structure

```yaml
# config/tokens-development.yml
environment: development

metadata:
  total_tokens: 100
  update_rate: 1  # 1 message/sec/token
  total_throughput: 100  # messages/sec
  
categories:
  tech:
    count: 20
    symbols: [AAPL, MSFT, GOOGL, AMZN, META, NVDA, TSLA, NFLX, AMD, INTC, 
              QCOM, AVGO, ADBE, CRM, ORCL, CSCO, IBM, SHOP, UBER, PYPL]
    characteristics:
      spread_bps: 1
      depth_multiplier: 1.0  # Full depth simulation
      update_rate: 1.0       # 1 msg/sec
      volatility: 0.015      # 1.5% daily
      correlation_within: 0.7
  
  crypto:
    count: 15
    symbols: [BTC, ETH, BNB, SOL, XRP, ADA, AVAX, MATIC, DOT, LINK, 
              UNI, ATOM, LTC, BCH, ALGO]
    characteristics:
      spread_bps: 5
      depth_multiplier: 0.5
      update_rate: 1.0
      volatility: 0.035      # 3.5% daily
      correlation_within: 0.8
  
  financials:
    count: 15
    symbols: [JPM, BAC, WFC, C, GS, MS, V, MA, AXP, BLK, 
              SCHW, USB, PNC, TFC, COF]
    characteristics:
      spread_bps: 1
      depth_multiplier: 0.8
      update_rate: 1.0
      volatility: 0.018
      correlation_within: 0.75
  
  energy:
    count: 10
    symbols: [XOM, CVX, COP, SLB, MPC, PSX, VLO, OXY, HAL, BP]
    characteristics:
      spread_bps: 2
      depth_multiplier: 0.6
      update_rate: 1.0
      volatility: 0.020
      correlation_within: 0.85
  
  healthcare:
    count: 10
    symbols: [JNJ, UNH, PFE, ABBV, TMO, ABT, MRK, LLY, BMY, AMGN]
    characteristics:
      spread_bps: 1
      depth_multiplier: 0.7
      update_rate: 1.0
      volatility: 0.012
      correlation_within: 0.6
  
  consumer:
    count: 10
    symbols: [HD, MCD, NKE, SBUX, TGT, LOW, TJX, CMG, BKNG, MAR]
    characteristics:
      spread_bps: 2
      depth_multiplier: 0.6
      update_rate: 1.0
      volatility: 0.016
      correlation_within: 0.65
  
  industrial:
    count: 10
    symbols: [CAT, BA, HON, UPS, DE, GE, MMM, RTX, LMT, NOC]
    characteristics:
      spread_bps: 2
      depth_multiplier: 0.6
      update_rate: 1.0
      volatility: 0.018
      correlation_within: 0.7
  
  utilities:
    count: 10
    symbols: [T, VZ, TMUS, NEE, DUK, SO, D, EXC, AEP, XEL]
    characteristics:
      spread_bps: 3
      depth_multiplier: 0.5
      update_rate: 1.0
      volatility: 0.008
      correlation_within: 0.5

# Strategy-specific token filters
strategy_filters:
  pairs_trading:
    enabled_categories: [tech, financials]
    token_count: 40
    min_correlation: 0.7
  
  ou_mean_reversion:
    enabled_categories: [energy, utilities]
    token_count: 20
    min_reversion_speed: 0.1
  
  kalman_trend:
    enabled_categories: [tech, crypto]
    token_count: 35
    min_volatility: 0.015
  
  obi_market_making:
    top_n_by_volume: 20
    min_liquidity_rank: 20
  
  vpin_toxicity:
    all_tokens: true
    token_count: 100
```

---

## Why This Mix Works

### **1. Diverse Correlations**
- Tech stocks highly correlated (0.7-0.8)
- Tech vs Utilities: low correlation (0.2-0.3)
- Enables pairs trading within sectors
- Avoids portfolio-wide risk

### **2. Multiple Volatility Regimes**
- **High:** Crypto (3.5% daily)
- **Medium:** Tech, Energy (1.5-2.0%)
- **Low:** Utilities (0.8%)
- Tests strategies across different conditions

### **3. Strategy Specialization**
Each strategy gets optimal tokens:
- Pairs trading: Correlated stocks
- Mean reversion: Stable, predictable tokens
- Trend following: High-volatility, momentum-driven
- Market making: Ultra-liquid, tight spreads

### **4. Realistic Microstructure**
- Different spreads (1 bps to 5 bps)
- Different depths (5k to 200k at L1)
- Different update rates
- Simulates real market conditions

### **5. Scalable Design**
- Same configuration structure scales to 30,000 tokens
- Just multiply counts: 20 tech → 6,000 tech
- Proportions remain same
- Easy production deployment

---

## Production Scaling (30,000 Tokens)

When scaling to production, maintain same proportions:

| Category | Dev (100) | Production (30k) | Multiplier |
|----------|-----------|------------------|------------|
| Tech | 20 | 6,000 | 300x |
| Crypto | 15 | 4,500 | 300x |
| Financials | 15 | 4,500 | 300x |
| Energy | 10 | 3,000 | 300x |
| Healthcare | 10 | 3,000 | 300x |
| Consumer | 10 | 3,000 | 300x |
| Industrial | 10 | 3,000 | 300x |
| Utilities | 10 | 3,000 | 300x |
| **TOTAL** | **100** | **30,000** | **300x** |

**Strategy adjustments for scale:**
- Use hierarchical filtering (coarse → fine)
- Token sampling per strategy
- Distributed processing
- (See `02-system-architecture.md` for details)

---

## Next Steps

1. ✅ **Token selection defined** (this document)
2. ⏭️ **Order book data format** - How to represent LOB data
3. ⏭️ **Token configuration loader** - Read YAML, initialize system
4. ⏭️ **Order book generator** - Simulate realistic dynamics

---

## References

- **HFT Literature:** Algorithmic and High-Frequency Trading (Cartea, Jaimungal, Penalva)
- **Order Book Dynamics:** Market Microstructure in Practice (Lehalle, Laruelle)
- **Correlation Data:** Historical stock correlations from financial databases
- **Volatility Estimates:** Realized volatility from historical tick data
