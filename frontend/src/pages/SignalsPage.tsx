import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { SignalFeed } from '@/components/signals/SignalFeed';
import { RefreshCw, Filter, TrendingUp, TrendingDown, Activity } from 'lucide-react';
import { cn } from '@/lib/utils';

type ActionFilter = 'ALL' | 'BUY' | 'SELL' | 'HOLD';

export function SignalsPage() {
  const [actionFilter, setActionFilter] = useState<ActionFilter>('ALL');
  const [limit, setLimit] = useState(20);

  const { data: signalsData, isLoading, refetch } = useQuery({
    queryKey: ['signals', actionFilter, limit],
    queryFn: () =>
      api.getRecentSignals(
        limit,
        actionFilter === 'ALL' ? undefined : actionFilter,
        undefined
      ),
    refetchInterval: 5000,
  });

  const { data: statsData } = useQuery({
    queryKey: ['signal-statistics'],
    queryFn: () => api.getSignalStatistics(24),
    refetchInterval: 10000,
  });

  const handleRefresh = () => {
    refetch();
  };

  const filters: { label: string; value: ActionFilter; icon: any; color: string }[] = [
    { label: 'All', value: 'ALL', icon: Activity, color: 'text-foreground' },
    { label: 'Buy', value: 'BUY', icon: TrendingUp, color: 'text-up' },
    { label: 'Sell', value: 'SELL', icon: TrendingDown, color: 'text-down' },
    { label: 'Hold', value: 'HOLD', icon: Activity, color: 'text-yellow-500' },
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-foreground">
            Trading Signals
          </h1>
          <p className="text-sm text-muted-foreground">
            Real-time signals from all active strategies
          </p>
        </div>

        <button
          onClick={handleRefresh}
          className="flex items-center gap-2 rounded-lg bg-primary/10 px-4 py-2 text-sm font-medium text-primary transition-colors hover:bg-primary/20"
        >
          <RefreshCw className="h-4 w-4" />
          Refresh
        </button>
      </div>

      {/* Statistics Cards */}
      {statsData && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-muted-foreground">
              <Activity className="h-4 w-4" />
              <span className="text-sm">Total Signals</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-foreground">
              {statsData.statistics.total_signals}
            </p>
            <p className="text-xs text-muted-foreground">Last 24 hours</p>
          </div>

          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-up">
              <TrendingUp className="h-4 w-4" />
              <span className="text-sm">Buy Signals</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-up">
              {statsData.statistics.by_action.BUY || 0}
            </p>
            <p className="text-xs text-muted-foreground">
              {statsData.statistics.total_signals > 0
                ? `${Math.round(((statsData.statistics.by_action.BUY || 0) / statsData.statistics.total_signals) * 100)}%`
                : '0%'}
            </p>
          </div>

          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-down">
              <TrendingDown className="h-4 w-4" />
              <span className="text-sm">Sell Signals</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-down">
              {statsData.statistics.by_action.SELL || 0}
            </p>
            <p className="text-xs text-muted-foreground">
              {statsData.statistics.total_signals > 0
                ? `${Math.round(((statsData.statistics.by_action.SELL || 0) / statsData.statistics.total_signals) * 100)}%`
                : '0%'}
            </p>
          </div>

          <div className="rounded-lg border border-border bg-card p-4">
            <div className="flex items-center gap-2 text-yellow-500">
              <Activity className="h-4 w-4" />
              <span className="text-sm">Hold Signals</span>
            </div>
            <p className="mt-2 text-2xl font-bold text-yellow-500">
              {statsData.statistics.by_action.HOLD || 0}
            </p>
            <p className="text-xs text-muted-foreground">
              {statsData.statistics.total_signals > 0
                ? `${Math.round(((statsData.statistics.by_action.HOLD || 0) / statsData.statistics.total_signals) * 100)}%`
                : '0%'}
            </p>
          </div>
        </div>
      )}

      {/* Filters */}
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Filter className="h-4 w-4" />
          <span>Filter by action:</span>
        </div>

        <div className="flex gap-2">
          {filters.map((filter) => {
            const Icon = filter.icon;
            return (
              <button
                key={filter.value}
                onClick={() => setActionFilter(filter.value)}
                className={cn(
                  'flex items-center gap-2 rounded-lg border px-4 py-2 text-sm font-medium transition-colors',
                  actionFilter === filter.value
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border bg-card text-muted-foreground hover:bg-secondary'
                )}
              >
                <Icon className={cn('h-4 w-4', filter.color)} />
                {filter.label}
              </button>
            );
          })}
        </div>

        <div className="ml-auto flex items-center gap-2">
          <span className="text-sm text-muted-foreground">Show:</span>
          <select
            value={limit}
            onChange={(e) => setLimit(Number(e.target.value))}
            className="rounded-lg border border-border bg-card px-3 py-2 text-sm text-foreground"
          >
            <option value={10}>10</option>
            <option value={20}>20</option>
            <option value={50}>50</option>
            <option value={100}>100</option>
          </select>
        </div>
      </div>

      {/* Signals Feed */}
      <div>
        {isLoading ? (
          <div className="flex h-48 items-center justify-center rounded-lg border border-border bg-card">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <RefreshCw className="h-4 w-4 animate-spin" />
              Loading signals...
            </div>
          </div>
        ) : signalsData ? (
          <>
            <SignalFeed signals={signalsData.signals} />
            <div className="mt-4 text-center text-sm text-muted-foreground">
              Showing {signalsData.count} signal{signalsData.count !== 1 ? 's' : ''}
              {actionFilter !== 'ALL' && ` (${actionFilter} only)`}
            </div>
          </>
        ) : (
          <div className="flex h-48 items-center justify-center rounded-lg border border-border bg-card text-sm text-muted-foreground">
            No signals available
          </div>
        )}
      </div>
    </div>
  );
}
