import { Link, useLocation } from 'react-router-dom';
import { BarChart3, Trophy, Activity, Zap } from 'lucide-react';
import { cn } from '@/lib/utils';

const navItems = [
  { path: '/', icon: Activity, label: 'Market' },
  { path: '/strategies', icon: Zap, label: 'Strategies' },
  { path: '/leaderboard', icon: Trophy, label: 'Leaderboard' },
  { path: '/signals', icon: BarChart3, label: 'Signals' },
];

export function Sidebar() {
  const location = useLocation();

  return (
    <aside className="fixed left-0 top-16 z-40 h-[calc(100vh-4rem)] w-16 border-r border-border bg-card">
      <nav className="flex h-full flex-col gap-2 p-2">
        {navItems.map((item) => {
          const Icon = item.icon;
          const isActive = location.pathname === item.path;

          return (
            <Link
              key={item.path}
              to={item.path}
              className={cn(
                'flex h-12 w-12 flex-col items-center justify-center gap-1 rounded-lg transition-colors',
                isActive
                  ? 'bg-primary/10 text-primary'
                  : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
              )}
              title={item.label}
            >
              <Icon className="h-5 w-5" />
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
