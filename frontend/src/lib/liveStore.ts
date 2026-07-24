// Live WebSocket state, shared app-wide via Zustand.
//
// One WebSocket to /ws/live is opened once (see useLiveFeed) and pushes envelopes here.
// We keep:
//   - connection status + last message time (for the header indicator)
//   - the latest FeatureRow per token (Market Overview reads the live tip)
//   - a bounded ring of recent signals (Live Signals feed)
//   - the server-reported "lagging" drop count, if the client falls behind
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

export const useLiveStore = create<LiveState>((set) => ({
  status: "connecting",
  lastMessageAt: null,
  lagging: 0,
  latestFeature: {},
  signals: [],
  signalSeq: 0,
  setStatus: (s) => set({ status: s }),
  ingest: (env) =>
    set((state) => {
      const patch: Partial<LiveState> = {
        lastMessageAt: Date.now(),
        lagging: env.lagging ?? state.lagging,
      };
      if (env.kind === "feature") {
        patch.latestFeature = { ...state.latestFeature, [env.data.token]: env.data };
      } else {
        const next = [env.data, ...state.signals];
        if (next.length > MAX_SIGNALS) next.length = MAX_SIGNALS;
        patch.signals = next;
        patch.signalSeq = state.signalSeq + 1;
      }
      return patch;
    }),
}));
