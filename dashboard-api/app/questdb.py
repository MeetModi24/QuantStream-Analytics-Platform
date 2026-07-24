"""Async QuestDB client over the HTTP ``/exec`` query endpoint.

QuestDB returns query results as JSON with separate ``columns`` and ``dataset``
(row-arrays) fields. This client turns them into a list of dicts so the API layer
can serialize them directly, and centralizes error handling so a QuestDB-side SQL
error surfaces as a clean exception rather than a silent empty result.
"""
from __future__ import annotations

from typing import Any

import httpx

from .config import settings


class QuestDBError(RuntimeError):
    """Raised when QuestDB returns an error for a query."""


class QuestDBClient:
    def __init__(self, base_url: str | None = None, timeout: float | None = None) -> None:
        self._base_url = (base_url or settings.questdb_http).rstrip("/")
        self._timeout = timeout or settings.query_timeout_s
        self._client: httpx.AsyncClient | None = None

    async def start(self) -> None:
        self._client = httpx.AsyncClient(base_url=self._base_url, timeout=self._timeout)

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def query(self, sql: str) -> list[dict[str, Any]]:
        """Run a SQL query and return rows as a list of column-keyed dicts."""
        if self._client is None:
            raise RuntimeError("QuestDBClient used before start()")

        resp = await self._client.get("/exec", params={"query": sql})
        resp.raise_for_status()
        body = resp.json()

        if "error" in body:
            raise QuestDBError(f"{body['error']} (query={sql!r})")

        columns = [c["name"] for c in body.get("columns", [])]
        dataset = body.get("dataset", [])
        return [dict(zip(columns, row)) for row in dataset]

    async def health(self) -> bool:
        """Cheap liveness probe against QuestDB."""
        try:
            await self.query("SELECT 1")
            return True
        except Exception:
            return False


questdb = QuestDBClient()
