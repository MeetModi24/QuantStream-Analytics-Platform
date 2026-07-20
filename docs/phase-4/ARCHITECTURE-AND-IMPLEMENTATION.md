# Phase 4: Architecture & Implementation Guide

**Last Updated:** 2026-07-20  
**Status:** Ready for Implementation

---

## Table of Contents

1. [Architecture Decision](#architecture-decision)
2. [System Design](#system-design)
3. [API Endpoints](#api-endpoints)
4. [Implementation Details](#implementation-details)
5. [Frontend Stack](#frontend-stack)
6. [Deployment Strategy](#deployment-strategy)

---

## Architecture Decision

### Design Pattern: Backend for Frontend (BFF)

**What We're Building:**
- Extend existing API Service (port 8085) to handle all frontend requests
- Keep existing 4 microservices for data processing (8081-8084)
- Frontend talks to ONE service only (8085)

### Why This Pattern?

**✅ Strengths:**
1. **Simple:** Frontend knows one URL: `http://localhost:8085`
2. **Fast:** Direct database queries, no routing overhead
3. **Still Microservices:** Data pipeline is event-driven microservices
4. **Free Deployment:** Fits in free tier (5 services total)
5. **Production-Ready:** Used by Spotify, SoundCloud, Netflix (for client APIs)

**⚠️ Trade-offs:**
1. API Service handles multiple concerns (backtest + market data + strategies)
2. Less independent scaling (but not needed for this scale)
3. Shared database access (read-only, acceptable for CQRS pattern)

### Alternative: API Gateway Pattern (Not Chosen)

```
Frontend → API Gateway (8086) → Market Service (8087)
                               → Strategy Service (8088)  
                               → Backtest Service (8085)
```

**Why NOT chosen:**
- ❌ One more service (6 services vs 5)
- ❌ More network hops (latency)
- ❌ More complex for learning project
- ❌ Overkill for current scale
- ✅ Good for: Large teams, independent scaling needs, 100+ microservices

**When to migrate:** If API Service exceeds 2GB RAM or needs independent scaling

---

## System Design

### Complete Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      FRONTEND (React)                         │
│                 http://localhost:5173                         │
│                                                               │
│  Pages: Market Dashboard, Leaderboard, Strategy Detail       │
│  Libraries: TradingView Lightweight Charts, Tremor, Shadcn  │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         │ HTTP REST + WebSocket
                         │ ONE API endpoint
                         ↓
┌──────────────────────────────────────────────────────────────┐
│              API SERVICE (Port 8085)                          │
│              Backend for Frontend                             │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  Existing (Phase 3):                                          │
│  • 12 Backtest endpoints ✅                                  │
│  • Async task manager ✅                                     │
│  • Strategy registry ✅                                      │
│                                                               │
│  NEW (Phase 4):                                               │
│  • 15 Market/Strategy/Signal endpoints                       │
│  • QuestDB connection (psycopg2)                             │
│  • Kafka consumers (3 topics)                                │
│  • WebSocket handler (STOMP)                                 │
│  • HTTP clients (optional, for service status)              │
└───────┬──────────────────┬───────────────────────────────────┘
        │                  │
        ↓                  ↓
┌──────────────┐    ┌──────────────────────────────────────┐
│   QuestDB    │    │  Kafka Broker (Port 9092)            │
│   Port 8812  │    │                                      │
│              │    │  Topics:                             │
│  Tables:     │    │  • market-data (ticks)               │
│  • ticks     │    │  • candles-1m (OHLC)                 │
│  • candles_1m│    │  • trading-signals (BUY/SELL)        │
│  • signals   │    └──────────────────────────────────────┘
└──────────────┘

┌──────────────────────────────────────────────────────────────┐
│         DATA PROCESSING MICROSERVICES (Event-Driven)          │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  Service 1: Data Generator (8081)                            │
│  • Generates market ticks (GBM algorithm)                    │
│  • Produces to Kafka: market-data                            │
│  • Tech: Java Spring Boot                                    │
│                                                               │
│  Service 2: Database Consumer (8082)                         │
│  • Consumes from Kafka: market-data, candles-1m, signals    │
│  • Writes to QuestDB (batch writes)                          │
│  • Tech: Java Spring Boot                                    │
│                                                               │
│  Service 3: Aggregator (8083)                                │
│  • Kafka Streams: 1-minute tumbling windows                  │
│  • Creates OHLC candles from ticks                           │
│  • Produces to Kafka: candles-1m                             │
│  • Tech: Java Spring Boot + Kafka Streams                    │
│                                                               │
│  Service 4: Strategy Engine (8084)                           │
│  • 10 trading strategies (@Component)                        │
│  • Scheduled: every 60 seconds                               │
│  • Queries QuestDB for historical data                       │
│  • Produces to Kafka: trading-signals                        │
│  • Tech: Java Spring Boot                                    │
│                                                               │
│  Communication: Kafka (async, event-driven)                  │
│  Pattern: Producer-Consumer, Pub-Sub                         │
└──────────────────────────────────────────────────────────────┘
```

### Data Flow Patterns

#### Pattern 1: Historical Data Queries (Most Endpoints)

```
User clicks "Market Dashboard"
    ↓
Frontend: GET /api/v1/tokens
    ↓
API Service (8085): 
    SELECT DISTINCT ON (symbol) symbol, price, volume
    FROM ticks
    ORDER BY symbol, timestamp DESC
    ↓
QuestDB returns data
    ↓
API Service: Format and return JSON
    ↓
Frontend: Display in table
```

**Why Direct DB Access?**
- All data already in QuestDB (written by DB Consumer 8082)
- Fast (no service hops)
- Read-only (no coupling issues)
- This is CQRS pattern (Command Query Responsibility Segregation)

#### Pattern 2: Real-time Updates (WebSocket)

```
User opens dashboard
    ↓
Frontend: WebSocket connect ws://localhost:8085/ws
    ↓
Frontend: Subscribe to /topic/prices
    ↓
[Background: API Service Kafka Consumer]
    Data Generator (8081) → Kafka (market-data)
    ↓
    API Service (8085) Kafka Consumer receives message
    ↓
    Push to all WebSocket clients
    ↓
Frontend: Real-time price update appears
```

#### Pattern 3: Service Status (Optional)

```
Frontend: GET /api/v1/services/health
    ↓
API Service (8085):
    • Query QuestDB (is it alive?)
    • Check Kafka connection
    • Optional: HTTP GET http://localhost:8084/actuator/health
    ↓
Return aggregated health status
```

---

## API Endpoints

### Service: API Service (Port 8085)

**Base URL:** `http://localhost:8085/api/v1`

#### Market Data Endpoints (5)

**1. List All Tokens**
```
GET /tokens

Response:
{
  "tokens": [
    {
      "symbol": "AAPL",
      "current_price": 180.50,
      "change_24h_pct": 2.3,
      "volume_24h": 45200000,
      "last_updated": "2026-07-20T11:15:30Z",
      "active_signals": {"buy": 3, "sell": 0, "hold": 7}
    }
  ],
  "total": 10
}
```

**2. Get Token Details**
```
GET /tokens/{symbol}

Response:
{
  "symbol": "AAPL",
  "current_price": 180.50,
  "change_24h_pct": 2.3,
  "high_24h": 182.30,
  "low_24h": 177.20,
  "volume_24h": 45200000
}
```

**3. Get Historical Candles**
```
GET /tokens/{symbol}/candles?interval=1m&start_date=...&end_date=...&limit=1000

Response:
{
  "symbol": "AAPL",
  "interval": "1m",
  "candles": [
    {
      "timestamp": "2026-07-20T11:00:00Z",
      "open": 180.20,
      "high": 180.60,
      "low": 180.10,
      "close": 180.50,
      "volume": 125000
    }
  ]
}
```

**4. Get Latest Tick**
```
GET /tokens/{symbol}/tick

Response:
{
  "symbol": "AAPL",
  "price": 180.50,
  "volume": 1000,
  "timestamp": "2026-07-20T11:15:30.458Z"
}
```

**5. Get Market Overview**
```
GET /market/overview

Response:
{
  "summary": {
    "total_volume_24h": 250000000,
    "gainers": 6,
    "losers": 4,
    "most_active": "TSLA"
  }
}
```

#### Strategy Endpoints (5)

**6. List All Strategies**
```
GET /strategies

Response:
{
  "strategies": [
    {
      "name": "RSI",
      "display_name": "RSI Mean Reversion",
      "type": "mean_reversion",
      "description": "Buy when RSI < 30, Sell when RSI > 70",
      "parameters": {"period": 14, "oversold": 30, "overbought": 70},
      "active": true
    }
  ]
}
```

**7. Get Strategy Details**
```
GET /strategies/{name}

Response:
{
  "name": "RSI",
  "display_name": "RSI Mean Reversion",
  "statistics": {
    "total_signals_24h": 45,
    "buy_signals_24h": 18,
    "avg_confidence": 0.78
  }
}
```

**8. Get Strategy Leaderboard**
```
GET /strategies/leaderboard?period=30d&metric=sharpe

Response:
{
  "leaderboard": [
    {
      "rank": 1,
      "strategy_name": "RSI",
      "total_return_pct": 32.5,
      "sharpe_ratio": 1.85,
      "win_rate_pct": 68.0,
      "total_signals": 487
    }
  ],
  "period": "30d"
}
```

**9. Get Strategy Performance**
```
GET /strategies/{name}/performance?period=30d&include_equity_curve=false

Response:
{
  "strategy_name": "RSI",
  "period": "30d",
  "metrics": {
    "total_return_pct": 32.5,
    "sharpe_ratio": 1.85,
    "win_rate_pct": 68.0,
    "max_drawdown_pct": -8.4
  },
  "equity_curve": [...],  // if include_equity_curve=true
  "by_symbol": {...}
}
```

**10. Get Strategy Signals**
```
GET /strategies/{name}/signals?symbol=AAPL&limit=100

Response:
{
  "strategy_name": "RSI",
  "signals": [
    {
      "timestamp": "2026-07-20T11:15:00Z",
      "symbol": "AAPL",
      "action": "BUY",
      "price": 180.50,
      "confidence": 0.85
    }
  ]
}
```

#### Signal Endpoints (3)

**11. Get Recent Signals (All Strategies)**
```
GET /signals/recent?limit=50

Response:
{
  "signals": [
    {
      "timestamp": "2026-07-20T11:15:00Z",
      "symbol": "AAPL",
      "strategy_name": "RSI",
      "action": "BUY",
      "price": 180.50,
      "confidence": 0.85
    }
  ]
}
```

**12. Get Signals by Symbol**
```
GET /signals/symbol/{symbol}?start_date=...&limit=100

Response: Same format as #11
```

**13. Get Signal Statistics**
```
GET /signals/statistics?period=24h

Response:
{
  "period": "24h",
  "total_signals": 487,
  "by_action": {"BUY": 182, "SELL": 145, "HOLD": 160},
  "by_strategy": {"RSI": 52, "MACD": 48},
  "avg_confidence": 0.78
}
```

#### Health Endpoints (2)

**14. System Health**
```
GET /health

Response:
{
  "status": "healthy",
  "timestamp": "2026-07-20T11:15:30Z",
  "services": {
    "questdb": "healthy",
    "kafka": "healthy"
  }
}
```

**15. Service Status**
```
GET /services/{service_name}/status

Response:
{
  "service": "data_generator",
  "status": "healthy",
  "uptime_seconds": 3600
}
```

#### Backtest Endpoints (12 - Already Exist from Phase 3)

```
POST   /backtest/run
POST   /backtest/batch
GET    /backtest/status/{id}
DELETE /backtest/{id}
GET    /backtest/recent
GET    /backtest/strategies
GET    /backtest/results/{id}
GET    /backtest/{id}/summary
GET    /backtest/{id}/equity-curve
POST   /backtest/compare
```

#### WebSocket Topics (5)

**Connection:** `ws://localhost:8085/ws`  
**Protocol:** STOMP

**1. Real-time Prices**
```
Subscribe: /topic/prices

Message:
{
  "type": "PRICE_UPDATE",
  "symbol": "AAPL",
  "price": 180.50,
  "volume": 1000,
  "timestamp": "2026-07-20T11:15:30.458Z"
}
```

**2. Real-time Signals**
```
Subscribe: /topic/signals

Message:
{
  "type": "SIGNAL",
  "symbol": "AAPL",
  "strategy_name": "RSI",
  "action": "BUY",
  "price": 180.50,
  "confidence": 0.85
}
```

**3. Leaderboard Updates**
```
Subscribe: /topic/leaderboard

Message:
{
  "type": "LEADERBOARD_UPDATE",
  "rankings": [...]
}
```

**4. Symbol-Specific Updates**
```
Subscribe: /topic/symbol/{symbol}

Message:
{
  "type": "SYMBOL_UPDATE",
  "symbol": "AAPL",
  "price": 180.50,
  "latest_signals": [...]
}
```

**5. Candle Updates**
```
Subscribe: /topic/candles/{symbol}

Message:
{
  "type": "CANDLE",
  "symbol": "AAPL",
  "open": 180.20,
  "high": 180.60,
  "low": 180.10,
  "close": 180.50,
  "volume": 125000
}
```

**Total Endpoints:** 15 REST + 12 Backtest + 5 WebSocket = **32 endpoints**

---

## Implementation Details

### Technology Stack

**API Service (8085) - Python:**
- FastAPI 0.115+
- Uvicorn (ASGI server)
- psycopg2 (QuestDB connection)
- kafka-python (Kafka consumers)
- python-socketio (WebSocket - STOMP)
- httpx (optional HTTP client)

**Data Services (8081-8084) - Java:**
- Spring Boot 3.x
- Kafka Client 3.8+
- Kafka Streams 3.8+ (Aggregator only)
- JDBC (QuestDB connection)

### Directory Structure

```
QuantStream/
├── api-service/              # Renamed from backtester/
│   ├── app/
│   │   ├── main.py
│   │   ├── config.py
│   │   ├── api/
│   │   │   ├── backtest.py       # Existing ✅
│   │   │   ├── market.py         # NEW
│   │   │   ├── strategies.py     # NEW
│   │   │   └── signals.py        # NEW
│   │   ├── core/
│   │   │   ├── task_manager.py   # Existing ✅
│   │   │   └── strategy_registry.py  # Existing ✅
│   │   ├── database/
│   │   │   └── questdb.py        # NEW - Connection pool
│   │   ├── kafka/
│   │   │   └── consumers.py      # NEW - 3 consumers
│   │   └── websocket/
│   │       └── handler.py        # NEW - STOMP handler
│   ├── requirements.txt
│   └── config.yaml               # NEW
│
├── data-generator/          # No changes ✅
├── database-consumer/       # No changes ✅
├── aggregator/             # No changes ✅
├── strategy-engine/        # No changes ✅
│
├── frontend/               # NEW - Phase 4
│   ├── src/
│   │   ├── pages/
│   │   ├── components/
│   │   ├── services/
│   │   └── App.tsx
│   └── package.json
│
└── docs/
    └── phase-4/
        ├── ARCHITECTURE-AND-IMPLEMENTATION.md  # THIS FILE
        └── FRONTEND-RESEARCH-NOTES.md
```

### Configuration

**File:** `api-service/config.yaml`

```yaml
app:
  name: "QuantStream API Service"
  version: "1.0.0"
  host: "0.0.0.0"
  port: 8085
  debug: true

cors:
  allowed_origins:
    - "http://localhost:5173"
    - "http://localhost:3000"

database:
  host: "localhost"
  port: 8812
  user: "admin"
  password: "quest"
  database: "qdb"
  pool_size: 10

kafka:
  bootstrap_servers: "localhost:9092"
  consumer_groups:
    prices: "api-service-prices"
    signals: "api-service-signals"
    candles: "api-service-candles"
  topics:
    market_data: "market-data"
    trading_signals: "trading-signals"
    candles: "candles-1m"

cache:
  ttl_seconds:
    tokens: 2
    candles: 5
    signals: 1
    leaderboard: 10

logging:
  level: "INFO"
  format: "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
```

### Database Queries

**Example: Get Token List**

```python
# app/database/questdb.py

async def get_token_list():
    query = """
        SELECT DISTINCT ON (symbol) 
            symbol,
            price,
            volume,
            timestamp
        FROM ticks
        ORDER BY symbol, timestamp DESC
    """
    conn = get_connection()
    cursor = conn.execute(query)
    return cursor.fetchall()

async def get_24h_stats(symbol: str):
    query = """
        SELECT 
            first(price) as open_24h,
            max(price) as high_24h,
            min(price) as low_24h,
            last(price) as close_24h,
            sum(volume) as volume_24h
        FROM ticks
        WHERE symbol = %s
          AND timestamp > dateadd('h', -24, now())
    """
    conn = get_connection()
    cursor = conn.execute(query, (symbol,))
    return cursor.fetchone()
```

### Kafka Consumers

```python
# app/kafka/consumers.py

from kafka import KafkaConsumer
import asyncio

class PriceConsumer:
    def __init__(self, websocket_manager):
        self.consumer = KafkaConsumer(
            'market-data',
            bootstrap_servers='localhost:9092',
            group_id='api-service-prices',
            value_deserializer=lambda m: json.loads(m.decode('utf-8'))
        )
        self.ws_manager = websocket_manager
    
    async def start(self):
        """Consume Kafka messages and push to WebSocket clients"""
        for message in self.consumer:
            data = message.value
            # Push to WebSocket topic /topic/prices
            await self.ws_manager.broadcast('/topic/prices', data)
```

### WebSocket Handler

```python
# app/websocket/handler.py

from fastapi import WebSocket
import json

class WebSocketManager:
    def __init__(self):
        self.active_connections = {}
        self.subscriptions = {}
    
    async def connect(self, websocket: WebSocket, client_id: str):
        await websocket.accept()
        self.active_connections[client_id] = websocket
    
    async def subscribe(self, client_id: str, topic: str):
        if topic not in self.subscriptions:
            self.subscriptions[topic] = []
        self.subscriptions[topic].append(client_id)
    
    async def broadcast(self, topic: str, message: dict):
        """Send message to all clients subscribed to topic"""
        if topic in self.subscriptions:
            for client_id in self.subscriptions[topic]:
                if client_id in self.active_connections:
                    ws = self.active_connections[client_id]
                    await ws.send_json(message)
```

---

## Frontend Stack

### Technology Choices

**Core:**
- React 18.3
- TypeScript 5.x
- Vite 6 (build tool)
- React Router 6 (routing)

**UI Components:**
- **Shadcn UI** - Base components (copy-paste, own the code)
- **Tremor** - Dashboard-specific components (KPIs, tables, charts)
- **Tailwind CSS 3** - Styling
- **Radix UI** - Accessibility primitives (via Shadcn)

**Charts:**
- **TradingView Lightweight Charts** - Candlestick charts (35KB, HTML5 Canvas)
- **Recharts** - Performance graphs (equity curves, comparisons)

**State Management:**
- **Zustand** - Client state (1KB, simple)
- **TanStack Query** - Server state (caching, refetching)

**Real-time:**
- **@stomp/stompjs** - WebSocket client (STOMP protocol)

**Utilities:**
- **date-fns** - Date formatting
- **numeral** - Number formatting
- **clsx** - Conditional classes

### Design System

**Color Palette (Dark Theme):**

```css
:root {
  /* Background */
  --bg-primary: #0A0E1A;        /* Deep blue-black */
  --bg-surface: #111827;        /* Cards */
  --bg-elevated: #1F2937;       /* Hover states */
  
  /* Text */
  --text-primary: #F9FAFB;      /* Off-white */
  --text-secondary: #9CA3AF;    /* Gray labels */
  --text-tertiary: #6B7280;     /* Muted text */
  
  /* Borders */
  --border: #1F2937;            /* Subtle dividers */
  
  /* Semantic */
  --success: #10B981;           /* Green - BUY */
  --danger: #EF4444;            /* Red - SELL */
  --warning: #F59E0B;           /* Amber - HOLD */
  --info: #3B82F6;              /* Blue */
  
  /* Chart Colors */
  --candle-up: #26A69A;         /* Teal green */
  --candle-down: #EF5350;       /* Coral red */
  --volume: #42A5F5;            /* Blue, transparent */
}
```

**Typography:**

```css
font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;

/* Scale */
--text-xs: 12px;
--text-sm: 14px;
--text-base: 16px;
--text-lg: 18px;
--text-xl: 20px;
--text-2xl: 24px;

/* Weights */
--font-normal: 400;
--font-medium: 500;
--font-semibold: 600;
--font-bold: 700;
```

**Spacing (4px scale):**
```
4px, 8px, 12px, 16px, 24px, 32px, 48px, 64px
```

**Border Radius:**
```
--radius-sm: 4px;
--radius-md: 8px;
--radius-lg: 12px;
```

### Component Patterns

**Data Table (Market Overview):**
- Sticky header
- Hover row highlight
- Sortable columns
- Monospace font for prices
- Color-coded percentages
- Right-align numbers

**Candlestick Chart:**
- TradingView Lightweight Charts
- Dark theme
- Volume histogram below
- Real-time updates via WebSocket
- Responsive container

**KPI Card:**
```tsx
<Card>
  <div className="text-2xl font-bold">{value}</div>
  <div className="flex items-center gap-1">
    {change > 0 ? <ArrowUp /> : <ArrowDown />}
    <span className={change > 0 ? "text-success" : "text-danger"}>
      {change}%
    </span>
  </div>
  <div className="text-sm text-secondary">{label}</div>
</Card>
```

**Signal Badge:**
```tsx
<Badge variant={action === "BUY" ? "success" : "danger"}>
  {action}
</Badge>
```

### Pages

**1. Market Dashboard (`/`)**
- Token table (real-time prices)
- Candlestick chart
- Recent signals feed
- Market overview stats

**2. Strategy Leaderboard (`/strategies`)**
- Performance rankings table
- Equity curve comparison (top 3)
- Performance heatmap (strategy × symbol)

**3. Strategy Detail (`/strategies/:name`)**
- Strategy info card
- Performance metrics
- Equity curve chart
- Recent signals table
- Strategy logic explanation

**4. Backtest Playground (`/backtest`)**
- Configuration form
- Results display
- Strategy comparison

---

## Deployment Strategy

### Free Tier Architecture

```
Frontend (Vercel Free)
    ↓
API Service (Render Free) ← QuestDB (Render Disk 1GB)
    ↓                        ↓
Kafka (Upstash Free)    Data Services × 4 (Render Free)
```

### Render.com (Backend - Free Tier)

**Services:**
1. data-generator.onrender.com
2. database-consumer.onrender.com
3. aggregator.onrender.com
4. strategy-engine.onrender.com
5. api-service.onrender.com (public-facing)

**Limits:**
- 750 hours/month per service (24/7 for all 5)
- 512 MB RAM each
- Sleeps after 15 min inactivity
- First request after sleep: ~30 seconds

**Configuration:**
```yaml
# render.yaml
services:
  - type: web
    name: api-service
    env: python
    buildCommand: pip install -r requirements.txt
    startCommand: uvicorn app.main:app --host 0.0.0.0 --port 8085
    envVars:
      - key: QUESTDB_HOST
        value: <questdb-url>
      - key: KAFKA_BROKERS
        value: <upstash-url>
```

### Upstash Kafka (Free Tier)

**Limits:**
- 10,000 messages/day
- 100 MB storage

**Solution: Reduce Tick Rate**
- 10 symbols × 1 tick/minute = 14,400 messages/day ✅

### QuestDB

**Option 1: Render Disk (Free)**
- 1 GB persistent disk
- Attached to database-consumer service

**Option 2: QuestDB Cloud (Free Tier)**
- 1 GB storage
- Better UI for demo

### Vercel (Frontend - Free)

- Unlimited bandwidth
- Global CDN
- Automatic HTTPS
- Domain: quantstream.vercel.app

---

**Total Cost:** $0/month

**Limitations:**
- Services sleep after 15 min
- Reduced tick rate (1/min vs 10/sec)
- Cold start latency (~30s)

**Acceptable for:** Portfolio, demo, learning

---

## Implementation Checklist

### Week 1: API Service Core
- [ ] Add QuestDB connection pool
- [ ] Implement 5 market data endpoints
- [ ] Add response caching
- [ ] Write tests

### Week 2: Strategy & Signal Endpoints
- [ ] Implement 5 strategy endpoints
- [ ] Implement 3 signal endpoints
- [ ] Add 2 health endpoints
- [ ] Write tests

### Week 3: Real-time (WebSocket)
- [ ] Add Kafka consumers (3 topics)
- [ ] Implement WebSocket handler
- [ ] Implement 5 STOMP topics
- [ ] Test with multiple clients

### Week 4: Frontend Setup
- [ ] Create React + TypeScript + Vite project
- [ ] Install Shadcn UI + Tremor
- [ ] Setup routing
- [ ] Create API client

### Week 5: Market Dashboard
- [ ] Token table component
- [ ] Candlestick chart (Lightweight Charts)
- [ ] Recent signals feed
- [ ] WebSocket integration

### Week 6: Strategy Pages
- [ ] Leaderboard with rankings
- [ ] Performance charts (Recharts)
- [ ] Strategy detail page
- [ ] Backtest playground

### Week 7: Polish
- [ ] Loading states
- [ ] Error handling
- [ ] Mobile responsive
- [ ] Accessibility audit
- [ ] Deploy to free tier

---

**End of Document**
