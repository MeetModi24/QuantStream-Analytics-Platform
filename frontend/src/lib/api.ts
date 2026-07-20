/**
 * API Client for QuantStream Backend
 *
 * Base URL: http://localhost:8085/api/v1
 */

const API_BASE_URL = 'http://localhost:8085/api/v1';

export interface Token {
  symbol: string;
  current_price: number;
  volume: number;
  last_updated: string | null;
}

export interface TokenDetail {
  symbol: string;
  current_price: number;
  change_24h_pct: number;
  high_24h: number;
  low_24h: number;
  volume_24h: number;
  open_24h: number;
  last_updated: string | null;
}

export interface Candle {
  timestamp: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface Signal {
  timestamp: string;
  symbol: string;
  strategy_name: string;
  action: 'BUY' | 'SELL' | 'HOLD';
  price: number;
  confidence: number;
}

export interface Strategy {
  name: string;
  display_name: string;
  type: string;
  description: string;
  parameters: Record<string, any>;
  active: boolean;
}

export interface StrategyDetail extends Strategy {
  statistics?: {
    total_signals_24h: number;
    buy_signals_24h: number;
    sell_signals_24h: number;
    hold_signals_24h: number;
    avg_confidence: number;
  };
}

export interface SignalStatistics {
  period_hours: number;
  total_signals: number;
  by_action: Record<string, number>;
  by_strategy: Record<string, number>;
  by_symbol: Record<string, number>;
  avg_confidence: number;
}

class APIClient {
  private baseURL: string;

  constructor(baseURL: string = API_BASE_URL) {
    this.baseURL = baseURL;
  }

  private async fetch<T>(endpoint: string): Promise<T> {
    const response = await fetch(`${this.baseURL}${endpoint}`);
    if (!response.ok) {
      throw new Error(`API Error: ${response.statusText}`);
    }
    return response.json();
  }

  // Market Data
  async getTokens(): Promise<{ tokens: Token[]; total: number; last_update: string }> {
    return this.fetch('/tokens');
  }

  async getTokenDetail(symbol: string): Promise<TokenDetail> {
    return this.fetch(`/tokens/${symbol}`);
  }

  async getCandles(
    symbol: string,
    limit: number = 1000
  ): Promise<{ symbol: string; interval: string; candles: Candle[]; count: number }> {
    return this.fetch(`/tokens/${symbol}/candles?limit=${limit}`);
  }

  async getLatestTick(symbol: string): Promise<{ symbol: string; price: number; volume: number; timestamp: string }> {
    return this.fetch(`/tokens/${symbol}/tick`);
  }

  // Strategies
  async getStrategies(): Promise<{ strategies: Strategy[]; total: number }> {
    return this.fetch('/strategies');
  }

  async getStrategyDetail(name: string): Promise<StrategyDetail> {
    return this.fetch(`/strategies/${name}`);
  }

  async getStrategySignals(
    name: string,
    symbol?: string,
    limit: number = 100
  ): Promise<{ signals: Signal[]; count: number; last_updated: string }> {
    const params = new URLSearchParams({ limit: limit.toString() });
    if (symbol) params.append('symbol', symbol);
    return this.fetch(`/strategies/${name}/signals?${params}`);
  }

  // Signals
  async getRecentSignals(
    limit: number = 50,
    action?: 'BUY' | 'SELL' | 'HOLD',
    symbol?: string
  ): Promise<{ signals: Signal[]; count: number; last_updated: string }> {
    const params = new URLSearchParams({ limit: limit.toString() });
    if (action) params.append('action', action);
    if (symbol) params.append('symbol', symbol);
    return this.fetch(`/signals/recent?${params}`);
  }

  async getSignalStatistics(
    period_hours: number = 24
  ): Promise<{ statistics: SignalStatistics }> {
    return this.fetch(`/signals/statistics?period_hours=${period_hours}`);
  }

  // Health
  async getHealth(): Promise<{ status: string; timestamp: string; services: Record<string, string> }> {
    return this.fetch('/health');
  }
}

export const api = new APIClient();
