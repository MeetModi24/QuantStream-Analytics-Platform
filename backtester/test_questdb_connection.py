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
