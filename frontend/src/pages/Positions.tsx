import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import clsx from "clsx";
import { api } from "@/lib/api";
import type { ConsensusRow, PositionRow } from "@/lib/types";
import { fmtPrice, fmtSignedNum, fmtSignedUsd } from "@/lib/format";
import { Card, Stat, Badge, Spinner, ErrorState, EmptyState } from "@/components/ui";

export function Positions() {
  const posQ = useQuery({
    queryKey: ["positions"],
    refetchInterval: 4000,
    queryFn: () => api.positions(),
  });

  const consensusQ = useQuery({
    queryKey: ["consensus"],
    refetchInterval: 4000,
    queryFn: api.consensus,
  });

  const positions = posQ.data ?? [];

  // Only positions with a live exposure are interesting for the grid.
  const open = useMemo(
    () => positions.filter((p) => Math.abs(p.net_position) > 1e-9),
    [positions],
  );

  const stats = useMemo(() => {
    const longs = open.filter((p) => p.net_position > 0).length;
    const shorts = open.filter((p) => p.net_position < 0).length;
    const unreal = open.reduce((s, p) => s + p.unrealized_pnl, 0);
    return { longs, shorts, unreal, count: open.length };
  }, [open]);

  const conflicts = (consensusQ.data ?? []).filter((c) => c.conflict);

  if (posQ.isLoading) return <Spinner label="Loading positions…" />;
  if (posQ.error) return <ErrorState error={posQ.error} />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Positions &amp; Exposure</h1>
        <p className="text-sm text-text-secondary">
          Live net exposure per strategy and token, and where strategies disagree.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <Stat label="Open positions" value={stats.count} />
        <Stat label="Long" value={stats.longs} valueClassName="text-buy" />
        <Stat label="Short" value={stats.shorts} valueClassName="text-sell" />
        <Stat
          label="Unrealized PnL"
          value={fmtSignedUsd(stats.unreal)}
          valueClassName={stats.unreal >= 0 ? "text-buy" : "text-sell"}
        />
      </div>

      <Consensus rows={consensusQ.data ?? []} conflicts={conflicts} loading={consensusQ.isLoading} />

      <Card title="Open positions" bodyClassName="p-0">
        {open.length === 0 ? (
          <EmptyState>No open positions — all strategies are flat.</EmptyState>
        ) : (
          <div className="overflow-x-auto">
            <table className="dt">
              <thead>
                <tr>
                  <th>Strategy</th>
                  <th>Token</th>
                  <th>Side</th>
                  <th className="!text-right">Net position</th>
                  <th className="!text-right">Avg entry</th>
                  <th className="!text-right">Realized</th>
                  <th className="!text-right">Unrealized</th>
                </tr>
              </thead>
              <tbody>
                {open
                  .slice()
                  .sort((a, b) => b.unrealized_pnl - a.unrealized_pnl)
                  .map((p, i) => (
                    <PositionRowView key={`${p.strategy}-${p.token}-${i}`} p={p} />
                  ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function PositionRowView({ p }: { p: PositionRow }) {
  const long = p.net_position > 0;
  return (
    <tr>
      <td className="font-medium">{p.strategy}</td>
      <td className="tnum">{p.token}</td>
      <td>
        <Badge tone={long ? "buy" : "sell"}>{long ? "LONG" : "SHORT"}</Badge>
      </td>
      <td className={clsx("num", long ? "text-buy" : "text-sell")}>
        {fmtSignedNum(p.net_position, 4)}
      </td>
      <td className="num text-text-secondary">{fmtPrice(p.avg_entry_price)}</td>
      <td className={clsx("num", pnlClass(p.realized_pnl))}>{fmtSignedUsd(p.realized_pnl)}</td>
      <td className={clsx("num", pnlClass(p.unrealized_pnl))}>{fmtSignedUsd(p.unrealized_pnl)}</td>
    </tr>
  );
}

/** Per-token consensus: which strategies are long vs short, and where they conflict. */
function Consensus({
  rows,
  conflicts,
  loading,
}: {
  rows: ConsensusRow[];
  conflicts: ConsensusRow[];
  loading: boolean;
}) {
  const navigate = useNavigate();
  const active = rows.filter((r) => r.longs.length + r.shorts.length > 0);

  return (
    <Card
      title="Consensus"
      right={
        conflicts.length > 0 ? (
          <Badge tone="hold" title="Tokens where at least one strategy is long and another is short">
            {conflicts.length} conflict{conflicts.length > 1 ? "s" : ""}
          </Badge>
        ) : undefined
      }
    >
      {loading ? (
        <Spinner />
      ) : active.length === 0 ? (
        <EmptyState>No directional positions across strategies yet.</EmptyState>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {active
            .slice()
            .sort((a, b) => Number(b.conflict) - Number(a.conflict) || a.token.localeCompare(b.token))
            .map((c) => (
              <button
                key={c.token}
                onClick={() => navigate(`/token/${c.token}`)}
                className={clsx(
                  "rounded-card border bg-surface px-3 py-2.5 text-left transition-colors hover:bg-surface-2/60",
                  c.conflict ? "border-hold/40" : "border-border",
                )}
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold tnum">{c.token}</span>
                  {c.conflict && <Badge tone="hold">conflict</Badge>}
                </div>
                <div className="mt-2 space-y-1 text-xs">
                  <ConsensusSide label="Long" tone="buy" names={c.longs} />
                  <ConsensusSide label="Short" tone="sell" names={c.shorts} />
                </div>
              </button>
            ))}
        </div>
      )}
    </Card>
  );
}

function ConsensusSide({
  label,
  tone,
  names,
}: {
  label: string;
  tone: "buy" | "sell";
  names: string[];
}) {
  return (
    <div className="flex items-start gap-2">
      <span className={clsx("w-10 shrink-0 font-medium", tone === "buy" ? "text-buy" : "text-sell")}>
        {label}
      </span>
      <span className="text-text-secondary">
        {names.length ? names.join(", ") : <span className="text-text-muted">—</span>}
      </span>
    </div>
  );
}

function pnlClass(v: number): string {
  return v > 0 ? "text-buy" : v < 0 ? "text-sell" : "text-text-secondary";
}
