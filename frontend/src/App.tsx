import { Route, Routes } from "react-router-dom";
import { AppShell } from "./components/AppShell";
import { useLiveFeed } from "./lib/useLiveFeed";
import { MarketOverview } from "./pages/MarketOverview";
import { TokenDetail } from "./pages/TokenDetail";
import { StrategyPerformance } from "./pages/StrategyPerformance";
import { Positions } from "./pages/Positions";
import { LiveSignals } from "./pages/LiveSignals";

export default function App() {
  // Single app-wide live WebSocket; individual pages read from the store.
  useLiveFeed();
  return (
    <AppShell>
      <Routes>
        <Route path="/" element={<MarketOverview />} />
        <Route path="/token/:token" element={<TokenDetail />} />
        <Route path="/strategies" element={<StrategyPerformance />} />
        <Route path="/positions" element={<Positions />} />
        <Route path="/signals" element={<LiveSignals />} />
      </Routes>
    </AppShell>
  );
}
