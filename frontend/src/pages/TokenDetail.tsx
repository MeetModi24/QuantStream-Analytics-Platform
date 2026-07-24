import { useMemo, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import clsx from "clsx";
import {
  Area,
  AreaChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api } from "@/lib/api";
import { useLiveStore } from "@/lib/liveStore";
import type { FeatureRow } from "@/lib/types";
import { fmtNum, fmtPrice, fmtTime } from "@/lib/format";
import { Card, ActionBadge, ConfidenceBar, Spinner, ErrorState, EmptyState } from "@/components/ui";
import { PriceChart } from "@/components/PriceChart";

const INTERVALS = ["1m", "5m", "15m"] as const;
type Interval = (typeof INTERVALS)[number];

export function TokenDetail() {
  const { token = "" } = useParams();
  const navigate = useNavigate();
  const [interval, setInterval] = useState<Interval>("1m");

  const tokensQ = useQuery({ queryKey: ["tokens"], queryFn: api.tokens });

  const candlesQ = useQuery({
    queryKey: ["candles", token, interval],
    enabled: !!token,
    refetchInterval: 5000,
    queryFn: () => api.candles(token, interval, 500),
  });

  const signalsQ = useQuery({
    queryKey: ["token-signals", token],
    enabled: !!token,
    refetchInterval: 3000,
    queryFn: () => api.signals(token, 100),
  });

  // Recent feature history drives the OBI oscillator + spread line.
  const featQ = useQuery({
    queryKey: ["token-features", token],
    enabled: !!token,
    refetchInterval: 3000,
    queryFn: () => api.features(token, 240),
  });

  const obQ = useQuery({
    queryKey: ["orderbook", token],
    enabled: !!token,
    refetchInterval: 2000,
    queryFn: () => api.orderbookLatest(token),
  });

  const liveFeat = useLiveStore((s) => (token ? s.latestFeature[token] : undefined));

  // Feature series ascending in time for the charts; splice in the live tip.
  const feats = useMemo<FeatureRow[]>(() => {
    const base = (featQ.data ?? []).slice().sort((a, b) => a.ts.localeCompare(b.ts));
    if (liveFeat && (base.length === 0 || liveFeat.ts > base[base.length - 1].ts)) {
      base.push(liveFeat);
    }
    return base;
  }, [featQ.data, liveFeat]);

  const latest = liveFeat ?? feats[feats.length - 1];

  if (!token) return <EmptyState>No token selected.</EmptyState>;
  if (tokensQ.data && !tokensQ.data.includes(token)) {
    return (
      <div className="space-y-4">
        <BackLink onClick={() => navigate("/")} />
        <ErrorState error={`Unknown token "${token}".`} />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between">
        <div>
          <BackLink onClick={() => navigate("/")} />
          <h1 className="mt-1 text-xl font-semibold tnum">{token}</h1>
          <p className="text-sm text-text-secondary">
            Microprice candles, order-book imbalance and signal history.
          </p>
        </div>
        <TopOfBook feature={latest} book={obQ.data} />
      </div>

      <Card
        title="Microprice"
        bodyClassName="p-0"
        right={
          <div className="flex gap-1">
            {INTERVALS.map((iv) => (
              <button
                key={iv}
                onClick={() => setInterval(iv)}
                className={clsx(
                  "rounded px-2 py-1 text-2xs font-medium transition-colors",
                  iv === interval
                    ? "bg-surface-2 text-text-primary"
                    : "text-text-secondary hover:text-text-primary",
                )}
              >
                {iv}
              </button>
            ))}
          </div>
        }
      >
        <div className="px-2 pb-2 pt-3">
          {candlesQ.isLoading ? (
            <Spinner label="Loading candles…" />
          ) : candlesQ.error ? (
            <ErrorState error={candlesQ.error} />
          ) : (candlesQ.data?.length ?? 0) === 0 ? (
            <EmptyState>No candle data yet for this interval.</EmptyState>
          ) : (
            <PriceChart candles={candlesQ.data ?? []} signals={signalsQ.data ?? []} />
          )}
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Order-book imbalance">
          <ObiOscillator feats={feats} />
        </Card>
        <Card title="Spread (bps)">
          <SpreadChart feats={feats} />
        </Card>
      </div>

      <Card title="Signals" bodyClassName="p-0">
        {signalsQ.isLoading ? (
          <Spinner />
        ) : (signalsQ.data?.length ?? 0) === 0 ? (
          <EmptyState>No signals emitted for {token} yet.</EmptyState>
        ) : (
          <div className="max-h-[420px] overflow-auto">
            <table className="dt">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Strategy</th>
                  <th>Action</th>
                  <th className="!text-right">Price</th>
                  <th>Confidence</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                {(signalsQ.data ?? []).map((s, i) => (
                  <tr key={`${s.ts}-${s.strategy}-${i}`}>
                    <td className="tnum text-text-secondary">{fmtTime(s.ts)}</td>
                    <td className="font-medium">{s.strategy}</td>
                    <td>
                      <ActionBadge action={s.action} />
                    </td>
                    <td className="num">{fmtPrice(s.price)}</td>
                    <td>
                      <ConfidenceBar value={s.confidence} />
                    </td>
                    <td className="max-w-[360px] truncate text-xs text-text-secondary" title={s.reason}>
                      {s.reason}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function BackLink({ onClick }: { onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="text-2xs font-medium text-text-secondary hover:text-text-primary"
    >
      ← Market
    </button>
  );
}

/** Compact top-of-book strip: bid / mid / ask, spread, and L5 depth balance. */
function TopOfBook({
  feature,
  book,
}: {
  feature?: FeatureRow;
  book?: import("@/lib/types").OrderBookRow;
}) {
  const bidDepth = book?.bid_depth_l5 ?? 0;
  const askDepth = book?.ask_depth_l5 ?? 0;
  const total = bidDepth + askDepth || 1;
  const bidPct = (bidDepth / total) * 100;

  return (
    <div className="flex items-stretch gap-4 rounded-card border border-border bg-surface px-4 py-2">
      <Quote label="Bid" value={fmtPrice(book?.best_bid_price)} tone="buy" />
      <Quote label="Mid" value={fmtPrice(feature?.mid_price)} />
      <Quote label="Ask" value={fmtPrice(book?.best_ask_price)} tone="sell" />
      <div className="w-px bg-border" />
      <div className="flex flex-col justify-center">
        <div className="label">Spread</div>
        <div className="tnum text-sm">{fmtNum(feature?.spread_bps)} bps</div>
      </div>
      <div className="hidden w-28 flex-col justify-center sm:flex">
        <div className="label">L5 depth</div>
        <div className="mt-1 flex h-2 w-full overflow-hidden rounded-full bg-surface-2">
          <div className="h-full bg-buy" style={{ width: `${bidPct}%` }} />
          <div className="h-full bg-sell" style={{ width: `${100 - bidPct}%` }} />
        </div>
      </div>
    </div>
  );
}

function Quote({ label, value, tone }: { label: string; value: string; tone?: "buy" | "sell" }) {
  return (
    <div className="flex flex-col justify-center">
      <div className="label">{label}</div>
      <div
        className={clsx(
          "tnum text-sm font-medium",
          tone === "buy" && "text-buy",
          tone === "sell" && "text-sell",
        )}
      >
        {value}
      </div>
    </div>
  );
}

function chartData(feats: FeatureRow[]) {
  return feats.map((f) => ({
    t: fmtTime(f.ts),
    obi: f.obi,
    spread_bps: f.spread_bps,
  }));
}

/** OBI oscillator: green area above 0 (buy pressure), red below (sell pressure). */
function ObiOscillator({ feats }: { feats: FeatureRow[] }) {
  const data = useMemo(() => chartData(feats), [feats]);
  if (data.length < 2) return <EmptyState>Waiting for feature data…</EmptyState>;
  return (
    <ResponsiveContainer width="100%" height={200}>
      <AreaChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
        <defs>
          <linearGradient id="obiPos" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#10B981" stopOpacity={0.5} />
            <stop offset="100%" stopColor="#10B981" stopOpacity={0} />
          </linearGradient>
        </defs>
        <XAxis dataKey="t" tick={{ fontSize: 10, fill: "#6B7280" }} minTickGap={40} />
        <YAxis
          domain={[-1, 1]}
          ticks={[-1, -0.5, 0, 0.5, 1]}
          tick={{ fontSize: 10, fill: "#6B7280" }}
          width={40}
        />
        <ReferenceLine y={0} stroke="#374151" />
        <Tooltip
          contentStyle={TOOLTIP_STYLE}
          labelStyle={{ color: "#9CA3AF" }}
          formatter={(v: number) => [v.toFixed(3), "OBI"]}
        />
        <Area
          type="monotone"
          dataKey="obi"
          stroke="#10B981"
          strokeWidth={1.5}
          fill="url(#obiPos)"
          isAnimationActive={false}
          dot={false}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}

function SpreadChart({ feats }: { feats: FeatureRow[] }) {
  const data = useMemo(() => chartData(feats), [feats]);
  if (data.length < 2) return <EmptyState>Waiting for feature data…</EmptyState>;
  return (
    <ResponsiveContainer width="100%" height={200}>
      <AreaChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
        <defs>
          <linearGradient id="spreadFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#3B82F6" stopOpacity={0.4} />
            <stop offset="100%" stopColor="#3B82F6" stopOpacity={0} />
          </linearGradient>
        </defs>
        <XAxis dataKey="t" tick={{ fontSize: 10, fill: "#6B7280" }} minTickGap={40} />
        <YAxis tick={{ fontSize: 10, fill: "#6B7280" }} width={40} />
        <Tooltip
          contentStyle={TOOLTIP_STYLE}
          labelStyle={{ color: "#9CA3AF" }}
          formatter={(v: number) => [`${v.toFixed(2)} bps`, "Spread"]}
        />
        <Area
          type="monotone"
          dataKey="spread_bps"
          stroke="#3B82F6"
          strokeWidth={1.5}
          fill="url(#spreadFill)"
          isAnimationActive={false}
          dot={false}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}

const TOOLTIP_STYLE = {
  background: "#111827",
  border: "1px solid #1F2937",
  borderRadius: 8,
  fontSize: 12,
} as const;
