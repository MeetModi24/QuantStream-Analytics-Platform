# Free Tier Deployment Strategy

## Goal

Deploy **QuantStream** to production with **$0/month cost** using only free tiers.

---

## Reality Check: What's Possible vs Impossible

### ✅ Possible (With Compromises)

- Frontend hosting (unlimited)
- Backend services (with sleep after inactivity)
- Database storage (1 GB limit)
- Kafka messaging (10k messages/day limit)

### ❌ Impossible (Free Tier Limits)

- **24/7 uptime** - Free services sleep after 15 min inactivity
- **High throughput** - 10 ticks/sec × 10 tokens = 864k msgs/day (86x over limit)
- **Large storage** - 90 days of data = ~9 GB (9x over limit)

---

## Free Tier Services Analysis

### 1. Frontend: Vercel (✅ Perfect)

**Free Tier:**
- Unlimited bandwidth
- Global CDN
- Automatic HTTPS
- GitHub integration (auto-deploy on push)
- Custom domain support

**Limits:**
- 100 GB bandwidth/month
- 100 serverless function invocations/day

**Verdict:** ✅ **No compromises needed**

**URL:** `https://quantstream.vercel.app`

---

### 2. Backend Services: Render.com (✅ Acceptable with Limitations)

**Free Tier (per service):**
- 512 MB RAM
- Shared CPU
- 750 hours/month (enough for 24/7)
- Sleep after 15 min inactivity ← **Major limitation**
- First request after sleep takes ~30-60s to wake

**Limits:**
- Max 5 services per account (we have 4, perfect fit)
- Services must be public (no private networking)
- No persistent volumes (use external DB)

**Services to Deploy:**
1. `data-generator` - Generates ticks
2. `aggregator` - Creates candles
3. `strategy-engine` - Generates signals
4. `database-consumer` - Writes to DB
5. (API Gateway will be Phase 4)

**Verdict:** ⚠️ **Acceptable for demo, not production**

**Workaround for sleep:**
- Add cron job to ping services every 10 minutes (keep awake)
- Use UptimeRobot (free) to monitor and ping
- Accept 30s cold start on first user visit

---

### 3. Kafka: Upstash (❌ Insufficient, Need Compromise)

**Free Tier:**
- 10,000 messages/day
- 10 GB traffic/month
- 1 MB max message size
- Serverless (pay-per-use after free tier)

**Our Requirements:**
- 10 tokens × 10 ticks/sec = 100 ticks/sec
- 100 × 60 × 60 × 24 = **8,640,000 messages/day**
- **864x over free tier limit!**

**Paid Tier:**
- $0.02 per 100k messages
- 8.64M messages/day = $1.73/day = **$52/month**

**Verdict:** ❌ **Free tier impossible at current scale**

**Compromise Options:**

#### Option A: Reduce Tick Rate (Recommended)
```
Reduce from 10 ticks/sec to 1 tick/10 sec per token:
- 10 tokens × 0.1 ticks/sec = 1 tick/sec
- 1 × 60 × 60 × 24 = 8,640 messages/day ✅

Within free tier!
```

#### Option B: Reduce Symbol Count
```
Reduce from 10 tokens to 2 tokens:
- 2 tokens × 10 ticks/sec = 20 ticks/sec
- 20 × 60 × 60 × 24 = 1,728,000 messages/day ❌
Still 173x over limit
```

#### Option C: Batch Messages
```
Batch 10 ticks into 1 Kafka message:
- 8,640,000 ticks/day ÷ 10 = 864,000 messages/day ❌
Still 86x over limit
```

**Recommended:** **Option A** - Reduce tick rate to 1 tick/10 sec

**Trade-offs:**
- Less real-time (10s latency vs 1s)
- Strategies still work (historical data sufficient)
- Charts update every 10s (still acceptable)

---

### 4. Database: QuestDB on Render Disk (⚠️ Limited Storage)

**Render Free Tier Disk:**
- 1 GB persistent disk per service
- Deleted if service sleeps > 7 days
- Not replicated (single point of failure)

**Alternative: QuestDB Cloud Free Tier:**
- 1 GB storage
- 100 MB ingestion/day
- 1 GB query/day

**Our Storage Needs (Reduced Tick Rate):**

**Ticks Table:**
- 10 tokens × 1 tick/10s = 8,640 ticks/day
- ~50 bytes/row
- 8,640 × 50 = 432 KB/day
- 30 days = **13 MB** ✅

**Candles Table:**
- 10 tokens × 1 candle/min = 14,400 candles/day
- ~80 bytes/row
- 14,400 × 80 = 1.15 MB/day
- 30 days = **35 MB** ✅

**Signals Table:**
- ~50 signals/day (sparse)
- ~100 bytes/row
- 30 days = **150 KB** ✅

**Total 30 Days:** 13 + 35 + 0.15 = **~50 MB** ✅

**Verdict:** ✅ **Within limits with reduced tick rate**

**Storage Rotation Strategy:**
```sql
-- Delete data older than 30 days (run daily)
DELETE FROM ticks WHERE timestamp < dateadd('d', -30, now());
DELETE FROM candles_1m WHERE timestamp < dateadd('d', -30, now());
DELETE FROM signals WHERE timestamp < dateadd('d', -30, now());
```

---

### 5. Alternative: Skip Kafka, Use HTTP

**If Kafka free tier is still too limiting:**

```
Generator → HTTP POST → Database Consumer → QuestDB
                                 ↓
                         Aggregator (polls DB)
                                 ↓
                         Strategy Engine (polls DB)
```

**Pros:**
- No Kafka cost
- Simpler deployment

**Cons:**
- Lose event streaming benefits
- Tight coupling
- No replay capability
- Less impressive architecture

**Verdict:** ❌ **Avoid - defeats learning purpose**

---

## Recommended Free Tier Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    VERCEL (Frontend)                     │
│                   quantstream.vercel.app                 │
│                                                          │
│  - React + TypeScript                                   │
│  - Unlimited bandwidth                                   │
│  - Global CDN                                            │
└────────────────────────┬────────────────────────────────┘
                         ↓ HTTPS
┌─────────────────────────────────────────────────────────┐
│              RENDER.COM (Backend Services)               │
│                                                          │
│  1. data-generator.onrender.com (512 MB)                │
│     - Generates 1 tick/10s per token                    │
│     - 8,640 ticks/day                                   │
│                                                          │
│  2. aggregator.onrender.com (512 MB)                    │
│     - Kafka Streams windowing                           │
│     - Creates 1-min candles                             │
│                                                          │
│  3. strategy-engine.onrender.com (512 MB)               │
│     - 10 alpha strategies                                │
│     - Runs every 60 seconds                             │
│                                                          │
│  4. database-consumer.onrender.com (512 MB)             │
│     - Writes ticks, candles, signals                    │
│     - Batch writes for efficiency                       │
│                                                          │
│  ⚠️  Services sleep after 15 min inactivity             │
│  ⚠️  First request takes ~30-60s to wake                │
└────────────────────────┬────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│                 UPSTASH (Kafka - Free Tier)              │
│                                                          │
│  Topics:                                                 │
│   - market-data (8,640 msgs/day) ✅                     │
│   - candles-1m (14,400 msgs/day) ❌ Over limit          │
│   - trading-signals (~50 msgs/day) ✅                   │
│                                                          │
│  Solution: Skip candles-1m topic, write directly to DB  │
└────────────────────────┬────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│            QUESTDB CLOUD (Free Tier) or                 │
│            RENDER DISK (1 GB)                            │
│                                                          │
│  Storage: ~50 MB for 30 days ✅                         │
│  Tables: ticks, candles_1m, signals                     │
│  Rotation: Delete data > 30 days old                    │
└─────────────────────────────────────────────────────────┘
```

---

## Modified Architecture (Free Tier Optimized)

### Changes from Original Design

**1. Reduced Tick Rate:**
- From: 10 ticks/sec
- To: 1 tick/10 sec
- Impact: Still enough for strategies, slightly less real-time

**2. Skip Candles Kafka Topic:**
- From: `market-data` → Aggregator → `candles-1m` → Consumer → DB
- To: `market-data` → Consumer → DB; Aggregator reads DB → writes DB directly
- Impact: Saves 14,400 Kafka messages/day

**3. Cron Keep-Alive:**
- Add UptimeRobot to ping services every 10 minutes
- Prevents sleep during demo hours
- Accept cold start outside demo times

---

## Deployment Checklist

### Prerequisites
- [ ] GitHub repo with all code
- [ ] Accounts created:
  - [ ] Vercel (GitHub OAuth)
  - [ ] Render.com (GitHub OAuth)
  - [ ] Upstash (email signup)
  - [ ] QuestDB Cloud (email signup)

### Step 1: Deploy Kafka (Upstash)
```bash
# Create Kafka cluster
1. Go to console.upstash.com
2. Create new cluster (free tier)
3. Create topics:
   - market-data (1 partition, 1 day retention)
   - trading-signals (1 partition, 7 day retention)
4. Copy bootstrap servers URL
5. Copy username/password
```

### Step 2: Deploy QuestDB
```bash
# Option A: QuestDB Cloud
1. Go to questdb.com/cloud
2. Create free tier instance
3. Copy connection string
4. Run schema SQL

# Option B: Render Disk
1. Add persistent disk to database-consumer
2. QuestDB runs embedded
3. Data stored on disk
```

### Step 3: Deploy Backend Services (Render)
```bash
# For each service (data-generator, aggregator, strategy-engine, database-consumer):
1. Go to dashboard.render.com
2. New > Web Service
3. Connect GitHub repo
4. Select service directory
5. Build command: mvn clean package
6. Start command: java -jar target/<service>.jar
7. Add environment variables:
   - KAFKA_BOOTSTRAP_SERVERS=<upstash-url>
   - KAFKA_USERNAME=<upstash-user>
   - KAFKA_PASSWORD=<upstash-pass>
   - QUESTDB_URL=<questdb-connection-string>
8. Deploy
```

### Step 4: Deploy Frontend (Vercel)
```bash
# One-time setup
1. Go to vercel.com
2. Import GitHub repo
3. Framework: React
4. Build command: npm run build
5. Output directory: dist
6. Add environment variable:
   - VITE_API_URL=https://api-gateway.onrender.com
7. Deploy

# Auto-deploy on git push
- Push to main branch → auto-deploys
```

### Step 5: Configure Keep-Alive
```bash
# UptimeRobot (free tier)
1. Go to uptimerobot.com
2. Add 4 monitors (one per service):
   - data-generator.onrender.com/health
   - aggregator.onrender.com/health
   - strategy-engine.onrender.com/health
   - database-consumer.onrender.com/health
3. Check interval: 10 minutes
4. Alert if down > 2 checks
```

---

## Performance Expectations (Free Tier)

### Cold Start
- **First request:** 30-60 seconds (service waking)
- **Subsequent requests:** <100ms

### Data Freshness
- **Ticks:** Updated every 10 seconds
- **Candles:** New candle every 1 minute
- **Signals:** Generated every 60 seconds
- **Charts:** Refresh every 10 seconds

### Limitations
- **Not suitable for:** Real-time trading, HFT, production use
- **Suitable for:** Portfolio demo, learning, interviews

---

## Cost Breakdown

| Service | Free Tier | Our Usage | Status |
|---------|-----------|-----------|--------|
| Vercel (Frontend) | Unlimited | ~1 GB/month | ✅ Free |
| Render (4 services) | 750 hrs/mo each | 24/7 = 720 hrs | ✅ Free |
| Upstash Kafka | 10k msgs/day | 8,640 msgs/day | ✅ Free |
| QuestDB Cloud | 1 GB storage | 50 MB | ✅ Free |
| UptimeRobot | 50 monitors | 4 monitors | ✅ Free |
| **Total** | | | **$0/month** |

---

## Upgrade Path (If You Want to Pay Later)

### Option 1: Scale Current Architecture ($20-30/month)
- **Render Pro:** $7/service × 4 = $28/month
  - No sleep
  - 2 GB RAM per service
  - Faster CPU
- **Upstash Pay-As-Go:** ~$5/month for higher throughput
- **QuestDB Cloud Pro:** $29/month (10 GB storage, faster queries)

**Total:** ~$60/month

### Option 2: Move to Cloud VPS ($5/month)
- **Hetzner Cloud:** €4.50/month (~$5)
  - 2 vCPU, 4 GB RAM, 40 GB SSD
  - Run all services + Kafka + QuestDB on single VM
  - No sleep, full control
  - Need to manage infrastructure

### Option 3: AWS Free Tier (12 months free)
- **EC2 t2.micro:** 750 hours/month (24/7 for 1 instance)
- **RDS PostgreSQL:** 750 hours/month (for QuestDB alternative)
- **1 GB outbound traffic/month**
- After 12 months: ~$15-20/month

---

## Recommendation

**For Portfolio/Demo (Now):**
- ✅ Deploy to free tier with reduced tick rate
- ✅ Accept 30s cold start limitation
- ✅ Use UptimeRobot to keep alive during demo times
- ✅ Total cost: **$0/month**

**For Production (Future):**
- Upgrade to Render Pro or Hetzner VPS
- Increase tick rate to 10/sec
- Add monitoring and alerting
- Cost: **$5-60/month** depending on option

**For Interviews/Portfolio:**
Free tier is **perfectly acceptable**. Emphasize:
- "Deployed fully functional trading platform on 100% free tier"
- "Optimized architecture to work within free tier constraints"
- "Demonstrates cost-consciousness and resourcefulness"

---

## Next Steps

1. ✅ **Complete Phase 2** (build aggregator + strategies)
2. ✅ **Complete Phase 3** (build backtester)
3. ✅ **Complete Phase 4** (build API gateway + frontend)
4. ⏳ **Deploy to free tier** (follow checklist above)
5. ⏳ **Add to portfolio/resume**

Deployment will be tackled after Phase 4 is complete!
