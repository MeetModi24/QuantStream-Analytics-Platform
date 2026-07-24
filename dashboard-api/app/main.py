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


# -------------------------------------------------------------------------- strategies
@app.get("/api/strategies")
async def strategies() -> list[str]:
    """Distinct strategy names that have produced a PnL snapshot."""
    rows = await questdb.query("SELECT DISTINCT strategy FROM strategy_pnl ORDER BY strategy")
    return [r["strategy"] for r in rows]


# ------------------------------------------------------------------------ strategy pnl
@app.get("/api/strategy-pnl/latest")
async def strategy_pnl_latest():
    """Latest PnL snapshot per strategy — the leaderboard source.

    ``LATEST ON ts PARTITION BY strategy`` gives QuestDB's efficient last-row-per-key.
    """
    return await questdb.query(
        "SELECT strategy, realized_pnl, unrealized_pnl, total_pnl, num_trades, win_rate, ts "
        "FROM strategy_pnl LATEST ON ts PARTITION BY strategy ORDER BY total_pnl DESC"
    )


@app.get("/api/strategy-pnl")
async def strategy_pnl_series(
    strategy: str | None = Query(None, description="Optional strategy filter"),
    limit: int = Query(600, ge=1, le=10000),
):
    """PnL time series (oldest-first) for equity curves. Optionally one strategy."""
    where = f"WHERE strategy = '{_esc(strategy)}'" if strategy else ""
    rows = await questdb.query(
        f"SELECT ts, strategy, realized_pnl, unrealized_pnl, total_pnl, num_trades, win_rate "
        f"FROM strategy_pnl {where} ORDER BY ts DESC LIMIT {limit}"
    )
    rows.reverse()
    return rows


# --------------------------------------------------------------------------- positions
@app.get("/api/positions")
async def positions_latest(
    strategy: str | None = Query(None),
    token: str | None = Query(None),
):
    """Latest open position per (strategy, token) — current exposure snapshot."""
    filters = []
    if strategy:
        filters.append(f"strategy = '{_esc(strategy)}'")
    if token:
        filters.append(f"token = '{_esc(token)}'")
    where = f"WHERE {' AND '.join(filters)}" if filters else ""
    return await questdb.query(
        f"SELECT strategy, token, net_position, avg_entry_price, realized_pnl, unrealized_pnl, ts "
        f"FROM positions {where} LATEST ON ts PARTITION BY strategy, token "
        f"ORDER BY strategy, token"
    )


@app.get("/api/consensus")
async def consensus():
    """Per-token long/short breakdown across strategies, derived from latest positions.

    A ``conflict`` is any token where at least one strategy is net-long while another is
    net-short. Computed from the positions table — no new backend state.
    """
    rows = await questdb.query(
        "SELECT strategy, token, net_position "
        "FROM positions LATEST ON ts PARTITION BY strategy, token"
    )
    by_token: dict[str, dict[str, list[str]]] = {}
    for r in rows:
        net = r["net_position"] or 0.0
        if net == 0.0:
            continue
        entry = by_token.setdefault(r["token"], {"longs": [], "shorts": []})
        (entry["longs"] if net > 0 else entry["shorts"]).append(r["strategy"])
    out = []
    for token in sorted(by_token):
        longs = sorted(by_token[token]["longs"])
        shorts = sorted(by_token[token]["shorts"])
        out.append({
            "token": token,
            "longs": longs,
            "shorts": shorts,
            "conflict": bool(longs and shorts),
        })
    return out


# ----------------------------------------------------------------------------- candles
@app.get("/api/candles")
async def candles(
    token: str = Query(...),
    interval: str = Query("1m", pattern="^(1s|5s|15s|1m|5m|15m|1h)$"),
    limit: int = Query(500, ge=1, le=5000),
):
    """Microprice OHLC candles via QuestDB ``SAMPLE BY``.

    Prices are the microprice (volume-weighted fair value), aggregated into candles so
    the front end can render a familiar price chart with signal overlays. Returns
    oldest-first for direct charting.
    """
    rows = await questdb.query(
        f"SELECT ts, first(microprice) AS open, max(microprice) AS high, "
        f"min(microprice) AS low, last(microprice) AS close "
        f"FROM features WHERE token = '{_esc(token)}' "
        f"SAMPLE BY {interval} ORDER BY ts DESC LIMIT {limit}"
    )
    rows.reverse()
    return rows


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
