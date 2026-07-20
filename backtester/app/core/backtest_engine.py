"""
Backtest Engine Core

Orchestrates the complete backtesting pipeline:
1. Fetch historical data
2. Validate data quality
3. Execute backtest loop
4. Calculate metrics
5. Return results
"""

import pandas as pd
from datetime import datetime
from typing import Optional
from app.core.data_fetcher import QuestDBFetcher
from app.core.portfolio import Portfolio
from app.core.metrics import MetricsCalculator
from app.strategies.base_strategy import BaseStrategy
from app.models.backtest_result import BacktestResult, BacktestConfig, DateRange


class BacktestEngine:
    """
    Orchestrates backtesting pipeline.

    Responsibilities:
    - Coordinate data fetching
    - Validate data quality
    - Execute backtest loop with expanding window
    - Generate comprehensive results

    NOT responsible for:
    - Indicator calculation (done by strategies)
    - Trade execution logic (done by Portfolio)
    - Metrics calculation (done by MetricsCalculator)

    Example:
        >>> fetcher = DataFetcher(db_config)
        >>> engine = BacktestEngine(fetcher)
        >>> result = engine.run(
        ...     strategy=RsiStrategy(),
        ...     symbol="AAPL",
        ...     start_date=datetime(2026, 6, 19),
        ...     end_date=datetime(2026, 7, 19)
        ... )
        >>> print(f"Return: {result.total_return_pct:.2f}%")
    """

    def __init__(self, data_fetcher: QuestDBFetcher):
        """
        Initialize engine with data fetcher.

        Args:
            data_fetcher: QuestDBFetcher instance for querying QuestDB
        """
        self.data_fetcher = data_fetcher

    def run(
        self,
        strategy: BaseStrategy,
        symbol: str,
        start_date: datetime,
        end_date: datetime,
        initial_capital: float = 10000.0,
        transaction_cost: float = 0.001,
        frequency: str = "1H"
    ) -> BacktestResult:
        """
        Run backtest and return complete results.

        Args:
            strategy: Strategy instance (e.g., RsiStrategy())
            symbol: Trading symbol (e.g., "AAPL")
            start_date: Backtest start date
            end_date: Backtest end date
            initial_capital: Starting cash (default: $10,000)
            transaction_cost: Fee as decimal (default: 0.001 = 0.1%)
            frequency: Candle frequency (default: "1H" for 1-hour candles)

        Returns:
            BacktestResult with metrics, trades, and equity curve

        Raises:
            ValueError: If insufficient data or invalid parameters

        Design:
            - Uses expanding window (strategy sees all past data)
            - All-in/all-out position sizing (simple)
            - Executes all signals regardless of confidence
        """
        # Step 1: Fetch historical data
        df = self.data_fetcher.fetch_candles(
            symbol=symbol,
            start_date=start_date.isoformat(),
            end_date=end_date.isoformat(),
            frequency=frequency
        )

        # Step 2: Validate and clean data
        df = self._validate_data(df, strategy, symbol, start_date, end_date)

        # Step 3: Initialize components
        strategy.reset_state()
        portfolio = Portfolio(
            initial_capital=initial_capital,
            transaction_cost=transaction_cost
        )

        # Step 4: Execute main backtest loop
        self._execute_backtest_loop(df, strategy, portfolio)

        # Step 5: Build and return result
        result = self._build_result(
            strategy=strategy,
            symbol=symbol,
            portfolio=portfolio,
            start_date=start_date,
            end_date=end_date,
            initial_capital=initial_capital,
            transaction_cost=transaction_cost,
            frequency=frequency,
            num_candles=len(df)
        )

        return result

    def _validate_data(
        self,
        df: pd.DataFrame,
        strategy: BaseStrategy,
        symbol: str,
        start_date: datetime,
        end_date: datetime
    ) -> pd.DataFrame:
        """
        Validate and clean historical data.

        Checks:
        1. DataFrame is not empty
        2. Sufficient candles for strategy
        3. No NaN values in critical columns
        4. Price values are positive
        5. Data is sorted by timestamp

        Args:
            df: Raw DataFrame from data fetcher
            strategy: Strategy instance (to check required candles)
            symbol: Symbol being backtested (for error messages)
            start_date: Start date (for error messages)
            end_date: End date (for error messages)

        Returns:
            Cleaned and validated DataFrame

        Raises:
            ValueError: If data is invalid or insufficient
        """
        # Check 1: Empty DataFrame
        if df.empty:
            raise ValueError(
                f"No data found for {symbol} between {start_date} and {end_date}"
            )

        # Check 2: Required columns exist
        required_columns = ['open', 'high', 'low', 'close', 'volume']
        missing_columns = [col for col in required_columns if col not in df.columns]
        if missing_columns:
            raise ValueError(
                f"Missing required columns: {missing_columns}"
            )

        # Check 3: Remove rows with NaN in critical columns
        original_len = len(df)
        df = df.dropna(subset=required_columns)
        if len(df) < original_len:
            dropped = original_len - len(df)
            print(f"Warning: Dropped {dropped} rows with NaN values")

        # Check 4: Validate positive prices
        invalid_prices = df[df['close'] <= 0]
        if not invalid_prices.empty:
            df = df[df['close'] > 0]
            print(f"Warning: Dropped {len(invalid_prices)} rows with invalid prices")

        # Check 5: Ensure sorted by timestamp
        if not df.index.is_monotonic_increasing:
            df = df.sort_index()

        # Check 6: Sufficient candles for strategy
        required_candles = strategy.get_required_candles()
        if len(df) < required_candles:
            raise ValueError(
                f"Insufficient data for {strategy.name} strategy: "
                f"{len(df)} candles available, {required_candles} required. "
                f"Try expanding the date range or using a lower frequency."
            )

        return df

    def _execute_backtest_loop(
        self,
        df: pd.DataFrame,
        strategy: BaseStrategy,
        portfolio: Portfolio
    ) -> None:
        """
        Execute main backtest loop with expanding window.

        For each candle:
        1. Generate signal from strategy (with expanding window)
        2. Execute trade if signal exists
        3. Update portfolio value

        Args:
            df: Validated DataFrame with OHLCV data
            strategy: Strategy instance
            portfolio: Portfolio instance

        Design Decisions:
        - Expanding Window: Strategy sees all data from start to current index
          (mimics real-world where strategy has access to all historical data)
        - All-in/All-out: BUY = buy max shares, SELL = sell all shares
        - State Persistence: Strategy maintains state between iterations
        """
        required_candles = strategy.get_required_candles()

        # Start loop after we have enough data for strategy
        for i in range(required_candles, len(df)):
            # Get expanding window (all data from start to current index)
            current_window = df.iloc[:i+1]
            current_candle = df.iloc[i]

            # Extract current values
            current_price = current_candle['close']
            timestamp = current_candle.name  # Index is timestamp

            # Generate signal from strategy
            signal = strategy.generate_signal(current_window)

            # Execute trade based on signal
            if signal is not None:
                if signal.action == "BUY":
                    # Only buy if we have cash and no position
                    if portfolio.cash > 0 and portfolio.shares == 0:
                        max_shares = portfolio.calculate_max_shares(current_price)
                        if max_shares > 0:
                            portfolio.buy(max_shares, current_price, timestamp)

                elif signal.action == "SELL":
                    # Only sell if we have shares
                    if portfolio.shares > 0:
                        portfolio.sell(portfolio.shares, current_price, timestamp)

            # Update portfolio value at end of candle (builds equity curve)
            portfolio.update_value(current_price, timestamp)

    def _build_result(
        self,
        strategy: BaseStrategy,
        symbol: str,
        portfolio: Portfolio,
        start_date: datetime,
        end_date: datetime,
        initial_capital: float,
        transaction_cost: float,
        frequency: str,
        num_candles: int
    ) -> BacktestResult:
        """
        Build final BacktestResult object.

        Args:
            strategy: Strategy instance
            symbol: Trading symbol
            portfolio: Portfolio after backtest completion
            start_date: Backtest start date
            end_date: Backtest end date
            initial_capital: Starting capital
            transaction_cost: Transaction cost used
            frequency: Candle frequency used
            num_candles: Number of candles processed

        Returns:
            Complete BacktestResult object
        """
        # Calculate metrics
        metrics_calculator = MetricsCalculator(portfolio)
        metrics = metrics_calculator.calculate_all_metrics()

        # Build configuration object
        config = BacktestConfig(
            initial_capital=initial_capital,
            transaction_cost=transaction_cost,
            frequency=frequency
        )

        # Build date range object
        period = DateRange(
            start=start_date,
            end=end_date
        )

        # Get portfolio state
        portfolio_state = portfolio.get_state()

        # Build result
        result = BacktestResult(
            strategy_name=strategy.name,
            symbol=symbol,
            period=period,
            config=config,
            metrics=metrics,
            trades=portfolio_state.trades,
            equity_curve=portfolio_state.equity_curve,
            final_portfolio_value=portfolio.current_value,
            total_return_pct=portfolio.total_return,
            num_candles_processed=num_candles
        )

        return result
