import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type TokenDetail } from '@/lib/api';
import { MarketTable } from '@/components/market/MarketTable';
import { CandlestickChart } from '@/components/charts/CandlestickChart';
import { SignalFeed } from '@/components/signals/SignalFeed';
import { RefreshCw } from 'lucide-react';

export function MarketDashboard() {
  const [selectedSymbol, setSelectedSymbol] = useState<string>('AAPL');
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Fetch all tokens with details
  const {
    data: tokensData,
    isLoading: tokensLoading,
    refetch: refetchTokens,
  } = useQuery({
    queryKey: ['tokens'],
    queryFn: async () => {
      const { tokens } = await api.getTokens();
      // Fetch details for each token
      const detailsPromises = tokens.map((token) =>
        api.getTokenDetail(token.symbol)
      );
      return Promise.all(detailsPromises);
    },
    refetchInterval: 3000, // Poll every 3 seconds
  });

  // Fetch candles for selected symbol
  const { data: candlesData, isLoading: candlesLoading } = useQuery({
    queryKey: ['candles', selectedSymbol],
    queryFn: () => api.getCandles(selectedSymbol, 500),
    enabled: !!selectedSymbol,
  });

  // Fetch recent signals
  const { data: signalsData, isLoading: signalsLoading } = useQuery({
    queryKey: ['signals'],
    queryFn: () => api.getRecentSignals(8),
    refetchInterval: 5000, // Poll every 5 seconds
  });

  const handleRefresh = async () => {
    setIsRefreshing(true);
    await Promise.all([refetchTokens()]);
    setTimeout(() => setIsRefreshing(false), 500);
  };

  useEffect(() => {
    if (tokensData && tokensData.length > 0 && !selectedSymbol) {
      setSelectedSymbol(tokensData[0].symbol);
    }
  }, [tokensData, selectedSymbol]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-foreground">
            Market Dashboard
          </h1>
          <p className="text-sm text-muted-foreground">
            Real-time market data and trading signals
          </p>
        </div>

        <button
          onClick={handleRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 rounded-lg bg-primary/10 px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/20 disabled:opacity-50"
        >
          <RefreshCw className={`h-4 w-4 ${isRefreshing ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {/* Market Table */}
      <div>
        <h2 className="mb-3 text-lg font-semibold text-foreground">Market Overview</h2>
        {tokensLoading ? (
          <div className="flex h-48 items-center justify-center rounded-lg border border-border bg-card">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <RefreshCw className="h-4 w-4 animate-spin" />
              Loading market data...
            </div>
          </div>
        ) : tokensData && tokensData.length > 0 ? (
          <MarketTable
            tokens={tokensData}
            onSymbolClick={setSelectedSymbol}
            selectedSymbol={selectedSymbol}
          />
        ) : (
          <div className="flex h-48 items-center justify-center rounded-lg border border-border bg-card text-sm text-muted-foreground">
            No market data available
          </div>
        )}
      </div>

      {/* Price Chart */}
      <div>
        <h2 className="mb-3 text-lg font-semibold text-foreground">
          Price Chart - {selectedSymbol}
        </h2>
        {candlesLoading ? (
          <div className="flex h-[500px] items-center justify-center rounded-lg border border-border bg-card">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <RefreshCw className="h-4 w-4 animate-spin" />
              Loading chart data...
            </div>
          </div>
        ) : candlesData && candlesData.candles.length > 0 ? (
          <div className="overflow-hidden rounded-lg border border-border bg-card p-4">
            <CandlestickChart
              data={candlesData.candles}
              symbol={selectedSymbol}
            />
          </div>
        ) : (
          <div className="flex h-[500px] items-center justify-center rounded-lg border border-border bg-card text-sm text-muted-foreground">
            No chart data available for {selectedSymbol}
          </div>
        )}
      </div>

      {/* Recent Signals */}
      <div>
        <h2 className="mb-3 text-lg font-semibold text-foreground">Recent Signals</h2>
        {signalsLoading ? (
          <div className="flex h-48 items-center justify-center rounded-lg border border-border bg-card">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <RefreshCw className="h-4 w-4 animate-spin" />
              Loading signals...
            </div>
          </div>
        ) : signalsData ? (
          <SignalFeed signals={signalsData.signals} />
        ) : (
          <div className="flex h-48 items-center justify-center rounded-lg border border-border bg-card text-sm text-muted-foreground">
            No signals available
          </div>
        )}
      </div>
    </div>
  );
}
