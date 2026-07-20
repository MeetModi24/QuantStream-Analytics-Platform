# Task 7 & 8: Strategy Implementation Guide

## Overview

This guide covers Tasks 7 and 8 from the Phase 3 task list:
- **Task 7**: Base Strategy Class - Abstract base class for all strategies
- **Task 8**: Implement 10 Trading Strategies - All strategy implementations matching Phase 2 logic

The goal is **100% consistency** with Phase 2 Java implementations in `/Users/mhiteshkumar/QuantStream/strategy-engine/src/main/java/com/quantstream/strategy/strategies/`.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Key Differences: Production vs Backtesting](#key-differences-production-vs-backtest
ing)
3. [Base Strategy Design](#base-strategy-design)
4. [State Management](#state-management)
5. [All 10 Strategies](#all-10-strategies)
6. [Signal Model](#signal-model)
7. [Integration with Backtest Engine](#integration-with-backtest-engine)
8. [Testing Approach](#testing-approach)
9. [Implementation Checklist](#implementation-checklist)

---

## Architecture Overview

### Phase 2 (Production) vs Phase 3 (Backtesting)

| Aspect | Phase 2 Production | Phase 3 Backtesting |
|--------|-------------------|---------------------|
| **Execution** | Scheduled (every 60s) | Loop through historical candles |
| **Data Query** | Fetch from QuestDB live | Pre-fetched DataFrame |
| **Data Order** | DESC (newest first) | ASC (chronological) |
| **State** | HashMap across runs | Instance variables |
| **First Run** | Skip signal (init state) | Handle in loop |
| **Output** | Store signal in DB | Return Signal object |

### Critical Pattern: Crossover Detection

**All strategies detect CROSSINGS, not just current values.**

Example - RSI Strategy:
```java
// Phase 2 (Java)
if (rsi > OVERSOLD && prevRSI <= OVERSOLD) {  // CROSSING detected
    return createSignal("BUY", confidence);
}
```

```python
# Phase 3 (Python)
if current_rsi > OVERSOLD and self.prev_rsi <= OVERSOLD:  # Same logic
    return Signal(action="BUY", confidence=confidence)
```

---

## Base Strategy Design

### File Structure
```
app/
├── models/
│   └── signal.py              # Signal Pydantic model
└── strategies/
    ├── __init__.py
    ├── base_strategy.py        # Abstract base class
    ├── rsi_strategy.py         # Strategy 1
    ├── ma_crossover_strategy.py  # Strategy 2
    └── ... (8 more strategies)
```

### Signal Model (`app/models/signal.py`)

```python
from pydantic import BaseModel, Field
from typing import Literal

class Signal(BaseModel):
    """Trading signal with action and confidence."""
    action: Literal["BUY", "SELL"] = Field(description="Trading action")
    confidence: float = Field(ge=0.0, le=1.0, description="Signal confidence (0.7-0.9)")
```

### BaseStrategy Class (`app/strategies/base_strategy.py`)

```python
from abc import ABC, abstractmethod
from typing import Optional
import pandas as pd
from app.models.signal import Signal

class BaseStrategy(ABC):
    """Abstract base class for all trading strategies."""
    
    def __init__(self, name: str):
        self.name = name
        self.reset_state()
    
    @abstractmethod
    def reset_state(self):
        """Reset internal state variables. Called at start of backtest."""
        pass
    
    @abstractmethod
    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        """
        Generate trading signal based on historical data.
        
        Args:
            df: DataFrame with columns ['timestamp', 'open', 'high', 'low', 'close', 'volume']
                Sorted chronologically (oldest first)
                Contains sufficient lookback window
        
        Returns:
            Signal (BUY/SELL with confidence) or None
        """
        pass
    
    @abstractmethod
    def get_required_candles(self) -> int:
        """Return minimum candles needed for this strategy."""
        pass
```

---

## State Management

### Phase 2 Pattern (Java)
```java
private final Map<String, Double> previousRSI = new HashMap<>();

public Signal evaluate(String symbol) {
    Double prevRSI = previousRSI.get(symbol);
    if (prevRSI == null) {
        previousRSI.put(symbol, currentRSI);
        return null;  // First run
    }
    // Detect crossover using prevRSI
}
```

### Phase 3 Pattern (Python)
```python
class RsiStrategy(BaseStrategy):
    def reset_state(self):
        self.prev_rsi = None  # Initialize state
    
    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        current_rsi = calculate_rsi(df['close'], period=14)
        
        if self.prev_rsi is None:  # First run
            self.prev_rsi = current_rsi
            return None
        
        # Detect crossover using self.prev_rsi
        if current_rsi > 30 and self.prev_rsi <= 30:
            signal = Signal(action="BUY", confidence=0.85)
        
        self.prev_rsi = current_rsi  # Update state
        return signal
```

**Key Points:**
1. Use `self.prev_*` for state variables
2. Initialize in `reset_state()`
3. Check `if self.prev_* is None` for first run
4. **Always update state before returning**

---

## All 10 Strategies

### 1. RSI Strategy (`rsi_strategy.py`)

**Original:** `RsiStrategy.java`

**Parameters:**
- RSI Period: 14
- Oversold: 30
- Overbought: 70

**Logic:**
- **BUY:** RSI crosses ABOVE 30 (was ≤ 30, now > 30)
- **SELL:** RSI crosses BELOW 70 (was ≥ 70, now < 70)

**State:** `prev_rsi`

**Confidence:**
```python
# BUY confidence (more oversold = higher confidence)
bonus = (30 - rsi) / 30 * 0.15
confidence = min(0.90, 0.75 + bonus)

# SELL confidence (more overbought = higher confidence)
bonus = (rsi - 70) / 30 * 0.15
confidence = min(0.90, 0.75 + bonus)
```

**Implementation:**
```python
from app.core.indicators import calculate_rsi

class RsiStrategy(BaseStrategy):
    RSI_PERIOD = 14
    OVERSOLD = 30.0
    OVERBOUGHT = 70.0
    
    def __init__(self):
        super().__init__(name="RSI Mean Reversion")
        
    def reset_state(self):
        self.prev_rsi = None
    
    def get_required_candles(self) -> int:
        return self.RSI_PERIOD + 1  # 15
    
    def generate_signal(self, df: pd.DataFrame) -> Optional[Signal]:
        current_rsi = calculate_rsi(df['close'], period=self.RSI_PERIOD)
        
        if self.prev_rsi is None:
            self.prev_rsi = current_rsi
            return None
        
        signal = None
        if current_rsi > self.OVERSOLD and self.prev_rsi <= self.OVERSOLD:
            confidence = self._calculate_buy_confidence(current_rsi)
            signal = Signal(action="BUY", confidence=confidence)
        elif current_rsi < self.OVERBOUGHT and self.prev_rsi >= self.OVERBOUGHT:
            confidence = self._calculate_sell_confidence(current_rsi)
            signal = Signal(action="SELL", confidence=confidence)
        
        self.prev_rsi = current_rsi
        return signal
    
    def _calculate_buy_confidence(self, rsi: float) -> float:
        bonus = (self.OVERSOLD - rsi) / self.OVERSOLD * 0.15
        return min(0.90, 0.75 + bonus)
    
    def _calculate_sell_confidence(self, rsi: float) -> float:
        bonus = (rsi - self.OVERBOUGHT) / (100 - self.OVERBOUGHT) * 0.15
        return min(0.90, 0.75 + bonus)
```

---

### 2. MA Crossover Strategy (`ma_crossover_strategy.py`)

**Original:** `MaCrossoverStrategy.java`

**Parameters:**
- MA10: 10 periods
- MA50: 50 periods

**Logic:**
- **BUY:** Golden Cross (MA10 crosses above MA50)
- **SELL:** Death Cross (MA10 crosses below MA50)

**State:** `prev_ma10`, `prev_ma50`

**Required Candles:** 50 (largest of all strategies)

---

### 3. MACD Strategy (`macd_strategy.py`)

**Original:** `MacdStrategy.java`

**Parameters:**
- Fast: 12, Slow: 26, Signal: 9
- MIN_PRICES: 35

**Logic:**
- **BUY:** MACD line crosses above signal line
- **SELL:** MACD line crosses below signal line

**State:** `prev_macd`, `prev_signal`

---

### 4. Bollinger Bands Strategy (`bollinger_bands_strategy.py`)

**Original:** `BollingerBandsStrategy.java`

**Parameters:**
- Period: 20
- Std Dev: 2.0

**Logic:**
- **BUY:** Price crosses INTO lower band zone
- **SELL:** Price crosses INTO upper band zone

**State:** `was_above_upper`, `was_below_lower` (boolean flags)

---

### 5. Stochastic Strategy (`stochastic_strategy.py`)

**Original:** `StochasticStrategy.java`

**Parameters:**
- %K Period: 14
- %D Period: 3
- Oversold: 20, Overbought: 80

**Logic:**
- **BUY:** %K crosses above %D in oversold zone (%K < 30)
- **SELL:** %K crosses below %D in overbought zone (%K > 70)

**State:** `prev_k`, `prev_d`

---

### 6. Williams %R Strategy (`williams_r_strategy.py`)

**Original:** `WilliamsRStrategy.java`

**Parameters:**
- Period: 14
- Oversold: -80, Overbought: -20

**Logic:**
- **BUY:** %R crosses above -80
- **SELL:** %R crosses below -20

**State:** `prev_r`

**Note:** Williams %R uses negative scale (-100 to 0)

---

### 7. ADX Strategy (`adx_strategy.py`)

**Original:** `AdxStrategy.java`

**Parameters:**
- Period: 14
- Min Trend Strength: 25

**Logic:**
- **BUY:** +DI crosses above -DI AND ADX > 25
- **SELL:** -DI crosses above +DI AND ADX > 25

**State:** `prev_plus_di`, `prev_minus_di`

**Key:** Only trades in strong trends (ADX > 25)

---

### 8. Donchian Channel Strategy (`donchian_channel_strategy.py`)

**Original:** `DonchianChannelStrategy.java`

**Parameters:**
- Period: 20

**Logic:**
- **BUY:** Price breaks above upper channel
- **SELL:** Price breaks below lower channel

**State:** `was_above_upper`, `was_below_lower`

---

### 9. ROC Strategy (`roc_strategy.py`)

**Original:** `RocStrategy.java`

**Parameters:**
- Period: 10
- Min Threshold: 2.0%

**Logic:**
- **BUY:** ROC crosses above 0 AND ROC > 2.0
- **SELL:** ROC crosses below 0 AND ROC < -2.0

**State:** `prev_roc`

**Key:** Filters weak signals near zero

---

### 10. VWAP Strategy (`vwap_strategy.py`)

**Original:** `VwapStrategy.java`

**Parameters:**
- Period: 50
- Min Deviation: 0.5%

**Logic:**
- **BUY:** Price crosses below VWAP with ≥0.5% deviation
- **SELL:** Price crosses above VWAP with ≥0.5% deviation

**State:** `was_above_vwap`

**Note:** Requires volume data (uses `calculate_vwap()`)

---

## Integration with Backtest Engine

### Backtest Loop (Pseudocode)

```python
def run_backtest(symbol: str, start: datetime, end: datetime, 
                strategy: BaseStrategy) -> BacktestResult:
    # 1. Fetch data
    df = data_fetcher.fetch_candles(symbol, start, end, interval="1h")
    
    # 2. Initialize
    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    strategy.reset_state()
    
    required = strategy.get_required_candles()
    if len(df) < required:
        raise ValueError(f"Need {required} candles, have {len(df)}")
    
    # 3. Loop through candles
    for i in range(required, len(df)):
        window = df.iloc[:i+1]  # Lookback window
        current_price = window['close'].iloc[-1]
        timestamp = window['timestamp'].iloc[-1]
        
        # Generate signal
        signal = strategy.generate_signal(window)
        
        # Execute trades
        if signal:
            if signal.action == "BUY" and portfolio.shares == 0:
                shares = portfolio.calculate_max_shares(current_price)
                portfolio.buy(shares, current_price, timestamp)
            elif signal.action == "SELL" and portfolio.shares > 0:
                portfolio.sell(portfolio.shares, current_price, timestamp)
        
        # Update equity curve
        portfolio.update_value(current_price, timestamp)
    
    # 4. Calculate metrics
    calculator = MetricsCalculator(portfolio)
    metrics = calculator.calculate_all_metrics()
    
    return BacktestResult(
        strategy_name=strategy.name,
        metrics=metrics,
        trades=portfolio.trades,
        equity_curve=portfolio.equity_curve
    )
```

---

## Testing Approach

### Unit Tests (`tests/test_strategies.py`)

```python
def test_rsi_buy_signal():
    """Test RSI generates BUY when crossing above oversold."""
    strategy = RsiStrategy()
    
    # Create data with RSI crossing above 30
    prices = create_oversold_recovery_pattern()
    df = pd.DataFrame({'close': prices})
    df['timestamp'] = pd.date_range(start='2024-01-01', periods=len(prices), freq='1H')
    
    # First call: initialize
    signal1 = strategy.generate_signal(df.iloc[:15])
    assert signal1 is None
    
    # Second call: RSI crosses above 30
    signal2 = strategy.generate_signal(df.iloc[:18])
    assert signal2 is not None
    assert signal2.action == "BUY"
    assert 0.7 <= signal2.confidence <= 0.9


def test_strategy_state_reset():
    """Test that reset_state() clears all state."""
    strategy = RsiStrategy()
    
    # Populate state
    df = create_test_dataframe([100, 95, 90, 85] * 5)
    strategy.generate_signal(df)
    assert strategy.prev_rsi is not None
    
    # Reset
    strategy.reset_state()
    assert strategy.prev_rsi is None
```

### Integration Test

```python
def test_full_backtest_all_strategies():
    """Run backtest with all 10 strategies on real data."""
    strategies = [
        RsiStrategy(),
        MaCrossoverStrategy(),
        MacdStrategy(),
        BollingerBandsStrategy(),
        StochasticStrategy(),
        WilliamsRStrategy(),
        AdxStrategy(),
        DonchianChannelStrategy(),
        RocStrategy(),
        VwapStrategy()
    ]
    
    for strategy in strategies:
        result = run_backtest("AAPL", "2024-01-01", "2024-01-31", strategy)
        
        # Verify structure
        assert result.metrics.has_trades in [True, False]
        assert result.metrics.total_return_pct is not None
        assert len(result.equity_curve) > 0
        
        print(f"{strategy.name}: {result.metrics.total_return_pct:.2f}%")
```

---

## Implementation Checklist

### Phase 1: Foundation
- [ ] Create `app/models/signal.py`
- [ ] Create `app/strategies/__init__.py`
- [ ] Create `app/strategies/base_strategy.py`
- [ ] Test BaseStrategy (cannot instantiate abstract class)

### Phase 2: Implement Strategies
- [ ] `app/strategies/rsi_strategy.py`
- [ ] `app/strategies/ma_crossover_strategy.py`
- [ ] `app/strategies/macd_strategy.py`
- [ ] `app/strategies/bollinger_bands_strategy.py`
- [ ] `app/strategies/stochastic_strategy.py`
- [ ] `app/strategies/williams_r_strategy.py`
- [ ] `app/strategies/adx_strategy.py`
- [ ] `app/strategies/donchian_channel_strategy.py`
- [ ] `app/strategies/roc_strategy.py`
- [ ] `app/strategies/vwap_strategy.py`

### Phase 3: Testing
- [ ] Create `tests/test_strategies.py`
- [ ] Test each strategy individually
- [ ] Test state reset functionality
- [ ] Test required_candles values
- [ ] Integration test with real data

### Phase 4: Export
- [ ] Update `app/strategies/__init__.py` to export all strategies
- [ ] Create strategy registry/factory (optional)

---

## Success Criteria

✅ All 10 strategies implemented  
✅ Each strategy extends BaseStrategy  
✅ Logic matches Phase 2 Java implementations 100%  
✅ State management works correctly (crossover detection)  
✅ Confidence calculations match Phase 2  
✅ All tests pass  
✅ Can run backtest with any strategy  
✅ Required candles declared correctly  

---

## Next Steps

1. **Read this guide thoroughly**
2. **Verify Phase 2 logic** by reading Java files
3. **Implement Signal model** first
4. **Implement BaseStrategy** class
5. **Implement each strategy** one by one
6. **Test as you go** (don't wait until all 10 are done)
7. **Run integration test** with real QuestDB data

---

## Reference

**Phase 2 Strategy Files:**
- `/Users/mhiteshkumar/QuantStream/strategy-engine/src/main/java/com/quantstream/strategy/strategies/`

**Key Pattern:**
- All strategies use **crossover detection** (not just thresholds)
- All strategies track **previous values** for crossover logic
- All strategies return **null on first run** (initialization)
- All strategies calculate **confidence 0.7-0.9** based on signal strength

**Remember:** The goal is 100% consistency with Phase 2, not to improve strategies.
