"""Application configuration, sourced from environment variables.

Every operational parameter is here — nothing about scaling from 1 to 100 tokens
requires a code change. Override any field with an env var (e.g. ``QS_KAFKA_BROKERS``,
``QS_QUESTDB_HTTP``) or a ``.env`` file.
"""
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="QS_", env_file=".env", extra="ignore")

    # --- QuestDB (REST/HTTP query endpoint; container 9000 mapped to host 9001) ---
    questdb_http: str = "http://localhost:9001"
    query_timeout_s: float = 10.0

    # --- Kafka (live feed source for the WebSocket) ---
    kafka_brokers: str = "localhost:9092"
    features_topic: str = "features"
    signals_topic: str = "signals"
    order_book_topic: str = "order-book-data"
    # A fresh group id each start => the dashboard always tails the *live* tip of the
    # stream rather than replaying history (history is served over REST from QuestDB).
    consumer_group_prefix: str = "dashboard-api"

    # --- HTTP server ---
    host: str = "0.0.0.0"
    port: int = 8000
    cors_origins: str = "*"

    # --- Live feed fan-out ---
    # Max messages buffered per WebSocket client before slow clients are dropped,
    # so one stuck browser tab cannot back-pressure the whole broadcaster.
    client_queue_size: int = 1000

    @property
    def kafka_broker_list(self) -> list[str]:
        return [b.strip() for b in self.kafka_brokers.split(",") if b.strip()]

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


settings = Settings()
