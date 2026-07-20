import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import { format } from "date-fns";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatNumber(value: number, decimals: number = 2): string {
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(value);
}

export function formatPrice(value: number): string {
  if (value >= 1000) {
    return formatNumber(value, 2);
  }
  if (value >= 1) {
    return formatNumber(value, 3);
  }
  return formatNumber(value, 6);
}

export function formatVolume(value: number): string {
  if (value >= 1_000_000_000) {
    return `${formatNumber(value / 1_000_000_000, 2)}B`;
  }
  if (value >= 1_000_000) {
    return `${formatNumber(value / 1_000_000, 2)}M`;
  }
  if (value >= 1_000) {
    return `${formatNumber(value / 1_000, 2)}K`;
  }
  return formatNumber(value, 0);
}

export function formatPercent(value: number, showSign: boolean = true): string {
  const formatted = formatNumber(Math.abs(value), 2);
  if (showSign && value !== 0) {
    return `${value > 0 ? '+' : '-'}${formatted}%`;
  }
  return `${formatted}%`;
}

export function formatTimestamp(timestamp: string): string {
  try {
    return format(new Date(timestamp), 'MMM dd, HH:mm:ss');
  } catch {
    return timestamp;
  }
}

export function getChangeColor(value: number): string {
  if (value > 0) return 'text-up';
  if (value < 0) return 'text-down';
  return 'text-muted-foreground';
}

export function getChangeBgColor(value: number): string {
  if (value > 0) return 'bg-up';
  if (value < 0) return 'bg-down';
  return 'bg-muted';
}

export function getActionColor(action: 'BUY' | 'SELL' | 'HOLD'): string {
  switch (action) {
    case 'BUY':
      return 'text-up';
    case 'SELL':
      return 'text-down';
    case 'HOLD':
      return 'text-yellow-500';
  }
}

export function getActionBadgeClass(action: 'BUY' | 'SELL' | 'HOLD'): string {
  switch (action) {
    case 'BUY':
      return 'bg-green-500/10 text-green-500 border-green-500/20';
    case 'SELL':
      return 'bg-red-500/10 text-red-500 border-red-500/20';
    case 'HOLD':
      return 'bg-yellow-500/10 text-yellow-500 border-yellow-500/20';
  }
}
