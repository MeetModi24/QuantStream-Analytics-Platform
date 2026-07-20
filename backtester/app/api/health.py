"""
Health Check and Service Status Endpoints

Provides system health monitoring for frontend and ops.
"""

from fastapi import APIRouter, HTTPException
from datetime import datetime
import psycopg2

from app.config import get_settings
from app.models.api_models import HealthResponse

router = APIRouter(prefix="/api/v1", tags=["Health"])

settings = get_settings()


@router.get("/health", response_model=HealthResponse)
async def health_check():
    """
    Get overall system health status.

    Checks connectivity to QuestDB.

    Returns:
        Health status of all services

    Example:
        GET /api/v1/health

        Response:
        {
            "status": "healthy",
            "timestamp": "2026-07-20T11:15:30Z",
            "services": {
                "api": "healthy",
                "questdb": "healthy"
            }
        }
    """
    services = {}

    # Check API itself (always healthy if we can respond)
    services["api"] = "healthy"

    # Check QuestDB connectivity
    try:
        conn = psycopg2.connect(
            host=settings.questdb_host,
            port=settings.questdb_port,
            user=settings.questdb_user,
            password=settings.questdb_password,
            database=settings.questdb_database,
            connect_timeout=3
        )
        conn.close()
        services["questdb"] = "healthy"
    except Exception:
        services["questdb"] = "unhealthy"

    # Overall status
    overall_status = "healthy" if all(
        status == "healthy" for status in services.values()
    ) else "degraded"

    return HealthResponse(
        status=overall_status,
        timestamp=datetime.utcnow().isoformat() + "Z",
        services=services
    )
