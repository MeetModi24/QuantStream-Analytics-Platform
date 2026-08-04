// Live WebSocket state, shared app-wide via Zustand.
//
// One WebSocket to /ws/live is opened once (see useLiveFeed) and pushes envelopes here.
// We keep:
//   - connection status + last message time (for the header indicator)
//   - the latest FeatureRow per token (Market Overview reads the live tip)
//   - a bounded ring of recent signals (Live Signals feed)
//   - the server-reported "lagging" drop count, if the client falls behind
//
// RENDER THROTTLING (the important bit):
// The feed can deliver many messages per second, but the screen only repaints ~60 times a
// second and the human eye perceives far less. So `ingest` does NOT write React state on
// every message. Instead it accumulates messages in plain module-level buffers (no re-render)
// and schedules a SINGLE store update on the next animation frame. Result: N messages/sec
// collapse into at most ~1 repaint per frame, regardless of feed rate. This decouples the
// data-rate from the render-rate — the single most important frontend scaling move.
// See docs/concepts/05-render-throttling.md.
import { create } from "zustand";
import type { FeatureRow, LiveEnvelope, SignalRow } from "./types";

const MAX_SIGNALS = 500;

export type ConnStatus = "connecting" | "open" | "closed";

interface LiveState {
  status: ConnStatus;
  lastMessageAt: number | null;
  lagging: number;
  latestFeature: Record<string, FeatureRow>;
  signals: SignalRow[];
  signalSeq: number; // monotonic counter for stable react keys
  setStatus: (s: ConnStatus) => void;
  ingest: (env: LiveEnvelope) => void;
}

// --- Coalescing buffers (live OUTSIDE the store, so writing them triggers no re-render) ---
// Features conflate by token: within a single frame only the newest value per token
// survives, which is exactly what a "latest per token" map wants anyway.
const pendingFeatures = new Map<string, FeatureRow>();
// Signals accumulate in arrival order; flushed newest-first.
let pendingSignals: SignalRow[] = [];
let pendingLagging: number | null = null;
let pendingLastAt: number | null = null;
let flushScheduled = false;

// requestAnimationFrame in the browser; a ~60fps timer fallback for jsdom/SSR/tests.
const scheduleFlush: (cb: () => void) => void =
  typeof requestAnimationFrame === "function"
    ? (cb) => requestAnimationFrame(cb)
    : (cb) => {
        setTimeout(cb, 16);
      };

export const useLiveStore = create<LiveState>((set) => {
  const flush = () => {
    flushScheduled = false;
    const hasFeatures = pendingFeatures.size > 0;
    const hasSignals = pendingSignals.length > 0;
    if (!hasFeatures && !hasSignals && pendingLagging === null && pendingLastAt === null) {
      return;
    }

    set((state) => {
      const patch: Partial<LiveState> = {};

      if (pendingLastAt !== null) patch.lastMessageAt = pendingLastAt;
      if (pendingLagging !== null) patch.lagging = pendingLagging;

      if (hasFeatures) {
        // One spread for the whole batch, not one per message.
        patch.latestFeature = { ...state.latestFeature };
        for (const [token, row] of pendingFeatures) patch.latestFeature[token] = row;
      }

      if (hasSignals) {
        // pendingSignals is oldest-first; prepend reversed so the newest ends up on top.
        const next = state.signals.slice();
        for (const s of pendingSignals) next.unshift(s);
        if (next.length > MAX_SIGNALS) next.length = MAX_SIGNALS;
        patch.signals = next;
        patch.signalSeq = state.signalSeq + pendingSignals.length;
      }

      return patch;
    });

    // Reset buffers for the next frame.
    pendingFeatures.clear();
    pendingSignals = [];
    pendingLagging = null;
    pendingLastAt = null;
  };

  return {
    status: "connecting",
    lastMessageAt: null,
    lagging: 0,
    latestFeature: {},
    signals: [],
    signalSeq: 0,
    setStatus: (s) => set({ status: s }),
    ingest: (env) => {
      // Cheap: mutate plain buffers, no React state touched here.
      pendingLastAt = Date.now();
      if (env.lagging !== undefined) pendingLagging = env.lagging;
      if (env.kind === "feature") {
        pendingFeatures.set(env.data.token, env.data);
      } else {
        pendingSignals.push(env.data);
      }
      // Publish at most once per animation frame.
      if (!flushScheduled) {
        flushScheduled = true;
        scheduleFlush(flush);
      }
    },
  };
});
