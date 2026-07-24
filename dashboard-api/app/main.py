"""QuantStream dashboard API.

Two data paths, deliberately separated:

* **REST over QuestDB** — historical / on-load queries (recent features, signals,
  latest order book). Reads the durable store, so it survives restarts and backfills
  a freshly-opened dashboard.
* **WebSocket over Kafka** — the live tip of the stream, pushed as it happens with
  sub-second latency (see :mod:`app.live_feed`).

The served HTML page uses REST once on load to paint history, then switches to the
WebSocket for live updates.
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

from .config import settings
from .live_feed import live_feed
from .questdb import QuestDBError, questdb

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("dashboard-api")

STATIC_DIR = Path(__file__).parent / "static"


@asynccontextmanager
async def lifespan(app: FastAPI):
    await questdb.start()
    await live_feed.start()
    log.info("dashboard-api ready")
    try:
        yield
    finally:
        await live_feed.stop()
        await questdb.close()


app = FastAPI(title="QuantStream Dashboard API", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ----------------------------------------------------------------------------- health
@app.get("/api/health")
async def health() -> JSONResponse:
    ok = await questdb.health()
    return JSONResponse({"status": "ok" if ok else "degraded", "questdb": ok},
                        status_code=200 if ok else 503)


# ------------------------------------------------------------------------------ tokens
@app.get("/api/tokens")
async def tokens() -> list[str]:
    """Distinct tokens currently present in the features table."""
    rows = await questdb.query("SELECT DISTINCT token FROM features ORDER BY token")
    return [r["token"] for r in rows]


# ---------------------------------------------------------------------------- features
@app.get("/api/features")
async def features(
    token: str = Query(..., description="Ticker symbol, e.g. AAPL"),
    limit: int = Query(300, ge=1, le=5000),
):
    """Most-recent feature rows for a token, oldest-first for direct charting."""
    rows = await questdb.query(
        f"SELECT ts, token, obi, microprice, mid_price, spread, spread_bps "
        f"FROM features WHERE token = '{_esc(token)}' ORDER BY ts DESC LIMIT {limit}"
    )
    rows.reverse()
    return rows


# ----------------------------------------------------------------------------- signals
@app.get("/api/signals")
async def signals(
    token: str | None = Query(None, description="Optional token filter"),
    limit: int = Query(100, ge=1, le=5000),
):
    """Most-recent signals, newest-first, optionally filtered by token."""
    where = f"WHERE token = '{_esc(token)}'" if token else ""
    rows = await questdb.query(
        f"SELECT ts, strategy, token, action, price, confidence, reason "
        f"FROM signals {where} ORDER BY ts DESC LIMIT {limit}"
    )
    return rows


# -------------------------------------------------------------------------- order book
@app.get("/api/orderbook/latest")
async def latest_order_book(token: str = Query(...)):
    """Latest order-book snapshot row for a token."""
    rows = await questdb.query(
        f"SELECT * FROM order_book_snapshots WHERE token = '{_esc(token)}' "
        f"ORDER BY ts DESC LIMIT 1"
    )
    return rows[0] if rows else JSONResponse({"detail": "no data"}, status_code=404)


# --------------------------------------------------------------------------- websocket
@app.websocket("/ws/live")
async def ws_live(websocket: WebSocket) -> None:
    """Push live feature/signal messages to the client as they arrive from Kafka."""
    await websocket.accept()
    sub = await live_feed.subscribe()
    try:
        while True:
            message = await sub.queue.get()
            if sub.dropped:
                message = {**message, "lagging": sub.dropped}
            await websocket.send_json(message)
    except WebSocketDisconnect:
        pass
    except Exception:  # pragma: no cover - client abrupt close
        log.debug("WebSocket send failed; closing", exc_info=True)
    finally:
        await live_feed.unsubscribe(sub)
        with contextlib.suppress(Exception):
            await websocket.close()


# ------------------------------------------------------------------------ static / UI
@app.get("/")
async def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


def _esc(value: str) -> str:
    """Escape single quotes for safe inlining into QuestDB SQL string literals.

    Token values come from a fixed, config-controlled universe, but we still guard
    against quote injection defensively (QuestDB's /exec takes no bind parameters).
    """
    return value.replace("'", "''")
