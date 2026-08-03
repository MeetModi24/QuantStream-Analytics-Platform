"""Live feed: bridges Kafka topics to connected WebSocket clients.

One :class:`LiveFeed` runs a single aiokafka consumer subscribed to the features and
signals topics. Each received message is fanned out to every subscribed client via a
per-client bounded queue. Bounded queues are the back-pressure boundary: if a browser
tab stalls and its queue fills, that client's messages are dropped (and the client
marked lagging) rather than blocking the consumer or growing memory without limit.

The consumer uses a unique group id per process start, so the dashboard always tails
the live tip of the stream — historical data is served separately over REST from QuestDB.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

from aiokafka import AIOKafkaConsumer

from .config import settings

log = logging.getLogger(__name__)


class Subscriber:
    """A single WebSocket client's view of the feed."""

    def __init__(self, queue_size: int) -> None:
        self.queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=queue_size)
        self.dropped = 0

    def offer(self, message: dict[str, Any]) -> None:
        """Non-blocking enqueue; drop-oldest if the client is not keeping up."""
        try:
            self.queue.put_nowait(message)
        except asyncio.QueueFull:
            # Drop the oldest to make room — a live dashboard cares about recency,
            # not completeness. Count drops so the client can be told it is lagging.
            try:
                self.queue.get_nowait()
                self.queue.put_nowait(message)
            except (asyncio.QueueEmpty, asyncio.QueueFull):
                pass
            self.dropped += 1


class LiveFeed:
    def __init__(self) -> None:
        self._subscribers: set[Subscriber] = set()
        self._lock = asyncio.Lock()
        self._consumer: AIOKafkaConsumer | None = None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        # NOTE: no fixed group instance id — a new random group id per start makes each
        # dashboard process an independent live tail with auto_offset_reset=latest.
        self._consumer = AIOKafkaConsumer(
            settings.features_topic,
            settings.signals_topic,
            bootstrap_servers=settings.kafka_broker_list,
            group_id=None,  # assign-less live tail; read from the latest offset
            auto_offset_reset="latest",
            enable_auto_commit=False,
            value_deserializer=lambda b: b.decode("utf-8"),
        )
        await self._consumer.start()
        self._task = asyncio.create_task(self._run(), name="live-feed-consumer")
        log.info("LiveFeed started; tailing topics %s, %s",
                 settings.features_topic, settings.signals_topic)

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._consumer is not None:
            await self._consumer.stop()
            self._consumer = None

    async def subscribe(self) -> Subscriber:
        sub = Subscriber(settings.client_queue_size)
        async with self._lock:
            self._subscribers.add(sub)
        log.info("WebSocket client subscribed (%d total)", len(self._subscribers))
        return sub

    async def unsubscribe(self, sub: Subscriber) -> None:
        async with self._lock:
            self._subscribers.discard(sub)
        log.info("WebSocket client left (%d remaining)", len(self._subscribers))

    async def _run(self) -> None:
        assert self._consumer is not None
        try:
            async for record in self._consumer:
                # Tag each message with which stream it came from so the browser can
                # route it (chart vs. signals table) without guessing from the shape.
                kind = "signal" if record.topic == settings.signals_topic else "feature"
                try:
                    payload = json.loads(record.value)
                except json.JSONDecodeError:
                    log.warning("Skipping non-JSON message on %s", record.topic)
                    continue
                # Stamp the moment this message left Kafka for the API, in epoch
                # milliseconds. The browser uses it to split end-to-end latency into
                # a pipeline leg (event -> API) and a delivery leg (API -> screen).
                envelope = {"kind": kind, "data": payload, "api_ts": time.time() * 1000.0}
                # Snapshot subscribers under lock, then fan out without holding it.
                async with self._lock:
                    targets = list(self._subscribers)
                for sub in targets:
                    sub.offer(envelope)
        except asyncio.CancelledError:
            raise
        except Exception:  # pragma: no cover - defensive
            log.exception("LiveFeed consumer loop crashed")
            raise


live_feed = LiveFeed()
