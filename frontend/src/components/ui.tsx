import clsx from "clsx";
import type { ReactNode } from "react";
import type { Action } from "@/lib/types";

export function Card({
  title,
  right,
  children,
  className,
  bodyClassName,
}: {
  title?: ReactNode;
  right?: ReactNode;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section className={clsx("card overflow-hidden", className)}>
      {title != null && (
        <header className="flex items-center justify-between px-4 py-3 border-b border-border">
          <h2 className="card-title">{title}</h2>
          {right}
        </header>
      )}
      <div className={clsx("p-4", bodyClassName)}>{children}</div>
    </section>
  );
}

/** Small KPI stat: label, big value, optional secondary line. */
export function Stat({
  label,
  value,
  sub,
  valueClassName,
}: {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  valueClassName?: string;
}) {
  return (
    <div className="card p-4">
      <div className="label">{label}</div>
      <div className={clsx("kpi-value mt-1", valueClassName)}>{value}</div>
      {sub != null && <div className="mt-1 text-xs text-text-secondary tnum">{sub}</div>}
    </div>
  );
}

const ACTION_STYLES: Record<Action, string> = {
  BUY: "bg-buy/15 text-buy border-buy/30",
  SELL: "bg-sell/15 text-sell border-sell/30",
  CLOSE: "bg-hold/15 text-hold border-hold/30",
};

export function ActionBadge({ action }: { action: Action }) {
  return (
    <span
      className={clsx(
        "inline-flex items-center rounded px-1.5 py-0.5 text-2xs font-semibold border tabular-nums",
        ACTION_STYLES[action],
      )}
    >
      {action}
    </span>
  );
}

export function Badge({
  children,
  tone = "neutral",
  title,
}: {
  children: ReactNode;
  tone?: "neutral" | "buy" | "sell" | "hold" | "info";
  title?: string;
}) {
  const tones: Record<string, string> = {
    neutral: "bg-surface-2 text-text-secondary border-border",
    buy: "bg-buy/15 text-buy border-buy/30",
    sell: "bg-sell/15 text-sell border-sell/30",
    hold: "bg-hold/15 text-hold border-hold/30",
    info: "bg-info/15 text-info border-info/30",
  };
  return (
    <span
      title={title}
      className={clsx(
        "inline-flex items-center rounded px-1.5 py-0.5 text-2xs font-medium border",
        tones[tone],
      )}
    >
      {children}
    </span>
  );
}

/** Horizontal bar for OBI in [-1, 1]: fills right (buy/green) or left (sell/red) of center. */
export function ObiBar({ value }: { value: number }) {
  const pct = Math.min(1, Math.abs(value)) * 50;
  const positive = value >= 0;
  return (
    <div className="relative h-2 w-full min-w-[64px] rounded-full bg-surface-2">
      <div className="absolute left-1/2 top-0 h-full w-px bg-border" />
      <div
        className={clsx("absolute top-0 h-full rounded-full", positive ? "bg-buy" : "bg-sell")}
        style={
          positive
            ? { left: "50%", width: `${pct}%` }
            : { right: "50%", width: `${pct}%` }
        }
      />
    </div>
  );
}

export function ConfidenceBar({ value }: { value: number }) {
  const pct = Math.max(0, Math.min(1, value)) * 100;
  return (
    <div className="flex items-center gap-2">
      <div className="h-1.5 w-16 rounded-full bg-surface-2 overflow-hidden">
        <div className="h-full bg-accent" style={{ width: `${pct}%` }} />
      </div>
      <span className="text-2xs text-text-secondary tnum">{pct.toFixed(0)}%</span>
    </div>
  );
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-10 text-sm text-text-secondary">
      <span className="h-3 w-3 animate-spin rounded-full border-2 border-border border-t-accent" />
      {label ?? "Loading…"}
    </div>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 text-center text-sm text-text-secondary">
      {children}
    </div>
  );
}

export function ErrorState({ error }: { error: unknown }) {
  const msg = error instanceof Error ? error.message : String(error);
  return (
    <div className="rounded-card border border-sell/30 bg-sell/10 px-4 py-3 text-sm text-sell">
      {msg}
    </div>
  );
}
