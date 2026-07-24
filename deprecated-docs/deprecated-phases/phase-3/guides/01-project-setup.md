# Task 1: Project Setup and Dependencies

**Goal:** Set up Python FastAPI project structure with all required dependencies.

**Estimated Time:** 1 hour

---

## Prerequisites

Before starting, ensure you have:
- Python 3.11 or higher installed
- pip (Python package manager)
- Your terminal at the project root: `/Users/mhiteshkumar/QuantStream`

**Check Python version:**
```bash
python3 --version
```

Expected output: `Python 3.11.x` or higher

---

## Step 1: Create Project Directory

Navigate to the project root and create the `backtester/` directory:

```bash
cd /Users/mhiteshkumar/QuantStream
mkdir backtester
cd backtester
```

**Verify:**
```bash
pwd
```
Output: `/Users/mhiteshkumar/QuantStream/backtester`

---

## Step 2: Create Python Virtual Environment

A virtual environment isolates project dependencies from system Python packages.

**Create virtual environment:**
```bash
python3 -m venv venv
```

This creates a `venv/` directory containing:
- Python interpreter
- pip (package installer)
- Isolated package installation directory

**Activate virtual environment:**
```bash
source venv/bin/activate
```

**Verify activation:**
Your terminal prompt should change to show `(venv)`:
```
(venv) ➜ backtester
```

**Check Python location (should be inside venv):**
```bash
which python
```
Output: `/Users/mhiteshkumar/QuantStream/backtester/venv/bin/python`

**To deactivate later (don't do this now):**
```bash
deactivate
```

---

## Step 3: Create requirements.txt

Create the dependencies file that lists all Python packages we need.

**Create file:**
```bash
touch requirements.txt
```

**Add dependencies (copy this exact content):**
```txt
# Web Framework
fastapi==0.115.0
uvicorn[standard]==0.30.0

# Data Processing
pandas==2.2.2
numpy==1.26.4

# Database
psycopg2-binary==2.9.9

# Data Validation
pydantic==2.8.0
pydantic-settings==2.3.0

# Technical Indicators
pandas-ta==0.3.14b0

# Environment Variables
python-dotenv==1.0.1

# HTTP Client (for testing)
httpx==0.27.0

# Testing
pytest==8.2.0
pytest-asyncio==0.23.6
```

**Why these packages?**

| Package | Purpose | Used In |
|---------|---------|---------|
| `fastapi` | Modern web framework for building APIs | All API endpoints |
| `uvicorn` | ASGI server to run FastAPI | Running the server |
| `pandas` | Data manipulation and analysis | Data fetching, resampling |
| `numpy` | Numerical computations | Metrics calculations |
| `psycopg2-binary` | PostgreSQL driver (QuestDB uses PostgreSQL wire protocol) | Fetching data from QuestDB |
| `pydantic` | Data validation and settings | Request/response models |
| `pandas-ta` | Technical analysis indicators | RSI, MACD, Bollinger Bands |
| `python-dotenv` | Load environment variables from .env file | Configuration |
| `httpx` | HTTP client for testing | API testing |
| `pytest` | Testing framework | Unit tests |

---

## Step 4: Install Dependencies

With virtual environment activated, install all packages:

```bash
pip install -r requirements.txt
```

**This will:**
1. Download all packages from PyPI (Python Package Index)
2. Install them in the virtual environment (not system-wide)
3. Install all sub-dependencies automatically

**Expected output:**
```
Collecting fastapi==0.115.0
  Downloading fastapi-0.115.0-py3-none-any.whl
...
Successfully installed fastapi-0.115.0 uvicorn-0.30.0 pandas-2.2.2 ...
```

**Verify installation:**
```bash
pip list
```

Should show all installed packages with versions.

**Verify specific packages:**
```bash
python -c "import fastapi; print(fastapi.__version__)"
python -c "import pandas; print(pandas.__version__)"
python -c "import psycopg2; print(psycopg2.__version__)"
```

Expected output:
```
0.115.0
2.2.2
2.9.9 (dt dec pq3 ext lo64)
```

---

## Step 5: Create Project Structure

Create all directories and `__init__.py` files.

**Create directories:**
```bash
mkdir -p app/models
mkdir -p app/core
mkdir -p app/strategies
mkdir -p app/api
mkdir -p app/utils
mkdir -p tests
```

**The `-p` flag:**
- Creates parent directories if they don't exist
- Doesn't error if directory already exists

**Create __init__.py files:**

These files tell Python that directories are packages (can be imported).

```bash
touch app/__init__.py
touch app/models/__init__.py
touch app/core/__init__.py
touch app/strategies/__init__.py
touch app/api/__init__.py
touch app/utils/__init__.py
touch tests/__init__.py
```

**Verify structure:**
```bash
tree -L 2 app
```

Expected output:
```
app
├── __init__.py
├── api
│   └── __init__.py
├── core
│   └── __init__.py
├── models
│   └── __init__.py
├── strategies
│   └── __init__.py
└── utils
    └── __init__.py
```

If `tree` command not found, use:
```bash
find app -type f -name "*.py" | sort
```

---

## Step 6: Create Configuration File

Create `app/config.py` to manage environment variables and configuration.

**Create file:**
```bash
touch app/config.py
```

**Add configuration code:**
```python
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
```

**What this does:**
- `BaseSettings`: Pydantic base class that loads from environment variables
- `lru_cache`: Caches the settings instance (singleton pattern)
- Default values: Used if environment variable not set
- `env_file = ".env"`: Loads from `.env` file if it exists

---

## Step 7: Create .env File

Create `.env` file to store environment-specific configuration.

**Create file:**
```bash
touch .env
```

**Add configuration:**
```bash
# Application Settings
DEBUG=true

# Server Settings
HOST=0.0.0.0
PORT=8085

# QuestDB Settings
QUESTDB_HOST=localhost
QUESTDB_PORT=8812
QUESTDB_USER=admin
QUESTDB_PASSWORD=quest
QUESTDB_DATABASE=qdb

# Backtesting Settings
DEFAULT_INITIAL_CAPITAL=10000.0
DEFAULT_TRANSACTION_COST=0.001
MAX_BACKTEST_DAYS=365
```

**Important:** Add `.env` to `.gitignore` to avoid committing secrets:
```bash
echo ".env" >> .gitignore
echo "venv/" >> .gitignore
echo "__pycache__/" >> .gitignore
echo "*.pyc" >> .gitignore
```

---

## Step 8: Create Main FastAPI Application

Create `app/main.py` - the entry point for the FastAPI application.

**Create file:**
```bash
touch app/main.py
```

**Add application code:**
```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.config import get_settings

# Get configuration
settings = get_settings()

# Create FastAPI application
app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="Backtesting engine for QuantStream trading strategies",
    docs_url="/docs",  # Swagger UI at /docs
    redoc_url="/redoc"  # ReDoc UI at /redoc
)

# Add CORS middleware to allow frontend requests
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:3000"],  # Frontend URLs
    allow_credentials=True,
    allow_methods=["*"],  # Allow all HTTP methods (GET, POST, etc.)
    allow_headers=["*"],  # Allow all headers
)


@app.get("/")
async def root():
    """Root endpoint - health check."""
    return {
        "message": "QuantStream Backtesting Engine",
        "version": settings.app_version,
        "status": "operational"
    }


@app.get("/health")
async def health_check():
    """Health check endpoint for monitoring."""
    return {"status": "healthy"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug  # Auto-reload on code changes in debug mode
    )
```

**What this does:**
- Creates FastAPI app with title, version, description
- Adds CORS middleware to allow frontend (React at localhost:5173) to call API
- Defines two endpoints:
  - `GET /` - Root endpoint
  - `GET /health` - Health check
- `if __name__ == "__main__"` - Runs uvicorn server when script executed directly

---

## Step 9: Test FastAPI Application

**Run the application:**
```bash
python app/main.py
```

**Expected output:**
```
INFO:     Will watch for changes in these directories: ['/Users/mhiteshkumar/QuantStream/backtester']
INFO:     Uvicorn running on http://0.0.0.0:8085 (Press CTRL+C to quit)
INFO:     Started reloader process [12345] using WatchFiles
INFO:     Started server process [12346]
INFO:     Waiting for application startup.
INFO:     Application startup complete.
```

**Test endpoints:**

**Option 1: Using curl (in another terminal):**
```bash
# Test root endpoint
curl http://localhost:8085/

# Expected output:
# {"message":"QuantStream Backtesting Engine","version":"1.0.0","status":"operational"}

# Test health endpoint
curl http://localhost:8085/health

# Expected output:
# {"status":"healthy"}
```

**Option 2: Using browser:**
- Open: http://localhost:8085/
- Open: http://localhost:8085/health
- Open: http://localhost:8085/docs (Swagger UI - interactive API documentation)

**Stop the server:**
Press `CTRL+C` in the terminal running the server.

---

## Step 10: Test QuestDB Connection

Create a simple test to verify QuestDB connectivity.

**Create test file:**
```bash
touch test_questdb_connection.py
```

**Add test code:**
```python
import psycopg2
from app.config import get_settings

settings = get_settings()

try:
    # Connect to QuestDB
    conn = psycopg2.connect(
        host=settings.questdb_host,
        port=settings.questdb_port,
        user=settings.questdb_user,
        password=settings.questdb_password,
        database=settings.questdb_database
    )
    
    # Create cursor
    cursor = conn.cursor()
    
    # Test query
    cursor.execute("SELECT 1 as test")
    result = cursor.fetchone()
    
    print(f"✅ QuestDB connection successful!")
    print(f"Test query result: {result}")
    
    # Check if ticks table exists
    cursor.execute("SELECT count(*) FROM ticks")
    tick_count = cursor.fetchone()[0]
    print(f"✅ Ticks table exists with {tick_count} rows")
    
    # Close connections
    cursor.close()
    conn.close()
    
except Exception as e:
    print(f"❌ QuestDB connection failed: {e}")
    print("\nTroubleshooting:")
    print("1. Ensure QuestDB is running: docker ps | grep questdb")
    print("2. Check QuestDB logs: docker logs questdb")
    print("3. Verify port 8812 is accessible: nc -zv localhost 8812")
```

**Run the test:**
```bash
python test_questdb_connection.py
```

**Expected output (if QuestDB is running):**
```
✅ QuestDB connection successful!
Test query result: (1,)
✅ Ticks table exists with 4600 rows
```

**If connection fails:**
1. Start QuestDB container: `docker start questdb`
2. Check if running: `docker ps | grep questdb`
3. Check logs: `docker logs questdb`

---

## Step 11: Create README.md

Document the setup and usage for the backtester service.

**Create file:**
```bash
touch README.md
```

**Add documentation:**
```markdown
# QuantStream Backtesting Engine

Python-based backtesting engine for evaluating trading strategy performance on historical data.

## Technology Stack

- **Python 3.11+**
- **FastAPI** - Modern web framework
- **Pandas** - Data manipulation
- **NumPy** - Numerical computations
- **Psycopg2** - QuestDB connection
- **pandas-ta** - Technical indicators

## Setup

### 1. Create Virtual Environment

\`\`\`bash
python3 -m venv venv
source venv/bin/activate
\`\`\`

### 2. Install Dependencies

\`\`\`bash
pip install -r requirements.txt
\`\`\`

### 3. Configure Environment

Copy `.env.example` to `.env` and update values:

\`\`\`bash
cp .env.example .env
\`\`\`

### 4. Run Application

\`\`\`bash
python app/main.py
\`\`\`

Server starts at: http://localhost:8085

## API Documentation

Interactive API documentation available at:
- Swagger UI: http://localhost:8085/docs
- ReDoc: http://localhost:8085/redoc

## Project Structure

\`\`\`
backtester/
├── app/
│   ├── main.py                  # FastAPI application entry
│   ├── config.py                # Configuration management
│   ├── models/                  # Pydantic models
│   ├── core/                    # Core backtesting logic
│   ├── strategies/              # Trading strategies
│   ├── api/                     # API endpoints
│   └── utils/                   # Utilities
├── tests/                       # Unit tests
├── requirements.txt             # Python dependencies
├── .env                         # Environment variables (not committed)
└── README.md                    # This file
\`\`\`

## Development

### Run with Auto-Reload

\`\`\`bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8085
\`\`\`

### Run Tests

\`\`\`bash
pytest tests/
\`\`\`

## Next Steps

See `docs/phase-3/tasks/TASK-LIST.md` for implementation tasks.
```

---

## Step 12: Verify Complete Setup

**Check directory structure:**
```bash
tree -L 3 -I 'venv|__pycache__'
```

**Expected structure:**
```
.
├── app
│   ├── __init__.py
│   ├── api
│   │   └── __init__.py
│   ├── config.py
│   ├── core
│   │   └── __init__.py
│   ├── main.py
│   ├── models
│   │   └── __init__.py
│   ├── strategies
│   │   └── __init__.py
│   └── utils
│       └── __init__.py
├── tests
│   └── __init__.py
├── requirements.txt
├── .env
├── .gitignore
├── README.md
└── test_questdb_connection.py
```

**Verify imports work:**
```bash
python -c "from app.config import get_settings; print(get_settings())"
```

Expected output (settings object with default values).

**Run FastAPI server:**
```bash
python app/main.py
```

**In another terminal, test endpoints:**
```bash
curl http://localhost:8085/
curl http://localhost:8085/health
```

---

## Success Criteria Checklist

Mark each as you complete:

- [ ] Virtual environment created and activated
- [ ] All dependencies installed without errors (`pip list` shows all packages)
- [ ] Project structure matches design (all directories created)
- [ ] `app/config.py` created with settings
- [ ] `.env` file created with QuestDB connection details
- [ ] `app/main.py` created with FastAPI app
- [ ] Can run server: `python app/main.py`
- [ ] Root endpoint works: `curl http://localhost:8085/`
- [ ] Health endpoint works: `curl http://localhost:8085/health`
- [ ] Swagger UI accessible: http://localhost:8085/docs
- [ ] QuestDB connection test passes
- [ ] README.md created

---

## Common Issues and Solutions

### Issue 1: Python version too old

**Error:** `Python 3.8 or lower`

**Solution:**
```bash
# macOS (using Homebrew)
brew install python@3.11

# Update PATH
export PATH="/usr/local/opt/python@3.11/bin:$PATH"

# Verify
python3 --version
```

### Issue 2: pip install fails

**Error:** `error: externally-managed-environment`

**Solution:** Always use virtual environment (not system Python):
```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Issue 3: psycopg2 build fails

**Error:** `Error: pg_config executable not found`

**Solution:** Use `psycopg2-binary` (already in requirements.txt):
```bash
pip install psycopg2-binary
```

### Issue 4: Port 8085 already in use

**Error:** `[Errno 48] Address already in use`

**Solution:**
```bash
# Find process using port 8085
lsof -i :8085

# Kill the process
kill -9 <PID>

# Or use different port in .env
PORT=8086
```

### Issue 5: QuestDB connection fails

**Error:** `psycopg2.OperationalError: could not connect`

**Solution:**
```bash
# Check if QuestDB is running
docker ps | grep questdb

# Start QuestDB if not running
docker start questdb

# Verify port 8812 is accessible
nc -zv localhost 8812
```

### Issue 6: Import errors

**Error:** `ModuleNotFoundError: No module named 'app'`

**Solution:** Run from project root:
```bash
cd /Users/mhiteshkumar/QuantStream/backtester
python app/main.py
```

---

## Next Steps

Once Task 1 is complete:

**Task 2: QuestDB Data Fetcher**
- Implement `app/core/data_fetcher.py`
- Fetch historical ticks from QuestDB
- Convert to Pandas DataFrame

See: `docs/phase-3/guides/02-data-fetcher-implementation.md`

---

## Files Created

This task creates the following files:

```
backtester/
├── app/
│   ├── __init__.py
│   ├── main.py
│   ├── config.py
│   ├── models/__init__.py
│   ├── core/__init__.py
│   ├── strategies/__init__.py
│   ├── api/__init__.py
│   └── utils/__init__.py
├── tests/__init__.py
├── requirements.txt
├── .env
├── .gitignore
├── README.md
└── test_questdb_connection.py
```

**Total:** 15 files
**Time:** ~1 hour

---

## Verification Commands

Run these to verify setup is complete:

```bash
# 1. Check Python version
python3 --version

# 2. Check virtual environment is activated
which python  # Should show path inside venv/

# 3. Check dependencies installed
pip list | grep -E "fastapi|pandas|psycopg2"

# 4. Check project structure
tree -L 2 -I 'venv|__pycache__' app

# 5. Test imports
python -c "from app.config import get_settings; print('✅ Config import works')"
python -c "from fastapi import FastAPI; print('✅ FastAPI import works')"
python -c "import pandas; print('✅ Pandas import works')"
python -c "import psycopg2; print('✅ Psycopg2 import works')"

# 6. Run server (Ctrl+C to stop)
python app/main.py

# 7. Test endpoints (in another terminal)
curl http://localhost:8085/
curl http://localhost:8085/health

# 8. Test QuestDB connection
python test_questdb_connection.py
```

If all commands succeed, Task 1 is complete! ✅
