import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';
import { Trophy, TrendingUp, Target, AlertCircle, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';

export function LeaderboardPage() {
  const { data: strategiesData } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => api.getStrategies(),
  });

  // Get all strategies with their 24h stats
  const { data: strategyDetails, isLoading } = useQuery({
    queryKey: ['all-strategy-details'],
    queryFn: async () => {
      if (!strategiesData?.strategies) return [];

      const detailsPromises = strategiesData.strategies.map((strategy) =>
        api.getStrategyDetail(strategy.name)
      );

      return Promise.all(detailsPromises);
    },
    enabled: !!strategiesData?.strategies,
  });

  // Sort by total signals (since we don't have backtest results yet)
  const rankedStrategies = strategyDetails
    ?.filter((s) => s.statistics)
    .sort((a, b) => {
      const aSignals = a.statistics?.total_signals_24h || 0;
      const bSignals = b.statistics?.total_signals_24h || 0;
      return bSignals - aSignals;
    })
    .map((strategy, index) => ({
      ...strategy,
      rank: index + 1,
    }));

  const getRankBadge = (rank: number) => {
    switch (rank) {
      case 1:
        return 'bg-yellow-500/10 text-yellow-500 border-yellow-500/20';
      case 2:
        return 'bg-gray-400/10 text-gray-400 border-gray-400/20';
      case 3:
        return 'bg-orange-500/10 text-orange-500 border-orange-500/20';
      default:
        return 'bg-secondary text-muted-foreground border-border';
    }
  };

  const getRankIcon = (rank: number) => {
    if (rank === 1) return '🥇';
    if (rank === 2) return '🥈';
    if (rank === 3) return '🥉';
    return rank;
  };

  if (isLoading) {
    return (
      <div className="flex h-96 items-center justify-center">
        <div className="text-sm text-muted-foreground">Loading leaderboard...</div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <div className="flex items-center gap-3">
          <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-yellow-500/10">
            <Trophy className="h-6 w-6 text-yellow-500" />
          </div>
          <div>
            <h1 className="text-3xl font-bold tracking-tight text-foreground">
              Strategy Leaderboard
            </h1>
            <p className="text-sm text-muted-foreground">
              Ranked by 24-hour signal activity
            </p>
          </div>
        </div>
      </div>

      {/* Note about performance metrics */}
      <div className="rounded-lg border border-blue-500/20 bg-blue-500/5 p-4">
        <div className="flex gap-3">
          <AlertCircle className="h-5 w-5 flex-shrink-0 text-blue-500" />
          <div className="space-y-1">
            <p className="text-sm font-medium text-foreground">
              Performance Metrics Coming Soon
            </p>
            <p className="text-sm text-muted-foreground">
              Currently ranked by signal activity. Full performance metrics (P&L, Sharpe ratio, win rate)
              will be available once backtest result aggregation is implemented.
            </p>
          </div>
        </div>
      </div>

      {/* Summary Stats */}
      <div className="grid gap-4 sm:grid-cols-3">
        <div className="rounded-lg border border-border bg-card p-4">
          <div className="flex items-center gap-2 text-muted-foreground">
            <Target className="h-4 w-4" />
            <span className="text-sm">Total Strategies</span>
          </div>
          <p className="mt-2 text-2xl font-bold text-foreground">
            {strategiesData?.total || 0}
          </p>
        </div>

        <div className="rounded-lg border border-border bg-card p-4">
          <div className="flex items-center gap-2 text-green-500">
            <TrendingUp className="h-4 w-4" />
            <span className="text-sm">Active Strategies</span>
          </div>
          <p className="mt-2 text-2xl font-bold text-green-500">
            {strategiesData?.strategies.filter((s) => s.active).length || 0}
          </p>
        </div>

        <div className="rounded-lg border border-border bg-card p-4">
          <div className="flex items-center gap-2 text-muted-foreground">
            <Trophy className="h-4 w-4" />
            <span className="text-sm">Most Active</span>
          </div>
          <p className="mt-2 text-lg font-bold text-foreground">
            {rankedStrategies?.[0]?.display_name || 'N/A'}
          </p>
        </div>
      </div>

      {/* Leaderboard Table */}
      <div className="overflow-hidden rounded-lg border border-border bg-card">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-border bg-secondary/50">
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Rank
                </th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Strategy
                </th>
                <th className="px-4 py-3 text-center text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Type
                </th>
                <th className="px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  24h Signals
                </th>
                <th className="px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Buy
                </th>
                <th className="px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Sell
                </th>
                <th className="px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Hold
                </th>
                <th className="px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Avg Confidence
                </th>
                <th className="px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Status
                </th>
                <th className="px-4 py-3"></th>
              </tr>
            </thead>
            <tbody>
              {rankedStrategies?.map((strategy) => (
                <tr
                  key={strategy.name}
                  className="border-b border-border/50 transition-colors hover:bg-secondary/50"
                >
                  <td className="px-4 py-4">
                    <div
                      className={cn(
                        'flex h-8 w-8 items-center justify-center rounded-lg border text-sm font-bold',
                        getRankBadge(strategy.rank)
                      )}
                    >
                      {getRankIcon(strategy.rank)}
                    </div>
                  </td>
                  <td className="px-4 py-4">
                    <div>
                      <p className="font-semibold text-foreground">
                        {strategy.display_name}
                      </p>
                      <p className="text-xs text-muted-foreground line-clamp-1">
                        {strategy.description}
                      </p>
                    </div>
                  </td>
                  <td className="px-4 py-4 text-center">
                    <span className="inline-block rounded-full border border-border bg-secondary px-2 py-1 text-xs font-medium capitalize text-foreground">
                      {strategy.type}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-right">
                    <span className="font-mono text-sm font-semibold text-foreground tabular-nums">
                      {strategy.statistics?.total_signals_24h || 0}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-right">
                    <span className="font-mono text-sm text-up tabular-nums">
                      {strategy.statistics?.buy_signals_24h || 0}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-right">
                    <span className="font-mono text-sm text-down tabular-nums">
                      {strategy.statistics?.sell_signals_24h || 0}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-right">
                    <span className="font-mono text-sm text-yellow-500 tabular-nums">
                      {strategy.statistics?.hold_signals_24h || 0}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-right">
                    <span className="font-mono text-sm text-muted-foreground tabular-nums">
                      {strategy.statistics?.avg_confidence
                        ? `${(strategy.statistics.avg_confidence * 100).toFixed(1)}%`
                        : 'N/A'}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-right">
                    <span
                      className={cn(
                        'inline-block rounded-full px-2 py-1 text-xs font-medium',
                        strategy.active
                          ? 'bg-green-500/10 text-green-500'
                          : 'bg-gray-500/10 text-gray-500'
                      )}
                    >
                      {strategy.active ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td className="px-4 py-4 text-right">
                    <Link
                      to={`/strategies/${strategy.name}`}
                      className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
                    >
                      View
                      <ChevronRight className="h-4 w-4" />
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Footer Note */}
      <div className="text-center text-sm text-muted-foreground">
        Rankings update every 24 hours based on signal activity
      </div>
    </div>
  );
}
