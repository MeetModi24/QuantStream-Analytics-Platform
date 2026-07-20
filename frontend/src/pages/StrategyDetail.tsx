import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { SignalFeed } from '@/components/signals/SignalFeed';
import { ArrowLeft, RefreshCw, TrendingUp, Activity } from 'lucide-react';
import { formatNumber } from '@/lib/utils';

export function StrategyDetail() {
  const { name } = useParams<{ name: string }>();

  const { data: strategy, isLoading: strategyLoading } = useQuery({
    queryKey: ['strategy', name],
    queryFn: () => api.getStrategyDetail(name!),
    enabled: !!name,
  });

  const { data: signalsData, isLoading: signalsLoading } = useQuery({
    queryKey: ['strategy-signals', name],
    queryFn: () => api.getStrategySignals(name!, undefined, 12),
    enabled: !!name,
    refetchInterval: 5000,
  });

  if (strategyLoading) {
    return (
      <div className="flex h-96 items-center justify-center">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <RefreshCw className="h-4 w-4 animate-spin" />
          Loading strategy...
        </div>
      </div>
    );
  }

  if (!strategy) {
    return (
      <div className="flex h-96 flex-col items-center justify-center gap-4">
        <p className="text-muted-foreground">Strategy not found</p>
        <Link
          to="/strategies"
          className="flex items-center gap-2 text-sm text-primary hover:underline"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to strategies
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <Link
          to="/strategies"
          className="mb-4 inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to strategies
        </Link>

        <div className="flex items-start justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight text-foreground">
              {strategy.display_name}
            </h1>
            <p className="mt-2 text-muted-foreground">{strategy.description}</p>
          </div>

          <div className="flex items-center gap-2">
            <span
              className={`rounded-full px-3 py-1 text-sm font-medium ${
                strategy.active
                  ? 'bg-green-500/10 text-green-500'
                  : 'bg-gray-500/10 text-gray-500'
              }`}
            >
              {strategy.active ? 'Active' : 'Inactive'}
            </span>
            <span className="rounded-full border border-border bg-secondary px-3 py-1 text-sm font-medium capitalize text-foreground">
              {strategy.type}
            </span>
          </div>
        </div>
      </div>

      {/* 24h Statistics */}
      {strategy.statistics && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-muted-foreground">
              <Activity className="h-4 w-4" />
              <span className="text-sm">Total Signals</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-foreground">
              {strategy.statistics.total_signals_24h}
            </p>
            <p className="text-xs text-muted-foreground">Last 24 hours</p>
          </div>

          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-up">
              <TrendingUp className="h-4 w-4" />
              <span className="text-sm">Buy Signals</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-up">
              {strategy.statistics.buy_signals_24h}
            </p>
            <p className="text-xs text-muted-foreground">
              {strategy.statistics.total_signals_24h > 0
                ? `${Math.round((strategy.statistics.buy_signals_24h / strategy.statistics.total_signals_24h) * 100)}%`
                : '0%'}
            </p>
          </div>

          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-down">
              <TrendingUp className="h-4 w-4 rotate-180" />
              <span className="text-sm">Sell Signals</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-down">
              {strategy.statistics.sell_signals_24h}
            </p>
            <p className="text-xs text-muted-foreground">
              {strategy.statistics.total_signals_24h > 0
                ? `${Math.round((strategy.statistics.sell_signals_24h / strategy.statistics.total_signals_24h) * 100)}%`
                : '0%'}
            </p>
          </div>

          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-yellow-500">
              <Activity className="h-4 w-4" />
              <span className="text-sm">Hold Signals</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-yellow-500">
              {strategy.statistics.hold_signals_24h}
            </p>
            <p className="text-xs text-muted-foreground">
              {strategy.statistics.total_signals_24h > 0
                ? `${Math.round((strategy.statistics.hold_signals_24h / strategy.statistics.total_signals_24h) * 100)}%`
                : '0%'}
            </p>
          </div>

          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-muted-foreground">
              <TrendingUp className="h-4 w-4" />
              <span className="text-sm">Avg Confidence</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-foreground">
              {formatNumber(strategy.statistics.avg_confidence * 100, 1)}%
            </p>
            <p className="text-xs text-muted-foreground">Confidence score</p>
          </div>
        </div>
      )}

      {/* Parameters */}
      <div>
        <h2 className="mb-3 text-lg font-semibold text-foreground">Parameters</h2>
        <div className="rounded-lg border border-border bg-card p-6">
          <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {Object.entries(strategy.parameters).map(([key, value]) => (
              <div key={key}>
                <dt className="text-sm font-medium text-muted-foreground capitalize">
                  {key.replace(/_/g, ' ')}
                </dt>
                <dd className="mt-1 text-lg font-semibold text-foreground">
                  {typeof value === 'number' ? formatNumber(value, 2) : String(value)}
                </dd>
              </div>
            ))}
          </dl>
        </div>
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
