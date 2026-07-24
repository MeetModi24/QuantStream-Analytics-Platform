// Opens (and keeps open) the single app-wide WebSocket to the live feed, with
// auto-reconnect and backoff. Mount once, near the app root.
import { useEffect, useRef } from "react";
import { useLiveStore } from "./liveStore";
import type { LiveEnvelope } from "./types";

export function useLiveFeed() {
  const ingest = useLiveStore((s) => s.ingest);
  const setStatus = useLiveStore((s) => s.setStatus);
  const retry = useRef(0);
  const stopped = useRef(false);

  useEffect(() => {
    stopped.current = false;
    let ws: WebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const connect = () => {
      if (stopped.current) return;
      setStatus(retry.current === 0 ? "connecting" : "connecting");
      const proto = window.location.protocol === "https:" ? "wss" : "ws";
      ws = new WebSocket(`${proto}://${window.location.host}/ws/live`);

      ws.onopen = () => {
        retry.current = 0;
        setStatus("open");
      };
      ws.onmessage = (ev) => {
        try {
          ingest(JSON.parse(ev.data) as LiveEnvelope);
        } catch {
          /* ignore malformed frame */
        }
      };
      ws.onclose = () => {
        setStatus("closed");
        if (stopped.current) return;
        // Exponential backoff capped at 10s.
        const delay = Math.min(10_000, 500 * 2 ** retry.current);
        retry.current += 1;
        timer = setTimeout(connect, delay);
      };
      ws.onerror = () => ws?.close();
    };

    connect();
    return () => {
      stopped.current = true;
      if (timer) clearTimeout(timer);
      ws?.close();
    };
  }, [ingest, setStatus]);
}
