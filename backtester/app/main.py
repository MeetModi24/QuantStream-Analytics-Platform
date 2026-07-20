from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
import asyncio
from app.config import get_settings
from app.api.backtest import router as backtest_router
from app.core.task_manager import start_cleanup_task

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

# Register API routers
app.include_router(backtest_router)


@app.on_event("startup")
async def startup_event():
    """Run on application startup."""
    # Start background task to clean up expired results
    asyncio.create_task(start_cleanup_task(interval_minutes=30, ttl_hours=1))
    print("✅ Backtesting Engine started")
    print(f"📊 API Documentation: http://{settings.host}:{settings.port}/docs")


@app.get("/")
async def root():
    """Root endpoint - health check."""
    return {
        "message": "QuantStream Backtesting Engine",
        "version": settings.app_version,
        "status": "operational",
        "docs_url": "/docs"
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