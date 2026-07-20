import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '@/lib/api';
import { Zap, TrendingUp, RefreshCw, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';

export function StrategiesList() {
  const { data: strategiesData, isLoading } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => api.getStrategies(),
  });

  const getStrategyIcon = (type: string) => {
    switch (type) {
      case 'technical':
        return <TrendingUp className="h-5 w-5" />;
      case 'statistical':
        return <Zap className="h-5 w-5" />;
      default:
        return <Zap className="h-5 w-5" />;
    }
  };

  const getTypeColor = (type: string) => {
    switch (type) {
      case 'technical':
        return 'bg-blue-500/10 text-blue-500 border-blue-500/20';
      case 'statistical':
        return 'bg-purple-500/10 text-purple-500 border-purple-500/20';
      default:
        return 'bg-gray-500/10 text-gray-500 border-gray-500/20';
    }
  };

  if (isLoading) {
    return (
      <div className="flex h-96 items-center justify-center">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <RefreshCw className="h-4 w-4 animate-spin" />
          Loading strategies...
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold tracking-tight text-foreground">
          Trading Strategies
        </h1>
        <p className="text-sm text-muted-foreground">
          Browse and analyze all available trading strategies
        </p>
      </div>

      {/* Stats */}
      <div className="grid gap-4 sm:grid-cols-3">
        <div className="rounded-lg border border-border bg-card p-4">
          <p className="text-sm text-muted-foreground">Total Strategies</p>
          <p className="mt-1 text-2xl font-bold text-foreground">
            {strategiesData?.total || 0}
          </p>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <p className="text-sm text-muted-foreground">Active</p>
          <p className="mt-1 text-2xl font-bold text-green-500">
            {strategiesData?.strategies.filter((s) => s.active).length || 0}
          </p>
        </div>
        <div className="rounded-lg border border-border bg-card p-4">
          <p className="text-sm text-muted-foreground">Types</p>
          <p className="mt-1 text-2xl font-bold text-foreground">
            {new Set(strategiesData?.strategies.map((s) => s.type)).size || 0}
          </p>
        </div>
      </div>

      {/* Strategy Grid */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {strategiesData?.strategies.map((strategy) => (
          <Link
            key={strategy.name}
            to={`/strategies/${strategy.name}`}
            className="group relative overflow-hidden rounded-lg border border-border bg-card p-6 transition-all hover:border-primary/50 hover:shadow-lg"
          >
            <div className="mb-4 flex items-start justify-between">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  {getStrategyIcon(strategy.type)}
                </div>
                <div>
                  <h3 className="font-semibold text-foreground">
                    {strategy.display_name}
                  </h3>
                  <span
                    className={cn(
                      'mt-1 inline-block rounded border px-2 py-0.5 text-xs font-medium capitalize',
                      getTypeColor(strategy.type)
                    )}
                  >
                    {strategy.type}
                  </span>
                </div>
              </div>

              <ChevronRight className="h-5 w-5 text-muted-foreground transition-transform group-hover:translate-x-1" />
            </div>

            <p className="mb-4 text-sm text-muted-foreground line-clamp-2">
              {strategy.description}
            </p>

            <div className="flex items-center justify-between text-xs">
              <span
                className={cn(
                  'rounded-full px-2 py-1',
                  strategy.active
                    ? 'bg-green-500/10 text-green-500'
                    : 'bg-gray-500/10 text-gray-500'
                )}
              >
                {strategy.active ? 'Active' : 'Inactive'}
              </span>

              <span className="text-muted-foreground">
                {Object.keys(strategy.parameters).length} parameters
              </span>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
