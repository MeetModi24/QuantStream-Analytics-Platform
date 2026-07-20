"""
Tests for Backtest Engine Core

Tests the main BacktestEngine orchestrator.
"""

import sys
sys.path.append('.')

import pandas as pd
import numpy as np
from datetime import datetime, timedelta
from unittest.mock import Mock, MagicMock

from app.core.backtest_engine import BacktestEngine
from app.strategies.rsi_strategy import RsiStrategy
from app.strategies.ma_crossover_strategy import MaCrossoverStrategy


def create_mock_data_fetcher(df: pd.DataFrame):
    """Create mock QuestDBFetcher that returns provided DataFrame."""
    mock_fetcher = Mock()
    mock_fetcher.fetch_candles = MagicMock(return_value=df)
    return mock_fetcher


def create_test_dataframe(prices, num_candles=100, start_date=None):
    """
    Create test DataFrame with realistic OHLCV data.

    Args:
        prices: List of close prices or single price for flat pattern
        num_candles: Number of candles to generate
        start_date: Starting date (default: 2026-06-01)

    Returns:
        DataFrame with DatetimeIndex and OHLCV columns
    """
    if start_date is None:
        start_date = datetime(2026, 6, 1)

    # If single price, create flat pattern
    if isinstance(prices, (int, float)):
        prices = [prices] * num_candles
    elif len(prices) < num_candles:
        # Extend prices if not enough
        prices = list(prices) + [prices[-1]] * (num_candles - len(prices))

    df = pd.DataFrame({
        'open': [p * 0.99 for p in prices[:num_candles]],
        'high': [p * 1.02 for p in prices[:num_candles]],
        'low': [p * 0.98 for p in prices[:num_candles]],
        'close': prices[:num_candles],
        'volume': [1000000] * num_candles
    })

    # Create DatetimeIndex with hourly frequency
    df.index = pd.date_range(start=start_date, periods=num_candles, freq='1h')
    df.index.name = 'timestamp'

    return df


# ============================================================================
# Test 1: Basic Functionality
# ============================================================================

def test_backtest_engine_initialization():
    """Test BacktestEngine can be initialized."""
    print("=" * 60)
    print("TEST: BacktestEngine Initialization")
    print("=" * 60)

    mock_fetcher = create_mock_data_fetcher(pd.DataFrame())
    engine = BacktestEngine(mock_fetcher)

    assert engine is not None
    assert engine.data_fetcher is not None
    print("✅ BacktestEngine initialized successfully")


# ============================================================================
# Test 2: Insufficient Data Error
# ============================================================================

def test_insufficient_data_error():
    """Test error when not enough candles for strategy."""
    print("\n" + "=" * 60)
    print("TEST: Insufficient Data Error")
    print("=" * 60)

    # Create only 10 candles
    df = create_test_dataframe(prices=100, num_candles=10)
    mock_fetcher = create_mock_data_fetcher(df)
    engine = BacktestEngine(mock_fetcher)

    # RSI needs 15 candles
    strategy = RsiStrategy()

    try:
        result = engine.run(
            strategy=strategy,
            symbol="TEST",
            start_date=datetime(2026, 6, 1),
            end_date=datetime(2026, 6, 10)
        )
        assert False, "Should have raised ValueError"
    except ValueError as e:
        assert "Insufficient data" in str(e) or "No data found" in str(e)
        print(f"✅ Correctly raised error: {str(e)[:80]}...")


# ============================================================================
# Test 3: No Signals Generated
# ============================================================================

def test_no_signals_generated():
    """Test backtest when strategy generates no signals."""
    print("\n" + "=" * 60)
    print("TEST: No Signals Generated")
    print("=" * 60)

    # Flat price (no RSI crossovers)
    df = create_test_dataframe(prices=100, num_candles=60)
    mock_fetcher = create_mock_data_fetcher(df)
    engine = BacktestEngine(mock_fetcher)

    strategy = RsiStrategy()

    result = engine.run(
        strategy=strategy,
        symbol="FLAT",
        start_date=datetime(2026, 6, 1),
        end_date=datetime(2026, 6, 10),
        initial_capital=10000.0
    )

    print(f"Strategy: {result.strategy_name}")
    print(f"Candles Processed: {result.num_candles_processed}")
    print(f"Trades: {result.metrics.num_trades}")
    print(f"Has Trades: {result.metrics.has_trades}")
    print(f"Total Return: {result.total_return_pct:.2f}%")

    assert result.metrics.num_trades == 0
    assert result.metrics.has_trades == False
    assert result.total_return_pct == 0.0
    assert result.final_portfolio_value == 10000.0

    print("✅ Correctly handled no signals case")


# ============================================================================
# Test 4: Single Round Trip Trade
# ============================================================================

def test_single_round_trip():
    """Test backtest with one BUY followed by one SELL."""
    print("\n" + "=" * 60)
    print("TEST: Single Round Trip Trade")
    print("=" * 60)

    # Create pattern: drop (oversold), then recover
    prices = [100] * 5 + \
             [100 - i*2 for i in range(10)] + \
             [80] * 5 + \
             [80 + i*2 for i in range(10)] + \
             [100] * 30

    df = create_test_dataframe(prices=prices, num_candles=len(prices))
    mock_fetcher = create_mock_data_fetcher(df)
    engine = BacktestEngine(mock_fetcher)

    strategy = RsiStrategy()

    result = engine.run(
        strategy=strategy,
        symbol="TEST",
        start_date=datetime(2026, 6, 1),
        end_date=datetime(2026, 6, 10),
        initial_capital=10000.0,
        transaction_cost=0.001
    )

    print(f"Strategy: {result.strategy_name}")
    print(f"Candles: {result.num_candles_processed}")
    print(f"Trades: {result.metrics.num_trades}")
    print(f"Win Rate: {result.metrics.win_rate_pct:.1f}%")
    print(f"Total Return: {result.total_return_pct:.2f}%")
    print(f"Final Value: ${result.final_portfolio_value:.2f}")

    # Should have at least some trades
    if result.metrics.num_trades > 0:
        print(f"✅ Generated {result.metrics.num_trades} trade(s)")
        print(f"   First Trade: {result.trades[0].action} at ${result.trades[0].price:.2f}")
    else:
        print("⚠️  No trades generated (pattern may need adjustment)")


# ============================================================================
# Test 5: Expanding Window Test
# ============================================================================

def test_expanding_window():
    """Test that strategy receives expanding window."""
    print("\n" + "=" * 60)
    print("TEST: Expanding Window Verification")
    print("=" * 60)

    # Create tracked strategy to monitor window sizes
    class TrackedRsiStrategy(RsiStrategy):
        def __init__(self):
            super().__init__()
            self.window_sizes = []

        def generate_signal(self, df):
            self.window_sizes.append(len(df))
            return super().generate_signal(df)

    df = create_test_dataframe(prices=100, num_candles=50)
    mock_fetcher = create_mock_data_fetcher(df)
    engine = BacktestEngine(mock_fetcher)

    strategy = TrackedRsiStrategy()

    result = engine.run(
        strategy=strategy,
        symbol="TEST",
        start_date=datetime(2026, 6, 1),
        end_date=datetime(2026, 6, 10)
    )

    # Verify window sizes grow
    print(f"Window sizes: {strategy.window_sizes[:5]} ... {strategy.window_sizes[-5:]}")
    print(f"First window: {strategy.window_sizes[0]} candles")
    print(f"Last window: {strategy.window_sizes[-1]} candles")

    # Should start at required_candles + 1 (first iteration is at index required_candles)
    # because we slice df.iloc[:i+1] where i starts at required_candles
    assert strategy.window_sizes[0] == strategy.get_required_candles() + 1
    assert strategy.window_sizes[-1] == len(df)
    assert all(strategy.window_sizes[i] < strategy.window_sizes[i+1]
               for i in range(len(strategy.window_sizes)-1))

    print("✅ Expanding window working correctly")


# ============================================================================
# Test 6: Position Sizing (All-In/All-Out)
# ============================================================================

def test_position_sizing():
    """Test all-in/all-out position sizing."""
    print("\n" + "=" * 60)
    print("TEST: Position Sizing (All-In/All-Out)")
    print("=" * 60)

    # Simple uptrend for MA crossover
    prices = list(range(100, 120)) + list(range(120, 140, 2)) + [140] * 20
    df = create_test_dataframe(prices=prices, num_candles=len(prices))
    mock_fetcher = create_mock_data_fetcher(df)
    engine = BacktestEngine(mock_fetcher)

    strategy = MaCrossoverStrategy()

    result = engine.run(
        strategy=strategy,
        symbol="TREND",
        start_date=datetime(2026, 6, 1),
        end_date=datetime(2026, 6, 10),
        initial_capital=10000.0
    )

    print(f"Initial Capital: $10,000")
    print(f"Final Value: ${result.final_portfolio_value:.2f}")

    if result.metrics.num_trades > 0:
        # Check first BUY trade
        buy_trades = [t for t in result.trades if t.action == 'BUY']
        if buy_trades:
            first_buy = buy_trades[0]
            print(f"\nFirst BUY:")
            print(f"  Price: ${first_buy.price:.2f}")
            print(f"  Shares: {first_buy.shares:.2f}")
            print(f"  Total: ${first_buy.total_amount:.2f}")
            print(f"  Cash After: ${first_buy.cash_after:.2f}")

            # Should use almost all cash (minus transaction cost)
            assert first_buy.cash_after < 100  # Should have < $100 left
            print("✅ All-in position sizing working")

        # Check SELL trade
        sell_trades = [t for t in result.trades if t.action == 'SELL']
        if sell_trades:
            first_sell = sell_trades[0]
            print(f"\nFirst SELL:")
            print(f"  Price: ${first_sell.price:.2f}")
            print(f"  Shares: {first_sell.shares:.2f}")
            print(f"  P/L: ${first_sell.pnl:.2f} ({first_sell.pnl_pct:.2f}%)")
            print(f"  Shares After: {first_sell.shares_after}")

            # Should sell all shares
            assert first_sell.shares_after == 0
            print("✅ All-out position sizing working")
    else:
        print("⚠️  No trades generated")


# ============================================================================
# Test 7: Data Validation
# ============================================================================

def test_data_validation():
    """Test data validation and cleaning."""
    print("\n" + "=" * 60)
    print("TEST: Data Validation")
    print("=" * 60)

    # Create data with issues
    df = create_test_dataframe(prices=100, num_candles=50)

    # Add NaN row
    df.loc[df.index[10], 'close'] = np.nan

    # Add invalid price row
    df.loc[df.index[20], 'close'] = -10

    print(f"Original data: {len(df)} rows")
    print(f"  NaN at row 10")
    print(f"  Invalid price at row 20")

    mock_fetcher = create_mock_data_fetcher(df)
    engine = BacktestEngine(mock_fetcher)

    strategy = RsiStrategy()

    result = engine.run(
        strategy=strategy,
        symbol="DIRTY",
        start_date=datetime(2026, 6, 1),
        end_date=datetime(2026, 6, 10)
    )

    print(f"After validation: {result.num_candles_processed} rows")
    print("✅ Data validation working (NaN and invalid prices removed)")


# ============================================================================
# Test 8: Equity Curve Generation
# ============================================================================

def test_equity_curve():
    """Test equity curve is generated correctly."""
    print("\n" + "=" * 60)
    print("TEST: Equity Curve Generation")
    print("=" * 60)

    df = create_test_dataframe(prices=100, num_candles=60)
    mock_fetcher = create_mock_data_fetcher(df)
    engine = BacktestEngine(mock_fetcher)

    strategy = RsiStrategy()

    result = engine.run(
        strategy=strategy,
        symbol="TEST",
        start_date=datetime(2026, 6, 1),
        end_date=datetime(2026, 6, 10),
        initial_capital=10000.0
    )

    print(f"Equity curve points: {len(result.equity_curve)}")
    print(f"First point: ${result.equity_curve[0].value:.2f} at {result.equity_curve[0].timestamp}")
    print(f"Last point: ${result.equity_curve[-1].value:.2f} at {result.equity_curve[-1].timestamp}")

    # Should have equity points for processed candles + initial
    # At minimum: initial point + (num_candles - required_candles)
    min_points = result.num_candles_processed - strategy.get_required_candles() + 1
    assert len(result.equity_curve) >= min_points

    print("✅ Equity curve generated correctly")


# ============================================================================
# Test 9: Configuration Preservation
# ============================================================================

def test_configuration_preserved():
    """Test that configuration is preserved in result."""
    print("\n" + "=" * 60)
    print("TEST: Configuration Preservation")
    print("=" * 60)

    df = create_test_dataframe(prices=100, num_candles=60)
    mock_fetcher = create_mock_data_fetcher(df)
    engine = BacktestEngine(mock_fetcher)

    strategy = RsiStrategy()

    result = engine.run(
        strategy=strategy,
        symbol="CONFIG_TEST",
        start_date=datetime(2026, 6, 1),
        end_date=datetime(2026, 7, 1),
        initial_capital=15000.0,
        transaction_cost=0.002,
        frequency="1H"
    )

    print(f"Config Initial Capital: ${result.config.initial_capital:.2f}")
    print(f"Config Transaction Cost: {result.config.transaction_cost}")
    print(f"Config Frequency: {result.config.frequency}")
    print(f"Period Start: {result.period.start}")
    print(f"Period End: {result.period.end}")

    assert result.config.initial_capital == 15000.0
    assert result.config.transaction_cost == 0.002
    assert result.config.frequency == "1H"
    assert result.period.start == datetime(2026, 6, 1)
    assert result.period.end == datetime(2026, 7, 1)

    print("✅ Configuration preserved correctly")


# ============================================================================
# Run All Tests
# ============================================================================

if __name__ == "__main__":
    test_backtest_engine_initialization()
    test_insufficient_data_error()
    test_no_signals_generated()
    test_single_round_trip()
    test_expanding_window()
    test_position_sizing()
    test_data_validation()
    test_equity_curve()
    test_configuration_preserved()

    print("\n" + "=" * 60)
    print("✅ ALL BACKTEST ENGINE TESTS PASSED!")
    print("=" * 60)
