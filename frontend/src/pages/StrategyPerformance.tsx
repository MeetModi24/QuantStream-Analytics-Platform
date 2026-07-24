import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api } from "@/lib/api";
import type { StrategyPnlRow } from "@/lib/types";
import { fmtPct, fmtSignedUsd, fmtTime } from "@/lib/format";
import { Card, Stat, Badge, Spinner, ErrorState, EmptyState } from "@/components/ui";

// Below this trade count, win-rate is statistically meaningless — flag it.
const MIN_TRADES_FOR_WINRATE = 20;

// Stable-ish palette for equity lines, indexed by strategy order.
const LINE_COLORS = ["#3B82F6", "#10B981", "#F59E0B", "#A78BFA", "#EC4899", "#22D3EE"];

export function StrategyPerformance() {
  const latestQ = useQuery({
    queryKey: ["strategy-pnl-latest"],
    refetchInterval: 4000,
    queryFn: api.strategyPnlLatest,
  });

  const seriesQ = useQuery({
    queryKey: ["strategy-pnl-series"],
    refetchInterval: 6000,
    queryFn: () => api.strategyPnlSeries(undefined, 600),
  });

  const rows = useMemo(
    () => (latestQ.data ?? []).slice().sort((a, b) => b.total_pnl - a.total_pnl),
    [latestQ.data],
  );

  const totals = useMemo(() => {
    const total = rows.reduce((s, r) => s + r.total_pnl, 0);
    const realized = rows.reduce((s, r) => s + r.realized_pnl, 0);
    const trades = rows.reduce((s, r) => s + r.num_trades, 0);
    return { total, realized, trades, count: rows.length };
  }, [rows]);

  // Pivot the flat time series into { t, [strategy]: total_pnl } for the equity chart.
  const { equity, strategies } = useMemo(
    () => pivotEquity(seriesQ.data ?? []),
    [seriesQ.data],
  );

  if (latestQ.isLoading) return <Spinner label="Loading strategy performance…" />;
  if (latestQ.error) return <ErrorState error={latestQ.error} />;
  if (rows.length === 0) {
    return (
      <div className="space-y-6">
        <Header />
        <EmptyState>No strategy PnL recorded yet — let the pipeline run past warmup.</EmptyState>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <Header />

      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <Stat
          label="Total PnL"
          value={fmtSignedUsd(totals.total)}
          valueClassName={totals.total >= 0 ? "text-buy" : "text-sell"}
          sub={`${totals.count} strategies`}
        />
        <Stat
          label="Realized PnL"
          value={fmtSignedUsd(totals.realized)}
          valueClassName={totals.realized >= 0 ? "text-buy" : "text-sell"}
        />
        <Stat
          label="Unrealized PnL"
          value={fmtSignedUsd(totals.total - totals.realized)}
          valueClassName={totals.total - totals.realized >= 0 ? "text-buy" : "text-sell"}
        />
        <Stat label="Trades" value={totals.trades} sub="closed round-trips" />
      </div>

      <Card title="Equity curves (total PnL)">
        {equity.length < 2 ? (
          <EmptyState>Not enough history yet to plot equity.</EmptyState>
        ) : (
          <ResponsiveContainer width="100%" height={280}>
            <LineChart data={equity} margin={{ top: 4, right: 12, left: -8, bottom: 0 }}>
              <CartesianGrid stroke="rgba(31,41,55,0.5)" vertical={false} />
              <XAxis dataKey="t" tick={{ fontSize: 10, fill: "#6B7280" }} minTickGap={48} />
              <YAxis tick={{ fontSize: 10, fill: "#6B7280" }} width={56} />
              <Tooltip contentStyle={TOOLTIP_STYLE} labelStyle={{ color: "#9CA3AF" }} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              {strategies.map((s, i) => (
                <Line
                  key={s}
                  type="monotone"
                  dataKey={s}
                  stroke={LINE_COLORS[i % LINE_COLORS.length]}
                  strokeWidth={1.5}
                  dot={false}
                  isAnimationActive={false}
                  connectNulls
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        )}
      </Card>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Leaderboard" bodyClassName="p-0">
          <table className="dt">
            <thead>
              <tr>
                <th>Strategy</th>
                <th className="!text-right">Realized</th>
                <th className="!text-right">Unrealized</th>
                <th className="!text-right">Total</th>
                <th className="!text-right">Trades</th>
                <th className="!text-right">Win rate</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.strategy}>
                  <td className="font-medium">{r.strategy}</td>
                  <td className={clsx("num", pnlClass(r.realized_pnl))}>
                    {fmtSignedUsd(r.realized_pnl)}
                  </td>
                  <td className={clsx("num", pnlClass(r.unrealized_pnl))}>
                    {fmtSignedUsd(r.unrealized_pnl)}
                  </td>
                  <td className={clsx("num font-semibold", pnlClass(r.total_pnl))}>
                    {fmtSignedUsd(r.total_pnl)}
                  </td>
                  <td className="num text-text-secondary">{r.num_trades}</td>
                  <td className="num">
                    <div className="flex items-center justify-end gap-1.5">
                      <span>{fmtPct(r.win_rate)}</span>
                      {r.num_trades < MIN_TRADES_FOR_WINRATE && (
                        <Badge
                          tone="hold"
                          title={`Only ${r.num_trades} trades — win rate not yet statistically meaningful (need ≥ ${MIN_TRADES_FOR_WINRATE}).`}
                        >
                          low n
                        </Badge>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>

        <RealizedVsUnrealized rows={rows} />
      </div>
    </div>
  );
}

function Header() {
  return (
    <div>
      <h1 className="text-xl font-semibold">Strategy Performance</h1>
      <p className="text-sm text-text-secondary">
        Paper-trading PnL per strategy. Sizing is notional-normalized for cross-token comparability.
      </p>
    </div>
  );
}

/** Realized vs unrealized split per strategy as a stacked horizontal read. */
function RealizedVsUnrealized({ rows }: { rows: StrategyPnlRow[] }) {
  const [metric, setMetric] = useState<"realized" | "unrealized">("realized");
  const key = metric === "realized" ? "realized_pnl" : "unrealized_pnl";
  const data = rows.map((r) => ({ strategy: r.strategy, value: r[key] }));

  return (
    <Card
      title="PnL breakdown"
      right={
        <div className="flex gap-1">
          {(["realized", "unrealized"] as const).map((m) => (
            <button
              key={m}
              onClick={() => setMetric(m)}
              className={clsx(
                "rounded px-2 py-1 text-2xs font-medium capitalize transition-colors",
                m === metric
                  ? "bg-surface-2 text-text-primary"
                  : "text-text-secondary hover:text-text-primary",
              )}
            >
              {m}
            </button>
          ))}
        </div>
      }
    >
      <ResponsiveContainer width="100%" height={Math.max(160, rows.length * 44)}>
        <AreaChart data={data} layout="vertical" margin={{ top: 4, right: 16, left: 8, bottom: 0 }}>
          <XAxis type="number" tick={{ fontSize: 10, fill: "#6B7280" }} />
          <YAxis
            type="category"
            dataKey="strategy"
            tick={{ fontSize: 11, fill: "#9CA3AF" }}
            width={90}
          />
          <Tooltip
            contentStyle={TOOLTIP_STYLE}
            labelStyle={{ color: "#9CA3AF" }}
            formatter={(v: number) => [fmtSignedUsd(v), metric]}
          />
          <Area
            dataKey="value"
            stroke="#3B82F6"
            fill="#3B82F6"
            fillOpacity={0.25}
            isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </Card>
  );
}

function pivotEquity(series: StrategyPnlRow[]) {
  const strategies = [...new Set(series.map((r) => r.strategy))].sort();
  const byTime = new Map<string, Record<string, number | string>>();
  for (const r of series) {
    const key = fmtTime(r.ts);
    const bucket = byTime.get(key) ?? { t: key };
    bucket[r.strategy] = r.total_pnl;
    byTime.set(key, bucket);
  }
  const equity = [...byTime.values()];
  return { equity, strategies };
}

function pnlClass(v: number): string {
  return v > 0 ? "text-buy" : v < 0 ? "text-sell" : "text-text-secondary";
}

const TOOLTIP_STYLE = {
  background: "#111827",
  border: "1px solid #1F2937",
  borderRadius: 8,
  fontSize: 12,
} as const;
