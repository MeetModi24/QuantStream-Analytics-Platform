// Shapes returned by the dashboard-api. Kept in one place so pages and hooks agree.

export type Action = "BUY" | "SELL" | "CLOSE";

export interface FeatureRow {
  ts: string;
  token: string;
  obi: number;
  microprice: number;
  mid_price: number;
  spread: number;
  spread_bps: number;
}

export interface SignalRow {
  ts: string;
  strategy: string;
  token: string;
  action: Action;
  price: number;
  confidence: number;
  reason: string;
}

export interface OrderBookRow {
  ts: string;
  token: string;
  best_bid_price: number;
  best_bid_volume: number;
  best_ask_price: number;
  best_ask_volume: number;
  bid_depth_l5: number;
  ask_depth_l5: number;
}

export interface StrategyPnlRow {
  ts: string;
  strategy: string;
  realized_pnl: number;
  unrealized_pnl: number;
  total_pnl: number;
  num_trades: number;
  win_rate: number;
}

export interface PositionRow {
  strategy: string;
  token: string;
  net_position: number;
  avg_entry_price: number;
  realized_pnl: number;
  unrealized_pnl: number;
  ts: string;
}

export interface ConsensusRow {
  token: string;
  longs: string[];
  shorts: string[];
  conflict: boolean;
}

export interface CandleRow {
  ts: string;
  open: number;
  high: number;
  low: number;
  close: number;
}

// WebSocket envelope emitted by live_feed.py
export type LiveEnvelope =
  | { kind: "feature"; data: FeatureRow; lagging?: number }
  | { kind: "signal"; data: SignalRow; lagging?: number };
