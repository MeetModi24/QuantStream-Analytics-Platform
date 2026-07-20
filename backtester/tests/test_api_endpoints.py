"""
API Endpoint Tests

Tests for Tasks 10 & 11: Backtest execution and results retrieval.
"""

import pytest
import time
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


# =============================================================================
# Task 10: Run Backtests & Check Status
# =============================================================================

def test_root_endpoint():
    """Test root endpoint returns correct information."""
    response = client.get("/")
    assert response.status_code == 200
    data = response.json()
    assert "message" in data
    assert "version" in data
    assert "status" in data
    assert data["status"] == "operational"


def test_health_check():
    """Test health check endpoint."""
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "healthy"}


def test_list_strategies():
    """Test listing available strategies."""
    response = client.get("/api/v1/backtest/strategies")
    assert response.status_code == 200
    data = response.json()
    assert "strategies" in data
    assert "total" in data
    assert len(data["strategies"]) == 10
    assert "RSI" in data["strategies"]
    assert "MACD" in data["strategies"]


def test_run_backtest_valid():
    """Test running a valid backtest."""
    response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })

    assert response.status_code == 202
    data = response.json()
    assert "backtest_id" in data
    assert data["status"] == "pending"
    assert "check_status_url" in data

    return data["backtest_id"]


def test_run_backtest_invalid_dates():
    """Test validation error for invalid date range."""
    response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-07-20T00:00:00",  # After end_date
        "end_date": "2026-06-20T00:00:00"
    })

    assert response.status_code == 422  # Validation error


def test_run_backtest_invalid_strategy():
    """Test error for invalid strategy name."""
    response = client.post("/api/v1/backtest/run", json={
        "strategy": "INVALID_STRATEGY",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })

    assert response.status_code == 422  # Validation error (invalid enum)


def test_check_status():
    """Test checking backtest status."""
    # First run a backtest
    run_response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })
    backtest_id = run_response.json()["backtest_id"]

    # Check status immediately (should be pending or running)
    status_response = client.get(f"/api/v1/backtest/status/{backtest_id}")
    assert status_response.status_code == 200
    status_data = status_response.json()
    assert status_data["status"] in ["pending", "running", "completed"]

    # Wait for completion
    max_attempts = 30
    for _ in range(max_attempts):
        status_response = client.get(f"/api/v1/backtest/status/{backtest_id}")
        status = status_response.json()["status"]
        if status == "completed":
            break
        time.sleep(1)

    assert status == "completed"


def test_check_status_not_found():
    """Test 404 for non-existent backtest."""
    response = client.get("/api/v1/backtest/status/invalid-id")
    assert response.status_code == 404


def test_batch_backtest():
    """Test batch backtest execution."""
    response = client.post("/api/v1/backtest/batch", json={
        "strategies": ["RSI", "MACD"],
        "symbols": ["AAPL", "GOOGL"],
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })

    assert response.status_code == 202
    data = response.json()
    assert data["total_backtests"] == 4  # 2 strategies × 2 symbols
    assert len(data["backtest_ids"]) == 4
    assert "batch_id" in data


def test_recent_backtests():
    """Test listing recent backtests."""
    # Run a backtest first
    client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })

    # Get recent list
    response = client.get("/api/v1/backtest/recent?limit=10")
    assert response.status_code == 200
    data = response.json()
    assert "backtests" in data
    assert len(data["backtests"]) > 0
    assert data["limit"] == 10


# =============================================================================
# Task 11: Results & Comparison
# =============================================================================

def test_get_summary():
    """Test getting backtest summary (metrics only)."""
    # Run backtest
    run_response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })
    backtest_id = run_response.json()["backtest_id"]

    # Wait for completion
    time.sleep(3)

    # Get summary
    response = client.get(f"/api/v1/backtest/{backtest_id}/summary")
    assert response.status_code == 200
    data = response.json()
    assert "total_return_pct" in data
    assert "sharpe_ratio" in data
    assert "win_rate_pct" in data
    assert "max_drawdown_pct" in data
    assert "num_trades" in data


def test_get_full_results():
    """Test getting complete backtest results."""
    # Run backtest
    run_response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })
    backtest_id = run_response.json()["backtest_id"]

    # Wait for completion
    time.sleep(3)

    # Get full results
    response = client.get(f"/api/v1/backtest/results/{backtest_id}")
    assert response.status_code == 200
    data = response.json()

    # Check all required fields
    assert "strategy_name" in data
    assert "symbol" in data
    assert "metrics" in data
    assert "trades" in data
    assert "equity_curve" in data
    assert "period" in data
    assert "config" in data


def test_get_equity_curve():
    """Test getting equity curve data."""
    # Run backtest
    run_response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })
    backtest_id = run_response.json()["backtest_id"]

    # Wait for completion
    time.sleep(3)

    # Get equity curve
    response = client.get(f"/api/v1/backtest/{backtest_id}/equity-curve")
    assert response.status_code == 200
    data = response.json()

    assert "equity_curve" in data
    assert len(data["equity_curve"]) > 0
    assert "total_points" in data
    assert "initial_value" in data
    assert "final_value" in data
    assert "peak_value" in data
    assert "lowest_value" in data

    # Check equity point structure
    first_point = data["equity_curve"][0]
    assert "t" in first_point  # timestamp
    assert "v" in first_point  # value


def test_equity_curve_unix_format():
    """Test equity curve with unix timestamp format."""
    # Run backtest
    run_response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00"
    })
    backtest_id = run_response.json()["backtest_id"]

    # Wait for completion
    time.sleep(3)

    # Get equity curve with unix format
    response = client.get(f"/api/v1/backtest/{backtest_id}/equity-curve?format=unix")
    assert response.status_code == 200
    data = response.json()

    # Unix timestamps should be numeric strings
    first_point = data["equity_curve"][0]
    assert first_point["t"].isdigit()  # Unix timestamp is numeric


def test_compare_strategies():
    """Test comparing multiple backtests."""
    # Run 2 backtests
    ids = []
    for strategy in ["RSI", "MACD"]:
        run_response = client.post("/api/v1/backtest/run", json={
            "strategy": strategy,
            "symbol": "AAPL",
            "start_date": "2026-06-20T00:00:00",
            "end_date": "2026-07-20T00:00:00"
        })
        ids.append(run_response.json()["backtest_id"])

    # Wait for completion
    time.sleep(5)

    # Compare
    response = client.post("/api/v1/backtest/compare", json={
        "backtest_ids": ids
    })

    assert response.status_code == 200
    data = response.json()

    assert "comparison" in data
    assert len(data["comparison"]) == 2
    assert "winner" in data
    assert "summary" in data

    # Check winner fields
    winner = data["winner"]
    assert "by_return" in winner
    assert "by_sharpe" in winner
    assert "by_win_rate" in winner
    assert "by_drawdown" in winner


def test_compare_not_found():
    """Test comparison with invalid backtest IDs."""
    response = client.post("/api/v1/backtest/compare", json={
        "backtest_ids": ["invalid-id-1", "invalid-id-2"]
    })

    assert response.status_code == 404


# =============================================================================
# Integration Tests
# =============================================================================

def test_full_workflow():
    """Test complete workflow: submit → status → results → compare."""
    # Step 1: Submit backtest
    run_response = client.post("/api/v1/backtest/run", json={
        "strategy": "RSI",
        "symbol": "AAPL",
        "start_date": "2026-06-20T00:00:00",
        "end_date": "2026-07-20T00:00:00",
        "initial_capital": 10000.0,
        "transaction_cost": 0.001
    })
    assert run_response.status_code == 202
    backtest_id = run_response.json()["backtest_id"]

    # Step 2: Poll status
    completed = False
    for _ in range(30):
        status_response = client.get(f"/api/v1/backtest/status/{backtest_id}")
        if status_response.json()["status"] == "completed":
            completed = True
            break
        time.sleep(1)

    assert completed, "Backtest did not complete in time"

    # Step 3: Get summary
    summary_response = client.get(f"/api/v1/backtest/{backtest_id}/summary")
    assert summary_response.status_code == 200

    # Step 4: Get full results
    results_response = client.get(f"/api/v1/backtest/results/{backtest_id}")
    assert results_response.status_code == 200

    # Step 5: Get equity curve
    curve_response = client.get(f"/api/v1/backtest/{backtest_id}/equity-curve")
    assert curve_response.status_code == 200

    print("\n✅ Full workflow test passed!")
    print(f"   Backtest ID: {backtest_id}")
    print(f"   Return: {summary_response.json()['total_return_pct']:.2f}%")
    print(f"   Trades: {summary_response.json()['num_trades']}")


if __name__ == "__main__":
    # Run tests
    pytest.main([__file__, "-v", "-s"])
