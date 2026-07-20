import type { Signal } from '@/lib/api';
import { formatTimestamp, getActionBadgeClass, cn } from '@/lib/utils';
import { Activity } from 'lucide-react';

interface SignalFeedProps {
  signals: Signal[];
}

export function SignalFeed({ signals }: SignalFeedProps) {
  if (signals.length === 0) {
    return (
      <div className="flex h-48 flex-col items-center justify-center rounded-lg border border-border bg-card p-8 text-center">
        <Activity className="mb-3 h-12 w-12 text-muted-foreground/50" />
        <p className="text-sm text-muted-foreground">No recent signals</p>
      </div>
    );
  }

  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
      {signals.map((signal, idx) => (
        <div
          key={`${signal.timestamp}-${signal.symbol}-${idx}`}
          className="group relative overflow-hidden rounded-lg border border-border bg-card p-4 transition-all hover:border-primary/50 hover:shadow-lg"
        >
          <div className="mb-3 flex items-start justify-between">
            <div className="flex items-center gap-2">
              <span className="text-lg font-bold text-foreground">{signal.symbol}</span>
              <span
                className={cn(
                  'rounded border px-2 py-0.5 text-xs font-semibold',
                  getActionBadgeClass(signal.action)
                )}
              >
                {signal.action}
              </span>
            </div>
          </div>

          <div className="space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="text-muted-foreground">Strategy</span>
              <span className="font-medium text-foreground">{signal.strategy_name}</span>
            </div>

            <div className="flex justify-between">
              <span className="text-muted-foreground">Confidence</span>
              <span className="font-mono font-medium text-foreground">
                {(signal.confidence * 100).toFixed(1)}%
              </span>
            </div>

            <div className="pt-2 text-xs text-muted-foreground">
              {formatTimestamp(signal.timestamp)}
            </div>
          </div>

          {/* Confidence bar */}
          <div className="absolute bottom-0 left-0 h-1 bg-secondary">
            <div
              className={cn(
                'h-full transition-all',
                signal.action === 'BUY' && 'bg-green-500',
                signal.action === 'SELL' && 'bg-red-500',
                signal.action === 'HOLD' && 'bg-yellow-500'
              )}
              style={{ width: `${signal.confidence * 100}%` }}
            />
          </div>
        </div>
      ))}
    </div>
  );
}
