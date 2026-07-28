import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import clsx from "clsx";
import { api } from "@/lib/api";
import { useLiveStore } from "@/lib/liveStore";
import type { Action, SignalRow } from "@/lib/types";
import { fmtPrice, fmtTime } from "@/lib/format";
import { Card, ActionBadge, ConfidenceBar, Stat, EmptyState } from "@/components/ui";

const ACTIONS: Action[] = ["BUY", "SELL", "CLOSE"];

export function LiveSignals() {
  const navigate = useNavigate();
  const liveSignals = useLiveStore((s) => s.signals);
  const status = useLiveStore((s) => s.status);
  const lagging = useLiveStore((s) => s.lagging);

  const tokensQ = useQuery({ queryKey: ["tokens"], queryFn: api.tokens });
  const stratQ = useQuery({ queryKey: ["strategies"], queryFn: api.strategies });

  // Seed the feed from REST so the page isn't empty before the first WS signal.
  const seedQ = useQuery({
    queryKey: ["signals-seed"],
    queryFn: () => api.signals(undefined, 100),
    staleTime: 30_000,
  });

  const [token, setToken] = useState<string>("");
  const [strategy, setStrategy] = useState<string>("");
  const [action, setAction] = useState<Action | "">("");

  // Merge live (newest-first) over the REST seed, de-duped by ts+strategy+token.
  const merged = useMemo(() => {
    const seen = new Set<string>();
    const out: SignalRow[] = [];
    for (const s of [...liveSignals, ...(seedQ.data ?? [])]) {
      const k = `${s.ts}|${s.strategy}|${s.token}|${s.action}`;
      if (seen.has(k)) continue;
      seen.add(k);
      out.push(s);
    }
    return out;
  }, [liveSignals, seedQ.data]);

  const filtered = useMemo(
    () =>
      merged.filter(
        (s) =>
          (!token || s.token === token) &&
          (!strategy || s.strategy === strategy) &&
          (!action || s.action === action),
      ),
    [merged, token, strategy, action],
  );

  const counts = useMemo(() => {
    const c: Record<Action, number> = { BUY: 0, SELL: 0, CLOSE: 0 };
    for (const s of filtered) c[s.action]++;
    return c;
  }, [filtered]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Live Signals</h1>
          <p className="text-sm text-text-secondary">
            Streaming signal feed across all strategies and tokens.
          </p>
        </div>
        <div className="flex items-center gap-2 text-xs">
          <span
            className={clsx(
              "inline-flex items-center gap-1.5 rounded-full border px-2 py-1",
              status === "open"
                ? "border-buy/30 bg-buy/10 text-buy"
                : status === "connecting"
                  ? "border-hold/30 bg-hold/10 text-hold"
                  : "border-sell/30 bg-sell/10 text-sell",
            )}
          >
            <span
              className={clsx(
                "h-1.5 w-1.5 rounded-full",
                status === "open" ? "bg-buy animate-pulse-dot" : status === "connecting" ? "bg-hold" : "bg-sell",
              )}
            />
            {status === "open" ? "Streaming" : status === "connecting" ? "Connecting" : "Offline"}
          </span>
          {lagging > 0 && (
            <span
              className="rounded-full border border-hold/30 bg-hold/10 px-2 py-1 text-hold"
              title="Client fell behind; some live messages were dropped by the server"
            >
              lagging {lagging}
            </span>
          )}
        </div>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Stat label="Buy" value={counts.BUY} valueClassName="text-buy" />
        <Stat label="Sell" value={counts.SELL} valueClassName="text-sell" />
        <Stat label="Close" value={counts.CLOSE} valueClassName="text-hold" />
      </div>

      <Card
        title={`Feed (${filtered.length})`}
        bodyClassName="p-0"
        right={
          <div className="flex flex-wrap items-center gap-2">
            <Select value={token} onChange={setToken} placeholder="All tokens" options={tokensQ.data ?? []} />
            <Select
              value={strategy}
              onChange={setStrategy}
              placeholder="All strategies"
              options={stratQ.data ?? []}
            />
            <div className="flex gap-1">
              {ACTIONS.map((a) => (
                <button
                  key={a}
                  onClick={() => setAction((cur) => (cur === a ? "" : a))}
                  className={clsx(
                    "rounded px-2 py-1 text-2xs font-semibold border transition-colors",
                    action === a
                      ? a === "BUY"
                        ? "border-buy/40 bg-buy/15 text-buy"
                        : a === "SELL"
                          ? "border-sell/40 bg-sell/15 text-sell"
                          : "border-hold/40 bg-hold/15 text-hold"
                      : "border-border text-text-secondary hover:text-text-primary",
                  )}
                >
                  {a}
                </button>
              ))}
            </div>
          </div>
        }
      >
        {filtered.length === 0 ? (
          <EmptyState>
            {merged.length === 0 ? "Waiting for signals…" : "No signals match the current filters."}
          </EmptyState>
        ) : (
          <div className="max-h-[600px] overflow-auto">
            <table className="dt">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Token</th>
                  <th>Strategy</th>
                  <th>Action</th>
                  <th className="!text-right">Price</th>
                  <th>Confidence</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                {filtered.slice(0, 300).map((s, i) => (
                  <tr
                    key={`${s.ts}-${s.strategy}-${s.token}-${i}`}
                    className={clsx("cursor-pointer", i === 0 && "animate-fade-in")}
                    onClick={() => navigate(`/token/${s.token}`)}
                  >
                    <td className="tnum text-text-secondary">{fmtTime(s.ts)}</td>
                    <td className="tnum font-medium">{s.token}</td>
                    <td>{s.strategy}</td>
                    <td>
                      <ActionBadge action={s.action} />
                    </td>
                    <td className="num">{fmtPrice(s.price)}</td>
                    <td>
                      <ConfidenceBar value={s.confidence} />
                    </td>
                    <td
                      className="max-w-[360px] truncate text-xs text-text-secondary"
                      title={s.reason}
                    >
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

function Select({
  value,
  onChange,
  placeholder,
  options,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  options: string[];
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="rounded border border-border bg-surface-2 px-2 py-1 text-2xs text-text-primary focus:border-accent focus:outline-none"
    >
      <option value="">{placeholder}</option>
      {options.map((o) => (
        <option key={o} value={o}>
          {o}
        </option>
      ))}
    </select>
  );
}
