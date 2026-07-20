"""
Production End-to-End Testing

Tests the complete backtesting pipeline with real QuestDB data.
NO MOCKS. NO SYNTHETIC DATA. Pure production testing.

Usage:
    python tests/test_production_end_to_end.py
"""

import sys
sys.path.append('.')

from datetime import datetime, timedelta
from app.core.data_fetcher import QuestDBFetcher
from app.core.backtest_engine import BacktestEngine
from app.strategies import (
    RsiStrategy, MaCrossoverStrategy, MacdStrategy,
    BollingerBandsStrategy, StochasticStrategy, WilliamsRStrategy,
    AdxStrategy, DonchianChannelStrategy, RocStrategy, VwapStrategy
)
import traceback


def main():
    """Run production tests."""
    print("\n" + "="*80)
    print("PRODUCTION END-TO-END TEST")
    print("="*80)
    print("Testing: Data Generator → Kafka → Database Consumer → QuestDB → Backtester")
    print("="*80 + "\n")

    # Setup
    print("Setting up connections...")
    data_fetcher = QuestDBFetcher()
    engine = BacktestEngine(data_fetcher)
    print("✅ Connections established\n")

    # Test configuration
    end_date = datetime.now()
    start_date = end_date - timedelta(days=30)
    symbols = ['AAPL', 'GOOGL', 'MSFT']

    strategies = [
        ('RSI', RsiStrategy()),
        ('MA Crossover', MaCrossoverStrategy()),
        ('MACD', MacdStrategy()),
    ]

    print(f"Test Plan:")
    print(f"  Period: {start_date.date()} to {end_date.date()}")
    print(f"  Symbols: {', '.join(symbols)}")
    print(f"  Strategies: {len(strategies)}")
    print(f"  Total tests: {len(strategies) * len(symbols)}\n")

    # Run tests
    passed = 0
    failed = 0

    for strategy_name, strategy in strategies:
        for symbol in symbols:
            test_name = f"{strategy_name}/{symbol}"
            print(f"\n{'='*80}")
            print(f"Testing: {test_name}")
            print(f"{'='*80}")

            try:
                # Run backtest
                result = engine.run(
                    strategy=strategy,
                    symbol=symbol,
                    start_date=start_date,
                    end_date=end_date,
                    initial_capital=10000.0,
                    transaction_cost=0.001,
                    frequency="1H"
                )

                # Display results
                print(f"\n📊 Results:")
                print(f"  Total Return: {result.total_return_pct:.2f}%")
                print(f"  Final Value: ${result.final_portfolio_value:,.2f}")
                print(f"  Sharpe Ratio: {result.metrics.sharpe_ratio:.2f}")
                print(f"  Trades: {result.metrics.num_trades}")
                print(f"  Win Rate: {result.metrics.win_rate_pct:.1f}%")
                print(f"  Max Drawdown: {result.metrics.max_drawdown_pct:.2f}%")
                print(f"  Candles Processed: {result.num_candles_processed}")

                print(f"\n✅ {test_name}: PASSED")
                passed += 1

            except Exception as e:
                print(f"\n❌ {test_name}: FAILED")
                print(f"Error: {e}")
                traceback.print_exc()
                failed += 1

    # Final report
    print("\n" + "="*80)
    print("FINAL REPORT")
    print("="*80)
    print(f"Total: {passed + failed}")
    print(f"✅ Passed: {passed}")
    print(f"❌ Failed: {failed}")

    if failed == 0:
        print("\n✅ ALL TESTS PASSED - PIPELINE WORKING END-TO-END")
    else:
        print(f"\n❌ {failed} TEST(S) FAILED")

    print("="*80 + "\n")

    sys.exit(0 if failed == 0 else 1)


if __name__ == '__main__':
    main()
