import clsx from "clsx";
import { NavLink } from "react-router-dom";
import type { ReactNode } from "react";
import { useLiveStore } from "@/lib/liveStore";
import { fmtTime } from "@/lib/format";

const NAV = [
  { to: "/", label: "Market", end: true },
  { to: "/strategies", label: "Strategies" },
  { to: "/positions", label: "Positions" },
  { to: "/signals", label: "Signals" },
];

function ConnDot() {
  const status = useLiveStore((s) => s.status);
  const lastAt = useLiveStore((s) => s.lastMessageAt);
  const lagging = useLiveStore((s) => s.lagging);
  const color =
    status === "open" ? "bg-buy" : status === "connecting" ? "bg-hold" : "bg-sell";
  const text =
    status === "open" ? "Live" : status === "connecting" ? "Connecting" : "Disconnected";
  return (
    <div className="flex items-center gap-2 text-xs text-text-secondary">
      <span className={clsx("h-2 w-2 rounded-full", color, status === "open" && "animate-pulse-dot")} />
      <span>{text}</span>
      {lastAt && <span className="tnum text-text-muted">· {fmtTime(new Date(lastAt).toISOString())}</span>}
      {lagging > 0 && (
        <span className="rounded bg-hold/15 px-1.5 py-0.5 text-2xs text-hold" title="Client fell behind; oldest live messages were dropped">
          lagging {lagging}
        </span>
      )}
    </div>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-bg">
      {/* Sidebar */}
      <aside className="fixed inset-y-0 left-0 z-20 w-56 border-r border-border bg-surface">
        <div className="flex h-16 items-center gap-2 border-b border-border px-5">
          <div className="h-6 w-6 rounded bg-gradient-to-br from-accent to-info" />
          <div className="leading-tight">
            <div className="text-sm font-semibold">QuantStream</div>
            <div className="text-2xs text-text-secondary">Microstructure Monitor</div>
          </div>
        </div>
        <nav className="flex flex-col gap-1 p-3">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end={n.end}
              className={({ isActive }) =>
                clsx(
                  "rounded px-3 py-2 text-sm font-medium transition-colors",
                  isActive
                    ? "bg-surface-2 text-text-primary"
                    : "text-text-secondary hover:bg-surface-2/60 hover:text-text-primary",
                )
              }
            >
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="absolute inset-x-0 bottom-0 border-t border-border p-4 text-2xs text-text-muted">
          Paper-trading monitor · synthetic feed
        </div>
      </aside>

      {/* Header */}
      <header className="fixed inset-x-0 left-56 top-0 z-10 flex h-16 items-center justify-between border-b border-border bg-bg/80 px-6 backdrop-blur">
        <div />
        <ConnDot />
      </header>

      {/* Main */}
      <main className="ml-56 pt-16">
        <div className="mx-auto max-w-[1600px] p-6">{children}</div>
      </main>
    </div>
  );
}
