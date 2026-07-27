// Thin typed fetch layer over the dashboard-api REST endpoints. All calls go through
// the Vite proxy in dev (/api -> :8000), so no base URL is needed.
import type {
  CandleRow,
  ConsensusRow,
  FeatureRow,
  OrderBookRow,
  PositionRow,
  SignalRow,
  StrategyPnlRow,
} from "./types";

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { headers: { Accept: "application/json" } });
  if (!res.ok) {
    throw new Error(`API ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

export const api = {
  health: () => get<{ status: string; questdb: boolean }>("/health"),
  tokens: () => get<string[]>("/tokens"),
  strategies: () => get<string[]>("/strategies"),

  features: (token: string, limit = 300) =>
    get<FeatureRow[]>(`/features?token=${encodeURIComponent(token)}&limit=${limit}`),

  // Recent features for ALL tokens in one request — backs the Market Overview
  // sparklines so the page scales past a handful of tokens without an N+1 fetch.
  featuresRecent: (minutes = 2) =>
    get<Record<string, FeatureRow[]>>(`/features/recent?minutes=${minutes}`),

  signals: (token?: string, limit = 100) =>
    get<SignalRow[]>(
      `/signals?limit=${limit}${token ? `&token=${encodeURIComponent(token)}` : ""}`,
    ),

  orderbookLatest: (token: string) =>
    get<OrderBookRow>(`/orderbook/latest?token=${encodeURIComponent(token)}`),

  strategyPnlLatest: () => get<StrategyPnlRow[]>("/strategy-pnl/latest"),

  strategyPnlSeries: (strategy?: string, limit = 600) =>
    get<StrategyPnlRow[]>(
      `/strategy-pnl?limit=${limit}${strategy ? `&strategy=${encodeURIComponent(strategy)}` : ""}`,
    ),

  positions: (opts?: { strategy?: string; token?: string }) => {
    const p = new URLSearchParams();
    if (opts?.strategy) p.set("strategy", opts.strategy);
    if (opts?.token) p.set("token", opts.token);
    const qs = p.toString();
    return get<PositionRow[]>(`/positions${qs ? `?${qs}` : ""}`);
  },

  consensus: () => get<ConsensusRow[]>("/consensus"),

  candles: (token: string, interval = "1m", limit = 500) =>
    get<CandleRow[]>(
      `/candles?token=${encodeURIComponent(token)}&interval=${interval}&limit=${limit}`,
    ),
};
