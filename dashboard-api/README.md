# QuantStream Dashboard API

Python **FastAPI** backend for the QuantStream monitoring dashboard. Two data paths:

- **REST over QuestDB** (`/api/*`) — historical / on-load queries against the durable store.
- **WebSocket over Kafka** (`/ws/live`) — the live tip of the stream, pushed sub-second
  via an `aiokafka` consumer that tails the `features` and `signals` topics.

The served page (`GET /`) paints history from REST once, then switches to the WebSocket
for live updates (OBI/microprice chart + signals table).

## Run

```bash
cd dashboard-api
uv sync
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
# open http://localhost:8000
```

Requires the pipeline running (generator → feature-calculator → strategy-engine →
database-writer) plus Kafka and QuestDB (`docker compose up -d`).

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health` | QuestDB liveness |
| GET | `/api/tokens` | Distinct tokens seen in `features` |
| GET | `/api/features?token=&limit=` | Recent features, oldest-first (for charting) |
| GET | `/api/signals?token=&limit=` | Recent signals, newest-first |
| GET | `/api/orderbook/latest?token=` | Latest order-book snapshot row |
| WS  | `/ws/live` | Live `{kind: feature\|signal, data: {...}}` envelopes |

## Config

All settings are env-driven (prefix `QS_`), so scaling 1 → 100 tokens is config-only.
See `app/config.py`; override via env or `.env`. Key vars: `QS_QUESTDB_HTTP`,
`QS_KAFKA_BROKERS`, `QS_PORT`, `QS_CORS_ORIGINS`.
