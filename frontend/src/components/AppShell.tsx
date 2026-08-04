import clsx from "clsx";
import { NavLink, useLocation } from "react-router-dom";
import { useEffect, useState, type ReactNode } from "react";
import { useLiveStore } from "@/lib/liveStore";
import { useLatencyStore } from "@/lib/latencyStore";
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
      {lastAt && (
        <span className="tnum text-text-muted hidden sm:inline">
          · {fmtTime(new Date(lastAt).toISOString())}
        </span>
      )}
      {lagging > 0 && (
        <span
          className="rounded bg-hold/15 px-1.5 py-0.5 text-2xs text-hold"
          title="Client fell behind; oldest live messages were dropped"
        >
          lagging {lagging}
        </span>
      )}
    </div>
  );
}

/** Live event -> screen latency, with a per-leg breakdown on hover. Reads the throttled
 *  summaries from the latency store (recomputed at most a couple times a second). */
function LatencyMeter() {
  const total = useLatencyStore((s) => s.total);
  const pipeline = useLatencyStore((s) => s.pipeline);
  const delivery = useLatencyStore((s) => s.delivery);
  const count = useLatencyStore((s) => s.count);

  if (count === 0) return null;

  // Green under 250ms, amber under 1s, red beyond — monitoring-plane targets, not
  // trading-path targets (see docs/concepts/03-hft-monitoring-at-scale.md).
  const tone =
    total.p99 < 250 ? "text-buy" : total.p99 < 1000 ? "text-hold" : "text-sell";

  const fmt = (ms: number) => (ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${Math.round(ms)}ms`);

  return (
    <div
      className="hidden items-center gap-2 text-xs text-text-secondary sm:flex"
      title={
        `event → screen latency (${count} samples)\n` +
        `pipeline (event→API)  p50 ${fmt(pipeline.p50)}  p99 ${fmt(pipeline.p99)}\n` +
        `delivery (API→screen) p50 ${fmt(delivery.p50)}  p99 ${fmt(delivery.p99)}\n` +
        `total    (event→screen) p50 ${fmt(total.p50)}  p99 ${fmt(total.p99)}`
      }
    >
      <span className="text-text-muted">latency</span>
      <span className={clsx("tnum font-medium", tone)}>
        {fmt(total.p50)}
        <span className="text-text-muted"> p50</span>
      </span>
      <span className={clsx("tnum", tone)}>
        {fmt(total.p99)}
        <span className="text-text-muted"> p99</span>
      </span>
    </div>
  );
}

function Brand() {
  return (
    <div className="flex items-center gap-2">
      <div className="h-6 w-6 rounded bg-gradient-to-br from-accent to-info" />
      <div className="leading-tight">
        <div className="text-sm font-semibold">QuantStream</div>
        <div className="text-2xs text-text-secondary">Microstructure Monitor</div>
      </div>
    </div>
  );
}

function NavItems({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav className="flex flex-col gap-1 p-3">
      {NAV.map((n) => (
        <NavLink
          key={n.to}
          to={n.to}
          end={n.end}
          onClick={onNavigate}
          className={({ isActive }) =>
            clsx(
              "group relative flex items-center rounded px-3 py-2 text-sm font-medium transition-all duration-150",
              isActive
                ? "bg-surface-2 text-text-primary"
                : "text-text-secondary hover:translate-x-0.5 hover:bg-surface-2/60 hover:text-text-primary",
            )
          }
        >
          {({ isActive }) => (
            <>
              <span
                className={clsx(
                  "absolute inset-y-1.5 left-0 w-0.5 rounded-full bg-accent transition-all duration-150",
                  isActive ? "opacity-100" : "opacity-0 group-hover:opacity-40",
                )}
                aria-hidden="true"
              />
              {n.label}
            </>
          )}
        </NavLink>
      ))}
    </nav>
  );
}

/** Sidebar body, reused by both the fixed desktop rail and the mobile drawer. */
function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <div className="flex h-full flex-col">
      <div className="flex h-16 shrink-0 items-center border-b border-border px-5">
        <Brand />
      </div>
      <div className="flex-1 overflow-y-auto">
        <NavItems onNavigate={onNavigate} />
      </div>
      <div className="shrink-0 border-t border-border p-4 text-2xs text-text-muted">
        Paper-trading monitor · synthetic feed
      </div>
    </div>
  );
}

function MenuIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path d="M3 5h14M3 10h14M3 15h14" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const location = useLocation();

  // Close the mobile drawer whenever the route changes.
  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  // Lock body scroll and allow Esc to dismiss while the drawer is open.
  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setDrawerOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  return (
    <div className="min-h-screen bg-bg">
      {/* Desktop rail — fixed, only from lg up. */}
      <aside className="fixed inset-y-0 left-0 z-20 hidden w-56 border-r border-border bg-surface lg:block">
        <SidebarContent />
      </aside>

      {/* Mobile drawer + backdrop — below lg only. */}
      <div
        className={clsx(
          "fixed inset-0 z-40 bg-black/60 backdrop-blur-sm transition-opacity lg:hidden",
          drawerOpen ? "opacity-100" : "pointer-events-none opacity-0",
        )}
        onClick={() => setDrawerOpen(false)}
        aria-hidden="true"
      />
      <aside
        className={clsx(
          "fixed inset-y-0 left-0 z-50 w-64 max-w-[80vw] border-r border-border bg-surface transition-transform duration-200 lg:hidden",
          drawerOpen ? "translate-x-0" : "-translate-x-full",
        )}
        role="dialog"
        aria-label="Navigation"
        aria-modal="true"
      >
        <SidebarContent onNavigate={() => setDrawerOpen(false)} />
      </aside>

      {/* Header — full width on mobile, offset by the rail from lg up. */}
      <header className="fixed inset-x-0 top-0 z-30 flex h-16 items-center justify-between border-b border-border bg-bg/80 px-4 backdrop-blur sm:px-6 lg:left-56">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            className="-ml-1 rounded p-2 text-text-secondary transition-colors hover:bg-surface-2 hover:text-text-primary lg:hidden"
            aria-label="Open navigation"
          >
            <MenuIcon />
          </button>
          <div className="lg:hidden">
            <Brand />
          </div>
        </div>
        <div className="flex items-center gap-4">
          <LatencyMeter />
          <span className="hidden h-4 w-px bg-border sm:block" />
          <ConnDot />
        </div>
      </header>

      {/* Main */}
      <main className="pt-16 lg:ml-56">
        <div className="mx-auto max-w-[1600px] p-4 sm:p-6">{children}</div>
      </main>
    </div>
  );
}
