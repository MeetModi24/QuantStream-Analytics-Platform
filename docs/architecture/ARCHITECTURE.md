# QuantStream System Architecture

## Overview

QuantStream is a **real-time trading strategy analytics platform** that combines:
- **Alpha trading strategies** (10 different strategies generating signals)
- **Real-time market visualization** (candlestick charts, live price updates)
- **Strategy performance evaluation** (backtesting, Sharpe ratio, PnL tracking)

**Tech Stack:**
- **Backend:** Spring Boot microservices, Apache Kafka, Kafka Streams, QuestDB
- **Frontend:** React + TypeScript, Lightweight Charts (TradingView), WebSocket
- **Deployment:** 100% free tier (Render, Vercel, Upstash)

---

## System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 1: DATA PIPELINE                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐                                           │
│  │ Data Generator   │  Generates realistic market data          │
│  │ (Spring Boot)    │  - 10 tokens (AAPL, BTC, ETH, etc.)      │
│  │                  │  - 10 ticks/second                        │
│  │                  │  - Geometric Brownian Motion algorithm    │
│  └────────┬─────────┘                                           │
│           ↓ Kafka Producer                                      │
│  ┌──────────────────┐                                           │
│  │ Kafka Topic:     │                                           │
│  │ "market-data"    │  Message queue for price updates          │
│  └────────┬─────────┘                                           │
│           ↓ Kafka Consumer                                      │
│  ┌──────────────────┐                                           │
│  │ Database         │  Persists raw ticks                       │
│  │ Consumer         │  - Batch writes (1000 rows/sec)          │
│  │ (Spring Boot)    │  - Target: QuestDB "ticks" table         │
│  └────────┬─────────┘                                           │
│           ↓                                                      │
│  ┌──────────────────┐                                           │
│  │ QuestDB          │  Time-series storage                      │
│  │ Table: ticks     │  - symbol, price, volume, timestamp      │
│  └──────────────────┘                                           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 2: INTELLIGENCE LAYER                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────────────────────────────────────┐       │
│  │           AGGREGATOR (Kafka Streams)                 │       │
│  │                                                       │       │
│  │  Input: Kafka "market-data" (raw ticks)             │       │
│  │  Processing: Windowed aggregation                    │       │
│  │    - 1-minute tumbling windows                       │       │
│  │    - Calculate OHLC (Open, High, Low, Close)        │       │
│  │    - Accumulate volume                               │       │
│  │  Output: Kafka "candles-1m"                          │       │
│  │                                                       │       │
│  │  Purpose: Create candles for frontend charts        │       │
│  └───────────────────────┬─────────────────────────────┘       │
│                          ↓                                       │
│                 Kafka Topic: "candles-1m"                       │
│                          ↓                                       │
│                          │                                       │
│  ┌───────────────────────┼─────────────────────────────┐       │
│  │                       │  STRATEGY ENGINE             │       │
│  │                       │  (Spring Boot)               │       │
│  │                       │                              │       │
│  │   Contains 10 alpha strategies as @Component:       │       │
│  │   ┌──────────────┐  ┌──────────────┐               │       │
│  │   │ MA Crossover │  │ RSI Mean Rev │               │       │
│  │   │ @Component   │  │ @Component   │               │       │
│  │   └──────────────┘  └──────────────┘               │       │
│  │   ┌──────────────┐  ┌──────────────┐               │       │
│  │   │ MACD         │  │ Bollinger    │               │       │
│  │   │ @Component   │  │ @Component   │  ... (6 more) │       │
│  │   └──────────────┘  └──────────────┘               │       │
│  │                                                      │       │
│  │   Scheduler runs every 60 seconds:                  │       │
│  │   1. Query QuestDB for last N ticks per symbol     │       │
│  │   2. Calculate indicators (MA, RSI, MACD, etc.)    │       │
│  │   3. Detect patterns (crossovers, thresholds)      │       │
│  │   4. Generate BUY/SELL signals                      │       │
│  │   5. Produce to Kafka "trading-signals"            │       │
│  │                                                      │       │
│  │   Input: QuestDB "ticks" table (historical data)   │       │
│  │   Output: Kafka "trading-signals" topic            │       │
│  └───────────────────────┬──────────────────────────────┘       │
│                          ↓                                       │
│                 Kafka Topic: "trading-signals"                  │
│                          ↓                                       │
│  ┌───────────────────────┴──────────────────────────────┐       │
│  │      DATABASE CONSUMER (Extended)                     │       │
│  │                                                        │       │
│  │  Consumes from 3 Kafka topics:                       │       │
│  │  1. "market-data" → writes to "ticks" table          │       │
│  │  2. "candles-1m" → writes to "candles_1m" table      │       │
│  │  3. "trading-signals" → writes to "signals" table    │       │
│  │                                                        │       │
│  │  Batch writes for efficiency                         │       │
│  └────────────────────────┬───────────────────────────────┘       │
│                           ↓                                       │
│  ┌───────────────────────────────────────────────────────┐       │
│  │              QuestDB (Time-Series Storage)             │       │
│  │                                                         │       │
│  │  Tables:                                               │       │
│  │  1. ticks (raw price data)                            │       │
│  │     - symbol, price, volume, timestamp                │       │
│  │     - ~10 rows/sec, 864K rows/day                     │       │
│  │                                                         │       │
│  │  2. candles_1m (1-minute OHLC)                        │       │
│  │     - symbol, open, high, low, close, volume, ts      │       │
│  │     - ~10 rows/min, 14.4K rows/day                    │       │
│  │                                                         │       │
│  │  3. signals (trading signals)                         │       │
│  │     - symbol, action, strategy, confidence, ts        │       │
│  │     - ~20-50 rows/hour (depends on market conditions) │       │
│  └───────────────────────────────────────────────────────┘       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│                    PHASE 3: BACKTESTING ENGINE                 │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌──────────────────────────────────────────────────────┐      │
│  │         Strategy Backtester (Fast Api)               │      │
│  │                                                      │      │
│  │  For each strategy (10 total):                       │      │
│  │  1. Query historical ticks from QuestDB              │      │
│  │  2. Replay strategy logic day-by-day                 │      │
│  │  3. Simulate trades at signal prices                 │      │
│  │  4. Track portfolio value over time                  │      │
│  │  5. Calculate performance metrics:                   │      │
│  │     - Total Return (%)                               │      │
│  │     - Sharpe Ratio                                   │      │
│  │     - Win Rate (%)                                   │      │
│  │     - Max Drawdown (%)                               │      │
│  │     - Average Win/Loss                               │      │
│  │                                                      │      │
│  │  Output: Strategy performance rankings               │      │
│  └──────────────────────────────────────────────────────┘      │
│                                                                │
└────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                 PHASE 4: API GATEWAY & FRONTEND                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────────────────────────────────────┐      │
│  │           API Gateway (Python)                   │      │
│  │                                                        │      │
│  │  REST API Endpoints:                                  │      │
│  │  - GET /api/tokens                                    │      │
│  │      → List all tokens with latest prices            │      │
│  │                                                        │      │
│  │  - GET /api/tokens/{symbol}/candles                   │      │
│  │      → Historical OHLC data for charts               │      │
│  │                                                        │      │
│  │  - GET /api/strategies                                │      │
│  │      → List all strategies with current signals      │      │
│  │                                                        │      │
│  │  - GET /api/strategies/leaderboard                    │      │
│  │      → Strategy performance rankings                 │      │
│  │                                                        │      │
│  │  - GET /api/strategies/{name}/performance             │      │
│  │      → Detailed metrics for specific strategy        │      │
│  │                                                        │      │
│  │  - GET /api/signals/recent                            │      │
│  │      → Latest signals across all strategies          │      │
│  │                                                        │      │
│  │  WebSocket Endpoints:                                 │      │
│  │  - /ws (connection endpoint)                          │      │
│  │  - /topic/prices (real-time price updates)           │      │
│  │  - /topic/signals (real-time signal notifications)   │      │
│  │  - /topic/leaderboard (live strategy rankings)       │      │
│  └────────────────────┬─────────────────────────────────┘      │
│                       ↓ HTTP/WebSocket                          │
│  ┌──────────────────────────────────────────────────────┐      │
│  │           React Frontend (TypeScript)                 │      │
│  │                                                        │      │
│  │  Pages: (see Frontend Design section below)          │      │
│  │  - Live Market Dashboard                              │      │
│  │  - Strategy Leaderboard                               │      │
│  │  - Strategy Deep Dive                                 │      │
│  │                                                        │      │
│  │  Libraries:                                            │      │
│  │  - Lightweight Charts (TradingView candlesticks)     │      │
│  │  - STOMP.js (WebSocket client)                       │      │
│  │  - Tailwind CSS (styling)                            │      │
│  │  - Recharts (performance graphs)                     │      │
│  └──────────────────────────────────────────────────────┘      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Frontend Design (UI Mockups)

### Page 1: Live Market Dashboard

```
╔════════════════════════════════════════════════════════════════╗
║  QuantStream                        🔔 Notifications    👤 User ║
╠════════════════════════════════════════════════════════════════╣
║                                                                 ║
║  📊 Market Overview                              Last Update: 2s║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │ Symbol │ Price    │ Change  │ Volume  │ Active Signals │  ║
║  ├────────┼──────────┼─────────┼─────────┼────────────────┤  ║
║  │ AAPL   │ $180.50  │ +2.3% ↗ │ 45.2M   │ 🟢 BUY (3)     │  ║
║  │ MSFT   │ $412.30  │ +1.8% ↗ │ 28.5M   │ 🟡 HOLD (5)    │  ║
║  │ GOOGL  │ $142.80  │ -0.5% ↘ │ 32.1M   │ 🔴 SELL (2)    │  ║
║  │ TSLA   │ $248.90  │ +4.2% ↗ │ 125.8M  │ 🟢 BUY (4)     │  ║
║  │ AMZN   │ $178.45  │ -1.1% ↘ │ 52.3M   │ 🟡 HOLD (3)    │  ║
║  │ BTC    │ $50,123  │ -2.4% ↘ │ 12.8B   │ 🔴 SELL (2)    │  ║
║  │ ETH    │ $2,845   │ +5.1% ↗ │ 8.4B    │ 🟢 BUY (5)     │  ║
║  │ SOL    │ $145.20  │ +8.3% ↗ │ 2.1B    │ 🟢 BUY (4)     │  ║
║  │ AVAX   │ $38.50   │ +3.2% ↗ │ 845M    │ 🟢 BUY (3)     │  ║
║  │ MATIC  │ $0.88    │ -4.5% ↘ │ 1.2B    │ 🔴 SELL (1)    │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║                                                                 ║
║  📈 Price Chart                        [AAPL Selected]          ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │                    AAPL - 1 Minute Chart                 │  ║
║  │  185 ┤        ╱╲         ╱╲                             │  ║
║  │      │       ╱  ╲       ╱  ╲    ↑BUY                    │  ║
║  │  180 ┤──────╯    ╲─────╯    ╲  ╱                        │  ║
║  │      │            ╲          ╲╱    ↓SELL                │  ║
║  │  175 ┤             ╲─────────╲                          │  ║
║  │      │                        ╲                          │  ║
║  │  170 ┤                         ╲─────────                │  ║
║  │      └──────────────────────────────────────────────→   │  ║
║  │     10:00   10:15   10:30   10:45   11:00   11:15      │  ║
║  │                                                          │  ║
║  │  Indicators: ━━ MA(10)  ━━ MA(50)  🔵 BUY  🔴 SELL    │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║                                                                 ║
║  🎯 Recent Signals (All Strategies)                            ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │ Time  │ Symbol│Strategy       │Action│Price   │Conf.   │  ║
║  ├───────┼───────┼───────────────┼──────┼────────┼────────┤  ║
║  │ 11:15 │ AAPL  │ MA Crossover  │ BUY  │ $180.5 │ 85%    │  ║
║  │ 11:14 │ ETH   │ RSI Mean Rev  │ BUY  │ $2,845 │ 92%    │  ║
║  │ 11:13 │ BTC   │ MACD          │ SELL │ $50.1K │ 78%    │  ║
║  │ 11:12 │ SOL   │ Bollinger     │ BUY  │ $145.2 │ 81%    │  ║
║  │ 11:11 │ TSLA  │ Stochastic    │ BUY  │ $248.9 │ 74%    │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║                                                                 ║
╚════════════════════════════════════════════════════════════════╝
```

---

### Page 2: Strategy Leaderboard

```
╔════════════════════════════════════════════════════════════════╗
║  QuantStream › Strategy Leaderboard                    👤 User  ║
╠════════════════════════════════════════════════════════════════╣
║                                                                 ║
║  🏆 Strategy Performance Rankings (Last 30 Days)               ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │Rank│ Strategy          │ Return │Sharpe│Win Rate│Signals│  ║
║  ├────┼───────────────────┼────────┼──────┼────────┼───────┤  ║
║  │ 1🥇│ RSI Mean Rev      │ +32.5% │ 1.85 │  68%   │  487  │  ║
║  │ 2🥈│ MA Crossover      │ +28.3% │ 1.45 │  62%   │  342  │  ║
║  │ 3🥉│ Bollinger Bands   │ +18.1% │ 1.12 │  58%   │  521  │  ║
║  │ 4  │ MACD Momentum     │ +12.7% │ 0.89 │  54%   │  398  │  ║
║  │ 5  │ Stochastic        │ +8.2%  │ 0.65 │  51%   │  445  │  ║
║  │ 6  │ Williams %R       │ +3.1%  │ 0.42 │  49%   │  412  │  ║
║  │ 7  │ VWAP Deviation    │ -2.4%  │-0.15 │  47%   │  289  │  ║
║  │ 8  │ Donchian Channel  │ -5.8%  │-0.34 │  45%   │  234  │  ║
║  │ 9  │ ADX Trend         │ -8.1%  │-0.56 │  43%   │  198  │  ║
║  │ 10 │ ROC Momentum      │-12.3%  │-0.78 │  41%   │  256  │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║                                                                 ║
║  📊 Cumulative Returns (Top 3 Strategies)                      ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │ $14K ┤         ━━━ RSI Mean Rev                         │  ║
║  │      │        ╱                                          │  ║
║  │ $13K ┤       ╱                                           │  ║
║  │      │      ╱    ━━━ MA Crossover                       │  ║
║  │ $12K ┤     ╱    ╱                                        │  ║
║  │      │    ╱    ╱                                         │  ║
║  │ $11K ┤   ╱    ╱  ━━━ Bollinger Bands                    │  ║
║  │      │  ╱    ╱  ╱                                        │  ║
║  │ $10K ┼─╯────╯──╯  (Starting Capital)                    │  ║
║  │      └─────────────────────────────────────────────→    │  ║
║  │      Day 1    Day 10    Day 20    Day 30               │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║                                                                 ║
║  📈 Performance Heatmap (By Token)                             ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │          │ AAPL│MSFT│GOOGL│TSLA│AMZN│BTC│ETH│SOL│AVAX│  ║
║  ├──────────┼─────┼────┼─────┼────┼────┼───┼───┼───┼────┤  ║
║  │ RSI      │ 🟩  │ 🟩 │ 🟨  │ 🟩 │ 🟨 │🟨 │🟩 │🟩 │ 🟨 │  ║
║  │ MA Cross │ 🟩  │ 🟩 │ 🟩  │ 🟨 │ 🟩 │🟨 │🟩 │🟩 │ 🟨 │  ║
║  │ Bollinger│ 🟩  │ 🟨 │ 🟨  │ 🟩 │ 🟨 │🟩 │🟩 │🟨 │ 🟨 │  ║
║  │ MACD     │ 🟨  │ 🟩 │ 🟨  │ 🟩 │ 🟨 │🟨 │🟩 │🟨 │ 🟥 │  ║
║  │          │ Legend: 🟩 Profit  🟨 Breakeven  🟥 Loss   │  ║
║  └─────────────────────────────────────────────────────────┘  ║
║                                                                 ║
║  💡 Click any strategy for detailed analysis                   ║
║                                                                 ║
╚════════════════════════════════════════════════════════════════╝
```

---

### Page 3: Strategy Deep Dive

```
╔════════════════════════════════════════════════════════════════╗
║  QuantStream › RSI Mean Reversion Strategy           👤 User   ║
╠════════════════════════════════════════════════════════════════╣
║                                                                 ║
║  📊 Strategy Overview                                          ║
║  ┌────────────────────────────────────────────────────────┐   ║
║  │  Strategy Type: Mean Reversion                         │   ║
║  │  Indicator: RSI (Relative Strength Index)              │   ║
║  │  Parameters: Period=14, Oversold<30, Overbought>70     │   ║
║  │  Active Symbols: 10 (AAPL, MSFT, GOOGL, TSLA, AMZN,   │   ║
║  │                     BTC, ETH, SOL, AVAX, MATIC)        │   ║
║  └────────────────────────────────────────────────────────┘   ║
║                                                                 ║
║  🎯 Performance Metrics (30-Day Backtest)                      ║
║  ┌──────────────────────┬──────────────────────┐              ║
║  │ Total Return         │ +32.5%               │              ║
║  │ Sharpe Ratio         │ 1.85 ⭐⭐⭐⭐⭐      │              ║
║  │ Win Rate             │ 68% (331/487)        │              ║
║  │ Average Win          │ +2.3%                │              ║
║  │ Average Loss         │ -1.1%                │              ║
║  │ Max Drawdown         │ -8.4%                │              ║
║  │ Total Signals        │ 487                  │              ║
║  │ Profitable Trades    │ 331                  │              ║
║  │ Losing Trades        │ 156                  │              ║
║  │ Risk/Reward Ratio    │ 2.09:1               │              ║
║  └──────────────────────┴──────────────────────┘              ║
║                                                                 ║
║  📈 Equity Curve                                               ║
║  ┌────────────────────────────────────────────────────────┐   ║
║  │ $13.2K ┤                          ╱──────               │   ║
║  │        │                        ╱╱                      │   ║
║  │ $12.5K ┤                     ╱─╯                        │   ║
║  │        │                  ╱─╯                           │   ║
║  │ $11.5K ┤              ╱──╯                              │   ║
║  │        │          ╱──╯                                  │   ║
║  │ $10.0K ┼─────────╯  (Starting Capital)                 │   ║
║  │        └───────────────────────────────────────────→   │   ║
║  │        Day 0    Day 10    Day 20    Day 30            │   ║
║  └────────────────────────────────────────────────────────┘   ║
║                                                                 ║
║  🔍 Recent Signals (Last 10)                                   ║
║  ┌────────────────────────────────────────────────────────┐   ║
║  │ Time  │Symbol│Action│Price   │RSI │Outcome│P/L    │   │   ║
║  ├───────┼──────┼──────┼────────┼────┼───────┼───────┤   │   ║
║  │ 11:15 │ ETH  │ BUY  │ $2,845 │ 28 │ ⏳ Open│ -     │   │   ║
║  │ 10:45 │ AAPL │ SELL │ $180.5 │ 72 │ ✅ Win │ +2.1% │   │   ║
║  │ 10:30 │ BTC  │ BUY  │ $50.1K │ 27 │ ✅ Win │ +1.8% │   │   ║
║  │ 10:15 │ SOL  │ BUY  │ $145.2 │ 25 │ ❌ Loss│ -0.9% │   │   ║
║  │ 10:00 │ TSLA │ SELL │ $248.9 │ 74 │ ✅ Win │ +3.2% │   │   ║
║  │ 09:45 │ MSFT │ BUY  │ $412.3 │ 29 │ ✅ Win │ +1.5% │   │   ║
║  │ 09:30 │ AVAX │ BUY  │ $38.50 │ 24 │ ❌ Loss│ -1.2% │   │   ║
║  │ 09:15 │ GOOGL│ SELL │ $142.8 │ 71 │ ✅ Win │ +2.4% │   │   ║
║  │ 09:00 │ AMZN │ BUY  │ $178.5 │ 26 │ ✅ Win │ +1.9% │   │   ║
║  │ 08:45 │ MATIC│ SELL │ $0.88  │ 73 │ ❌ Loss│ -1.5% │   │   ║
║  └────────────────────────────────────────────────────────┘   ║
║                                                                 ║
║  📚 Strategy Logic                                             ║
║  ┌────────────────────────────────────────────────────────┐   ║
║  │  BUY Signal:                                            │   ║
║  │   • RSI < 30 (oversold condition)                      │   ║
║  │   • Confidence = (30 - RSI) / 30                       │   ║
║  │   • Example: RSI = 25 → Confidence = 16.7%            │   ║
║  │                                                         │   ║
║  │  SELL Signal:                                           │   ║
║  │   • RSI > 70 (overbought condition)                    │   ║
║  │   • Confidence = (RSI - 70) / 30                       │   ║
║  │   • Example: RSI = 75 → Confidence = 16.7%            │   ║
║  │                                                         │   ║
║  │  Historical Data Required: 14 days                     │   ║
║  └────────────────────────────────────────────────────────┘   ║
║                                                                 ║
╚════════════════════════════════════════════════════════════════╝
```

---

## Services (Detailed)

### 1. Market Data Generator (Phase 1)
**Technology:** Java 21 + Spring Boot  
**Purpose:** Generate realistic price data  
**Algorithm:** Geometric Brownian Motion  
**Output:** Kafka topic `market-data`  
**Rate:** 10 messages/second (10 tokens × 1 update/sec)  
**Deployment:** Render free tier (512 MB)

### 2. Database Consumer (Phase 1, Extended Phase 2)
**Technology:** Java 21 + Spring Boot  
**Purpose:** Persist all data to QuestDB  
**Inputs:**
- Kafka topic `market-data` → writes to `ticks` table
- Kafka topic `candles-1m` (Phase 2) → writes to `candles_1m` table
- Kafka topic `trading-signals` (Phase 2) → writes to `signals` table

**Deployment:** Render free tier (512 MB)

### 3. Aggregator (Phase 2 - NEW)
**Technology:** Java 21 + Spring Boot + Kafka Streams  
**Purpose:** Create OHLC candles for frontend visualization  
**Processing:**
- 1-minute tumbling windows
- Calculate Open, High, Low, Close, Volume
- Stateful aggregation with changelog topic

**Input:** Kafka topic `market-data` (raw ticks)  
**Output:** Kafka topic `candles-1m` (OHLC candles)  
**Deployment:** Render free tier (512 MB)

### 4. Strategy Engine (Phase 2 - NEW)
**Technology:** Java 21 + Spring Boot  
**Purpose:** Generate trading signals from 10 alpha strategies  
**Architecture:** Single service, 10 strategies as `@Component` classes  
**Strategies:**
1. MA Crossover (trend following)
2. RSI Mean Reversion (mean reversion)
3. MACD Momentum (trend + momentum)
4. Bollinger Bands (volatility)
5. Stochastic Oscillator (momentum)
6. Williams %R (momentum)
7. ADX Trend Strength (trend)
8. Donchian Channel (breakout)
9. ROC (momentum)
10. VWAP Deviation (volume-based)

**Execution:** Scheduler runs every 60 seconds  
**Input:** QuestDB `ticks` table (historical queries)  
**Output:** Kafka topic `trading-signals`  
**Deployment:** Render free tier (512 MB)

### 5. Backtester (Phase 3)
**Technology:** Java 21 + Spring Boot  
**Purpose:** Evaluate strategy performance on historical data  
**Process:**
- Query historical ticks from QuestDB
- Replay each strategy day-by-day
- Simulate trades at signal prices
- Calculate Sharpe ratio, win rate, PnL, drawdown

**Output:** Performance metrics stored in QuestDB  
**Deployment:** Render free tier (512 MB)

### 6. API Gateway (Phase 4)
**Technology:** Java 21 + Spring Boot + WebSocket (STOMP)  
**Purpose:** Expose REST API and WebSocket for frontend  
**REST Endpoints:**
- `GET /api/tokens` - List all tokens
- `GET /api/tokens/{symbol}/candles` - Historical OHLC
- `GET /api/strategies/leaderboard` - Strategy rankings
- `GET /api/strategies/{name}/performance` - Detailed metrics
- `GET /api/signals/recent` - Latest signals

**WebSocket Topics:**
- `/topic/prices` - Real-time price updates
- `/topic/signals` - Real-time signal notifications
- `/topic/leaderboard` - Live strategy rankings

**Deployment:** Render free tier (512 MB)

### 7. Frontend Dashboard (Phase 4)
**Technology:** React 18 + TypeScript + Vite  
**Libraries:**
- Lightweight Charts (TradingView candlesticks)
- STOMP.js (WebSocket client)
- Tailwind CSS (styling)
- Recharts (performance graphs)

**Deployment:** Vercel free tier (unlimited bandwidth)

---

## Data Flow Example

### Example: BTC Price Update → Chart Display

**1. Generation (t=0ms)**
```java
Tick tick = new Tick("BTC", 50000.00, 1000, Instant.now());
kafkaTemplate.send("market-data", "BTC", tick);
```

**2. Kafka (t=5ms)**
```
Topic: market-data
Message: {"symbol":"BTC","price":50000.00,"volume":1000,"timestamp":"..."}
```

**3a. Database Consumer (t=10ms)**
```sql
INSERT INTO ticks VALUES ('BTC', 50000.00, 1000, '2026-07-17T11:00:00Z');
```

**3b. Aggregator (Kafka Streams) (t=10ms)**
```java
// Updates 1-minute window state
currentCandle.updateHigh(50000.00);
currentCandle.updateLow(50000.00);
currentCandle.setClose(50000.00);
```

**3c. Strategy Engine (t=60000ms - every minute)**
```java
List<Double> prices = jdbcTemplate.query("SELECT price FROM ticks WHERE symbol='BTC' LIMIT 14");
double rsi = calculateRSI(prices);
if (rsi < 30) {
    Signal signal = new Signal("BTC", "BUY", "RSI", 0.85, Instant.now());
    kafkaTemplate.send("trading-signals", signal);
}
```

**4. Window Closes (t=60000ms)**
```java
// Aggregator emits completed candle
OHLCCandle candle = new OHLCCandle("BTC", 50000, 50100, 49900, 50050, 60000, ts);
kafkaTemplate.send("candles-1m", candle);
```

**5. WebSocket Push (t=60010ms)**
```java
simpMessagingTemplate.convertAndSend("/topic/prices", candle);
```

**6. Frontend Update (t=60020ms)**
```typescript
stompClient.subscribe('/topic/prices', (message) => {
    const candle = JSON.parse(message.body);
    chart.update(candle);  // Adds new candle to chart
});
```

**Total Latency:** ~100ms (generation → display)

---

## Deployment Architecture (100% Free Tier)

### Development (Local)
```
Docker Compose:
├── Kafka (port 9092)
├── Zookeeper (port 2181)
└── QuestDB (port 9000, 8812)

Services (run locally):
├── data-generator (port 8081)
├── database-consumer (port 8082)
├── aggregator (port 8083)
├── strategy-engine (port 8084)
├── backtester (port 8085)
└── api-gateway (port 8086)

Frontend:
└── React dev server (port 5173)
```

### Production (Free Tier)

**Backend Services → Render.com (Free Tier)**
- 5 services × 512 MB = 2.5 GB total
- Free tier: 750 hours/month per service (enough for 24/7)
- Sleep after 15 min inactivity (acceptable for demo)

**Services:**
1. data-generator.onrender.com
2. database-consumer.onrender.com
3. aggregator.onrender.com
4. strategy-engine.onrender.com
5. api-gateway.onrender.com (public-facing)

**Kafka → Upstash (Free Tier)**
- 10 GB traffic/month
- 10k messages/day = ~300k/month (within limit)
- Serverless (pay-per-use after free tier)
- WebSocket support

**QuestDB → Render.com (Free Tier Disk)**
- 1 GB persistent disk (free)
- Storage: ~500 MB for 30 days data
- Alternative: QuestDB Cloud free tier (1 GB)

**Frontend → Vercel (Free Tier)**
- Unlimited bandwidth
- Global CDN
- Automatic HTTPS
- quantstream.vercel.app

**Architecture:**
```
Internet
   ↓
Vercel (Frontend) → api-gateway.onrender.com (Backend)
                           ↓
                    Upstash Kafka
                           ↓
          ┌─────────────┬──┴────┬─────────────┐
          ↓             ↓       ↓             ↓
    data-generator  aggregator  strategy-engine  db-consumer
    (Render)        (Render)    (Render)         (Render)
                                                    ↓
                                                QuestDB
                                                (Render Disk)
```

**Total Cost:** $0/month (100% free tier)

**Limitations:**
- Services sleep after 15 min inactivity (first request takes ~30s to wake)
- 512 MB RAM per service (sufficient for our workload)
- 1 GB storage (30 days of data, then rotate)
- 10k messages/day Kafka limit (we generate ~864k ticks/day) ❌

**Reality Check:** Kafka free tier is **NOT sufficient** for 10 ticks/sec continuous.

**Solutions:**
1. **Reduce tick rate:** 1 tick/10 seconds instead of 10/second (9,600 ticks/day) ✅
2. **Reduce symbols:** 5 tokens instead of 10 (4,320 ticks/day) ✅
3. **Paid Kafka:** Upstash pay-as-you-go (~$5-10/month for our volume)
4. **Self-hosted Kafka:** Free but complex deployment

**Recommended for free deployment:**
- Reduce to 5 tokens
- 1 tick every 5 seconds per token
- Total: 8,640 ticks/day (within free tier)
- Still enough for demo/portfolio

---

## Storage Estimates

### 30-Day Storage (Free Tier Optimized)

**Ticks Table:**
- 5 tokens × 1 tick/5s = 1 tick/sec
- 86,400 ticks/day × 30 days = 2.6M rows
- ~50 bytes/row = 130 MB

**Candles Table:**
- 5 tokens × 1 candle/min = 5 candles/min
- 7,200 candles/day × 30 days = 216K rows
- ~80 bytes/row = 17 MB

**Signals Table:**
- ~50 signals/day × 30 days = 1,500 rows
- ~100 bytes/row = 150 KB

**Total:** 130 + 17 + 0.15 = **~150 MB** (well within 1 GB free tier)

---

## Key Design Decisions

### Why Kafka?
- Decouples services (each independent)
- Handles high throughput (1M+ msg/sec capability)
- Durable (messages persisted, can replay)
- Scalable (add partitions and consumers)

### Why Kafka Streams for Aggregator?
- Stateful processing (maintains windowed state)
- Fault-tolerant (state backed up to Kafka)
- Exactly-once semantics (no duplicate candles)
- Embedded (runs in Spring Boot, no separate deployment)

### Why QuestDB?
- Fast time-series ingestion (1.6M rows/sec)
- SQL interface (familiar query language)
- Columnar storage (10x better compression)
- Time-based partitioning (fast time-range queries)

### Why Separate Strategy Engine + Aggregator?
**Aggregator:**
- Purpose: Create candles for **frontend visualization**
- Consumes: Live Kafka stream
- Processing: Stateful windowing (Kafka Streams)

**Strategy Engine:**
- Purpose: Generate **alpha trading signals**
- Consumes: QuestDB historical data (queries)
- Processing: Complex indicator calculations

**Why not combined?**
- Different responsibilities (candles vs signals)
- Different data sources (stream vs database)
- Different scaling profiles (stateful vs stateless)
- Aggregator fails → frontend charts break, strategies continue
- Strategies fail → signals stop, frontend candles continue

### Why Single Strategy Engine (Not 10 Services)?
- Strategies are algorithms, not features (same resource profile)
- Free tier constraints (10 services = impossible)
- Zero code duplication (shared models, utils)
- Easier development and debugging
- Still modular (interface-based, can extract later)

---

## Technology Stack Summary

### Backend
- **Java 21** (LTS)
- **Spring Boot 4.0.7**
- **Apache Kafka 3.8+**
- **Kafka Streams 3.8+**
- **QuestDB 8.x**
- **Maven** (build tool)

### Frontend
- **React 18**
- **TypeScript 5**
- **Lightweight Charts** (TradingView)
- **STOMP.js** (WebSocket)
- **Tailwind CSS**
- **Vite** (build tool)

### Infrastructure
- **Docker Compose** (local dev)
- **Render.com** (backend deployment)
- **Vercel** (frontend deployment)
- **Upstash** (managed Kafka)

---

## Next Steps

1. ✅ **Phase 1 Complete:** Data pipeline (generator → Kafka → consumer → QuestDB)
2. 🚧 **Phase 2 In Progress:** Build aggregator + strategy engine + extend consumer
3. ⏳ **Phase 3:** Build backtester
4. ⏳ **Phase 4:** Build API gateway + React frontend
5. ⏳ **Phase 5:** Deploy to free tier (Render + Vercel + Upstash)

---

## Questions?

See `docs/QUESTIONS.md` for common questions and troubleshooting.
