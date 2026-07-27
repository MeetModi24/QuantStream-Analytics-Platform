import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import clsx from "clsx";
import { api } from "@/lib/api";
import { useLiveStore } from "@/lib/liveStore";
import type { SignalRow } from "@/lib/types";
import { fmtNum, fmtPrice, fmtTime } from "@/lib/format";
import { Card, ObiBar, Spinner, ErrorState, ActionBadge, Stat } from "@/components/ui";
import { Sparkline } from "@/components/Sparkline";

type SortKey = "token" | "microprice" | "spread_bps" | "obi";

export function MarketOverview() {
  const navigate = useNavigate();
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: "token", dir: 1 });

  const tokensQ = useQuery({ queryKey: ["tokens"], queryFn: api.tokens });
  const tokens = tokensQ.data ?? [];

  // Recent feature history for the sparklines — one batched request for all tokens
  // (not one-per-token), so the page scales past a handful of tokens.
  const histQ = useQuery({
    queryKey: ["market-hist"],
    refetchInterval: 5000,
    queryFn: () => api.featuresRecent(2),
  });

  // Latest signal per token, for the "last signal" column.
  const sigQ = useQuery({
    queryKey: ["market-signals"],
    refetchInterval: 3000,
    queryFn: () => api.signals(undefined, 200),
  });

  // Live tip overrides REST wherever the WS has delivered a fresher feature.
  const live = useLiveStore((s) => s.latestFeature);

  const lastSignalByToken = useMemo(() => {
    const m: Record<string, SignalRow> = {};
    for (const s of sigQ.data ?? []) if (!m[s.token]) m[s.token] = s;
    return m;
  }, [sigQ.data]);

  const rows = useMemo(() => {
    const hist = histQ.data ?? {};
    return tokens.map((token) => {
      const series = hist[token] ?? [];
      const latest = live[token] ?? series[series.length - 1];
      return {
        token,
        latest,
        spark: series.map((f) => f.microprice),
        lastSignal: lastSignalByToken[token],
      };
    });
  }, [tokens, histQ.data, live, lastSignalByToken]);

  const sorted = useMemo(() => {
    const copy = [...rows];
    copy.sort((a, b) => {
      let av: number | string;
      let bv: number | string;
      if (sort.key === "token") {
        av = a.token;
        bv = b.token;
      } else {
        av = a.latest?.[sort.key] ?? 0;
        bv = b.latest?.[sort.key] ?? 0;
      }
      if (av < bv) return -1 * sort.dir;
      if (av > bv) return 1 * sort.dir;
      return 0;
    });
    return copy;
  }, [rows, sort]);

  if (tokensQ.isLoading) return <Spinner label="Loading market…" />;
  if (tokensQ.error) return <ErrorState error={tokensQ.error} />;

  const totalTokens = tokens.length;
  const conflicts = 0; // shown on Positions page; kept simple here

  const toggleSort = (key: SortKey) =>
    setSort((s) => (s.key === key ? { key, dir: (s.dir * -1) as 1 | -1 } : { key, dir: 1 }));

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-xl font-semibold">Market Overview</h1>
          <p className="text-sm text-text-secondary">
            Live microstructure across all monitored tokens.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <Stat label="Tokens" value={totalTokens} />
        <Stat
          label="Signals (recent)"
          value={sigQ.data?.length ?? "—"}
          sub="last 200 across tokens"
        />
        <Stat
          label="Feed"
          value={<LiveCount />}
          sub="live feature updates"
        />
        <Stat label="Conflicts" value={conflicts} sub="see Positions page" />
      </div>

      <Card title="Tokens" bodyClassName="p-0">
        <div className="overflow-x-auto">
          <table className="dt">
            <thead>
              <tr>
                <Th onClick={() => toggleSort("token")} active={sort.key === "token"} dir={sort.dir}>
                  Token
                </Th>
                <Th right onClick={() => toggleSort("microprice")} active={sort.key === "microprice"} dir={sort.dir}>
                  Microprice
                </Th>
                <th className="!text-right">Mid</th>
                <Th right onClick={() => toggleSort("spread_bps")} active={sort.key === "spread_bps"} dir={sort.dir}>
                  Spread (bps)
                </Th>
                <Th onClick={() => toggleSort("obi")} active={sort.key === "obi"} dir={sort.dir}>
                  OBI
                </Th>
                <th>Trend</th>
                <th>Last signal</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((r) => (
                <tr
                  key={r.token}
                  className="cursor-pointer"
                  onClick={() => navigate(`/token/${r.token}`)}
                >
                  <td className="font-medium">{r.token}</td>
                  <td className="num">{fmtPrice(r.latest?.microprice)}</td>
                  <td className="num text-text-secondary">{fmtPrice(r.latest?.mid_price)}</td>
                  <td className="num">{fmtNum(r.latest?.spread_bps)}</td>
                  <td>
                    <div className="flex items-center gap-2">
                      <ObiBar value={r.latest?.obi ?? 0} />
                      <span
                        className={clsx(
                          "num text-2xs w-10",
                          (r.latest?.obi ?? 0) >= 0 ? "text-buy" : "text-sell",
                        )}
                      >
                        {fmtNum(r.latest?.obi, 2)}
                      </span>
                    </div>
                  </td>
                  <td>
                    <Sparkline data={r.spark} />
                  </td>
                  <td>
                    {r.lastSignal ? (
                      <div className="flex items-center gap-2">
                        <ActionBadge action={r.lastSignal.action} />
                        <span className="text-2xs text-text-secondary">
                          {r.lastSignal.strategy}
                        </span>
                        <span className="text-2xs text-text-muted tnum">
                          {fmtTime(r.lastSignal.ts)}
                        </span>
                      </div>
                    ) : (
                      <span className="text-2xs text-text-muted">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}

function LiveCount() {
  const n = useLiveStore((s) => Object.keys(s.latestFeature).length);
  return <span>{n}</span>;
}

function Th({
  children,
  onClick,
  active,
  dir,
  right,
}: {
  children: React.ReactNode;
  onClick: () => void;
  active: boolean;
  dir: 1 | -1;
  right?: boolean;
}) {
  return (
    <th
      onClick={onClick}
      className={clsx("cursor-pointer select-none", right && "!text-right")}
    >
      <span className="inline-flex items-center gap-1">
        {children}
        <span className={clsx("text-text-muted", !active && "opacity-0")}>
          {dir === 1 ? "▲" : "▼"}
        </span>
      </span>
    </th>
  );
}
