import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Navbar } from './components/layout/Navbar';
import { Sidebar } from './components/layout/Sidebar';
import { Container } from './components/layout/Container';
import { MarketDashboard } from './pages/MarketDashboard';
import { StrategiesList } from './pages/StrategiesList';
import { StrategyDetail } from './pages/StrategyDetail';
import { LeaderboardPage } from './pages/LeaderboardPage';
import { SignalsPage } from './pages/SignalsPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
      staleTime: 2000,
    },
  },
});

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <div className="min-h-screen bg-background">
          <Navbar />
          <Sidebar />
          <Container>
            <Routes>
              <Route path="/" element={<MarketDashboard />} />
              <Route path="/strategies" element={<StrategiesList />} />
              <Route path="/strategies/:name" element={<StrategyDetail />} />
              <Route path="/leaderboard" element={<LeaderboardPage />} />
              <Route path="/signals" element={<SignalsPage />} />
            </Routes>
          </Container>
        </div>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

export default App;
