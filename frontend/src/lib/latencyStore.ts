// End-to-end latency instrumentation for the live feed.
//
// Every live message carries two clocks besides the moment it lands in the browser:
//   - the EVENT time     — when the order book was generated (Features.timestamp),
//                          set as Instant.now() at the pipeline's source and copied
//                          unchanged downstream, so it is a true event-origin stamp.
//   - the API time       — `api_ts`, stamped when the message left Kafka for the API.
//   - the RECEIVE time    — Date.now() the instant we decode the frame.
//
// From those we derive three legs, in milliseconds:
//   pipeline = api_ts - event      (generate -> feature -> Kafka -> API)
//   delivery = receive - api_ts    (API -> WebSocket -> browser decode)
//   total    = receive - event     (event -> screen)
//
// CAVEAT (documented, not a bug): the generator, API, and browser all run on one host
// during development, so they share a wall clock and the subtraction is meaningful with
// no clock-sync. Across machines this would need synchronized clocks (NTP/PTP) or the
// legs would be dominated by clock skew; see docs/concepts/03-hft-monitoring-at-scale.md.
//
// We keep a bounded ring of raw samples and recompute percentiles on a THROTTLE (not per
// message). That is deliberate: at 30k msg/sec you never do O(n) work per message on the
// render path — you accumulate cheaply and summarize at a human-perceivable cadence.
import { create } from "zustand";

/** How many recent samples to keep per leg for the percentile window. */
const WINDOW = 512;
/** Recompute the published percentiles at most this often (ms). */
const RECOMPUTE_MS = 500;

export interface LatencyStats {
  p50: number;
  p99: number;
  max: number;
}

const EMPTY: LatencyStats = { p50: 0, p99: 0, max: 0 };

interface LatencyState {
  /** Published summaries — the UI reads these; they update at most every RECOMPUTE_MS. */
  pipeline: LatencyStats;
  delivery: LatencyStats;
  total: LatencyStats;
  /** Messages sampled since load (those that carried a usable event timestamp). */
  count: number;
  /** Feed a single live message's timing. Cheap: push to rings, maybe recompute. */
  sample: (eventMs: number, apiMs: number | undefined, receiveMs: number) => void;
}

// Raw sample rings live outside the store object so pushing to them does not trigger a
// React re-render — only the throttled percentile recompute publishes into the store.
const pipelineRing: number[] = [];
const deliveryRing: number[] = [];
const totalRing: number[] = [];
let sampleCount = 0;
let lastRecomputeAt = 0;

function push(ring: number[], value: number) {
  ring.push(value);
  if (ring.length > WINDOW) ring.shift();
}

function percentile(sorted: number[], p: number): number {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[idx];
}

function summarize(ring: number[]): LatencyStats {
  if (ring.length === 0) return EMPTY;
  const sorted = [...ring].sort((a, b) => a - b);
  return {
    p50: percentile(sorted, 50),
    p99: percentile(sorted, 99),
    max: sorted[sorted.length - 1],
  };
}

export const useLatencyStore = create<LatencyState>((set) => ({
  pipeline: EMPTY,
  delivery: EMPTY,
  total: EMPTY,
  count: 0,
  sample: (eventMs, apiMs, receiveMs) => {
    // A malformed or missing event stamp yields NaN; skip it rather than poison the ring.
    if (Number.isFinite(eventMs)) {
      push(totalRing, Math.max(0, receiveMs - eventMs));
      if (apiMs !== undefined && Number.isFinite(apiMs)) {
        push(pipelineRing, Math.max(0, apiMs - eventMs));
      }
    }
    if (apiMs !== undefined && Number.isFinite(apiMs)) {
      push(deliveryRing, Math.max(0, receiveMs - apiMs));
    }
    sampleCount += 1;

    // Throttled publish — the only thing that touches React state.
    const now = receiveMs;
    if (now - lastRecomputeAt < RECOMPUTE_MS) return;
    lastRecomputeAt = now;
    set({
      pipeline: summarize(pipelineRing),
      delivery: summarize(deliveryRing),
      total: summarize(totalRing),
      count: sampleCount,
    });
  },
}));

/** Parse the event-origin timestamp from a live payload. Lives here so the ingest path
 *  stays declarative. The Java payload field is `timestamp` (ISO-8601); we tolerate a
 *  numeric epoch too, and fall back to NaN so the sampler can skip it. */
export function eventMillisFromPayload(data: unknown): number {
  const t = (data as { timestamp?: string | number } | null)?.timestamp;
  if (typeof t === "number") return t;
  if (typeof t === "string") return Date.parse(t);
  return NaN;
}
