import { useState } from 'react';
import type { TokenDetail } from '@/lib/api';
import { formatPrice, formatVolume, formatPercent, getChangeColor, cn } from '@/lib/utils';
import { TrendingUp, TrendingDown } from 'lucide-react';

interface MarketTableProps {
  tokens: TokenDetail[];
  onSymbolClick: (symbol: string) => void;
  selectedSymbol?: string;
}

export function MarketTable({ tokens, onSymbolClick, selectedSymbol }: MarketTableProps) {
  const [sortField, setSortField] = useState<keyof TokenDetail>('symbol');
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('asc');

  const handleSort = (field: keyof TokenDetail) => {
    if (sortField === field) {
      setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDirection('desc');
    }
  };

  const sortedTokens = [...tokens].sort((a, b) => {
    const aVal = a[sortField];
    const bVal = b[sortField];

    if (typeof aVal === 'number' && typeof bVal === 'number') {
      return sortDirection === 'asc' ? aVal - bVal : bVal - aVal;
    }

    if (typeof aVal === 'string' && typeof bVal === 'string') {
      return sortDirection === 'asc'
        ? aVal.localeCompare(bVal)
        : bVal.localeCompare(aVal);
    }

    return 0;
  });

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-card">
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-border bg-secondary/50">
              <th
                className="cursor-pointer px-4 py-3 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground"
                onClick={() => handleSort('symbol')}
              >
                Symbol
              </th>
              <th
                className="cursor-pointer px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground"
                onClick={() => handleSort('current_price')}
              >
                Price
              </th>
              <th
                className="cursor-pointer px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground"
                onClick={() => handleSort('change_24h_pct')}
              >
                24h Change
              </th>
              <th
                className="cursor-pointer px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground"
                onClick={() => handleSort('high_24h')}
              >
                24h High
              </th>
              <th
                className="cursor-pointer px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground"
                onClick={() => handleSort('low_24h')}
              >
                24h Low
              </th>
              <th
                className="cursor-pointer px-4 py-3 text-right text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground"
                onClick={() => handleSort('volume_24h')}
              >
                24h Volume
              </th>
            </tr>
          </thead>
          <tbody>
            {sortedTokens.map((token) => (
              <tr
                key={token.symbol}
                onClick={() => onSymbolClick(token.symbol)}
                className={cn(
                  'cursor-pointer border-b border-border/50 transition-colors hover:bg-secondary/50',
                  selectedSymbol === token.symbol && 'bg-primary/5'
                )}
              >
                <td className="px-4 py-3">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-foreground">{token.symbol}</span>
                  </div>
                </td>
                <td className="px-4 py-3 text-right font-mono text-sm text-foreground tabular-nums">
                  ${formatPrice(token.current_price)}
                </td>
                <td className="px-4 py-3 text-right">
                  <div className="flex items-center justify-end gap-1">
                    {token.change_24h_pct > 0 ? (
                      <TrendingUp className="h-4 w-4 text-up" />
                    ) : token.change_24h_pct < 0 ? (
                      <TrendingDown className="h-4 w-4 text-down" />
                    ) : null}
                    <span className={cn('font-mono text-sm tabular-nums', getChangeColor(token.change_24h_pct))}>
                      {formatPercent(token.change_24h_pct)}
                    </span>
                  </div>
                </td>
                <td className="px-4 py-3 text-right font-mono text-sm text-muted-foreground tabular-nums">
                  ${formatPrice(token.high_24h)}
                </td>
                <td className="px-4 py-3 text-right font-mono text-sm text-muted-foreground tabular-nums">
                  ${formatPrice(token.low_24h)}
                </td>
                <td className="px-4 py-3 text-right font-mono text-sm text-muted-foreground tabular-nums">
                  {formatVolume(token.volume_24h)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
