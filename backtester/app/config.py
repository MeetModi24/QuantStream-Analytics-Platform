from pydantic_settings import BaseSettings
from functools import lru_cache


class Settings(BaseSettings):
    """Application configuration settings loaded from environment variables."""
    
    # Application Settings
    app_name: str = "QuantStream Backtester"
    app_version: str = "1.0.0"
    debug: bool = False
    
    # Server Settings
    host: str = "0.0.0.0"
    port: int = 8085
    
    # QuestDB Settings
    questdb_host: str = "localhost"
    questdb_port: int = 8812
    questdb_user: str = "admin"
    questdb_password: str = "quest"
    questdb_database: str = "qdb"
    
    # Backtesting Settings
    default_initial_capital: float = 10000.0
    default_transaction_cost: float = 0.001  # 0.1% per trade
    max_backtest_days: int = 365  # Maximum backtest period
    
    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


@lru_cache()
def get_settings() -> Settings:
    """
    Create cached settings instance.
    
    The @lru_cache decorator ensures only one Settings instance is created
    and reused throughout the application lifecycle.
    """
    return Settings()