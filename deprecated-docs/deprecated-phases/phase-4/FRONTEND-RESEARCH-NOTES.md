# Frontend UI Research - Professional Trading Dashboards

**Goal:** Build a professional, modern trading analytics dashboard that doesn't look like generic AI-generated slides.

**Anti-Pattern:** Avoid centered cards, excessive shadows, gradient backgrounds, over-animated components.

---

## Research Findings

### 1. Component Libraries (Modern, Non-Generic)

#### Shadcn UI ✅ RECOMMENDED
- **Philosophy:** Copy components into your project, own the code
- **Why it's good:** Not locked into a library's API, full customization
- **Components:** 50+ components (buttons, dialogs, tables, charts)
- **Stack:** React + Tailwind + Radix UI (accessibility)
- **Use for:** All UI components (buttons, tables, cards, dialogs)

#### Tremor ✅ RECOMMENDED FOR DASHBOARDS
- **Philosophy:** Built specifically for analytical dashboards
- **Why it's good:** 35+ dashboard-specific components, beautiful defaults
- **Components:** KPI cards, data tables, area charts, bar charts, donut charts
- **Stack:** React + Tailwind + Recharts + Radix UI
- **Use for:** Dashboard-specific components (metrics, filters, date pickers)
- **Features:** 250+ pre-built dashboard blocks/templates

#### Magic UI (Optional Enhancement)
- **Philosophy:** Animated components companion for shadcn/ui
- **Why it's good:** 150+ animated components with smooth transitions
- **Use for:** Micro-interactions, transitions (not core functionality)

---

### 2. Chart Libraries

#### TradingView Lightweight Charts ✅ PRIMARY CHOICE
- **Size:** 35 KB (tiny!)
- **Performance:** Handles thousands of bars, real-time streaming
- **License:** Apache 2.0 (free, open source)
- **Chart Types:** Candlesticks, Line, Area, Bar, Histogram
- **Customization:** Full theme control, custom plugins
- **Use for:** Main candlestick price charts

**Key Features:**
- HTML5 Canvas (super fast)
- Real-time data streaming
- Custom plugin system
- Attribution required (link to tradingview.com)

#### Recharts (Secondary for Analytics)
- **Use for:** Performance graphs (equity curves, returns, comparisons)
- **Why both?** Lightweight Charts for price, Recharts for strategy analytics

---

### 3. Design System Analysis - Professional Trading Platforms

#### Robinhood Design Patterns
- **Color:** Minimalist white bg, black text, subtle green accents
- **Typography:** Clear hierarchy, generous line-height, sans-serif
- **Spacing:** Wide margins, breathing room, card-based layouts
- **Trust:** Regulatory info prominent, legal disclaimers clear

#### Bloomberg/TradingView Common Patterns
- **Data Density:** High information density but organized
- **Dark Mode:** Often default for reduced eye strain
- **Fixed Headers:** Ticker info always visible
- **Color Semantics:** Green = up/buy, Red = down/sell (universal)

---

## Design Principles for QuantStream

### 1. Color Palette

**Primary (Dark Theme - Professional Trading Standard):**
- Background: `#0A0E1A` (deep blue-black, not pure black)
- Surface: `#111827` (cards, elevated surfaces)
- Border: `#1F2937` (subtle dividers)
- Text Primary: `#F9FAFB` (off-white, not harsh white)
- Text Secondary: `#9CA3AF` (gray for labels)

**Semantic Colors:**
- Success/Buy: `#10B981` (green)
- Danger/Sell: `#EF4444` (red)
- Warning/Hold: `#F59E0B` (amber)
- Info: `#3B82F6` (blue)

**Chart Colors:**
- Candle Up: `#26A69A` (teal green)
- Candle Down: `#EF5350` (coral red)
- Volume: `#42A5F5` (blue, semi-transparent)

### 2. Typography

**Font Stack:**
```css
font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
```

**Hierarchy:**
- H1 (Page Title): 2xl (24px), font-bold
- H2 (Section): xl (20px), font-semibold
- H3 (Card Title): lg (18px), font-medium
- Body: base (16px), font-normal
- Caption: sm (14px), text-secondary
- Label: xs (12px), text-secondary, uppercase

### 3. Layout Patterns

**Grid System:**
- Sidebar: 64px (collapsed) or 256px (expanded)
- Main content: flex-1 with max-width constraints
- Cards: rounded-lg (8px), subtle shadow
- Spacing: 4-based scale (4, 8, 12, 16, 24, 32, 48)

**Dashboard Sections:**
```
┌─────────────────────────────────────────┐
│ Fixed Navbar (64px)                     │
├────────┬────────────────────────────────┤
│        │                                │
│ Side   │  Main Content Area             │
│ bar    │  - Market Overview (table)     │
│ (64px) │  - Chart (full width)          │
│        │  - Signals Feed (grid)         │
│        │                                │
└────────┴────────────────────────────────┘
```

### 4. Component Patterns

**Table (Market Overview):**
- Sticky header
- Hover highlight row
- Sortable columns
- Monospace for prices
- Color-coded change percentages
- Right-align numbers

**Chart Container:**
- Full-width or 2/3 width
- Fixed aspect ratio (16:9 or 21:9)
- Controls above chart (timeframe, indicators)
- Legend inside chart (top-left)

**Signal Cards:**
- Compact, grid layout (3-4 columns)
- Icon + timestamp + symbol
- Action badge (BUY/SELL with color)
- Confidence percentage

**KPI Cards:**
- Large number (primary metric)
- Change indicator (arrow + percentage)
- Sparkline (micro chart)
- Label below

---

## Technology Stack (Final)

### Core
- React 18 + TypeScript 5
- Vite 6 (build tool)
- React Router 6 (routing)

### UI Components
- Shadcn UI (base components)
- Tremor (dashboard components)
- Tailwind CSS 3 (styling)
- Radix UI (accessibility primitives)

### Charts
- TradingView Lightweight Charts (candlesticks)
- Recharts (analytics graphs)

### State Management
- Zustand (lightweight, 1KB)
- React Query (server state, caching)

### Real-time
- @stomp/stompjs (WebSocket)
- SWR or React Query (polling fallback)

### Utilities
- date-fns (date formatting)
- numeral (number formatting)
- clsx (conditional classes)

---

## Implementation Strategy

### Phase 1: Foundation (Week 4)
1. Setup React + TypeScript + Vite
2. Install Shadcn UI (init project)
3. Configure Tailwind with custom theme
4. Create layout components (Navbar, Sidebar, Container)
5. Setup routing (4 pages)

### Phase 2: Market Dashboard (Week 4-5)
1. Install Lightweight Charts
2. Create MarketTable component (Tremor Table)
3. Create CandlestickChart component (Lightweight Charts)
4. Create SignalFeed component
5. Integrate WebSocket for real-time updates

### Phase 3: Strategy Pages (Week 5-6)
1. Leaderboard with Tremor components
2. Create PerformanceChart (Recharts)
3. Strategy detail page with metrics
4. Backtest playground

### Phase 4: Polish (Week 6-7)
1. Dark mode refinement
2. Loading states (skeleton screens)
3. Error boundaries
4. Animations (Magic UI if time)
5. Accessibility audit

---

## Anti-Patterns to Avoid

❌ **Don't:**
- Use centered cards with huge shadows
- Add gradient backgrounds everywhere
- Over-animate everything
- Use emojis as primary UI elements
- Make everything a modal/dialog
- Use Comic Sans or cursive fonts
- Add confetti or celebration animations
- Use stock photos of happy traders
- Make buttons too rounded (border-radius: 50%)

✅ **Do:**
- Use data tables for financial data
- Keep backgrounds dark and subtle
- Animate only on interaction (hover, click)
- Use icons sparingly, meaningfully
- Use inline forms and dropdowns
- Use Inter or system fonts
- Add subtle transitions (200ms)
- Focus on data, not decoration
- Use consistent border-radius (4-8px)

---

## References

### Design Inspiration
- TradingView (professional standard)
- Robinhood (clean minimalism)
- Bloomberg Terminal (data density)
- Binance (crypto trading)

### Component Libraries
- Shadcn UI: https://ui.shadcn.com/
- Tremor: https://www.tremor.so/
- Lightweight Charts: https://tradingview.github.io/lightweight-charts/

### Resources
- Tailwind UI (paid but good patterns): https://tailwindui.com/
- Tremor Blocks (free templates): https://www.tremor.so/blocks
- Shadcn Themes: https://ui.shadcn.com/themes

---

**Document Status:** ✅ Research Complete  
**Next Step:** Implement backend API Gateway (Task 14)  
**Then:** Frontend implementation (Tasks 21-26)

