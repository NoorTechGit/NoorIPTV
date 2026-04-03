"""
Celery configuration
"""

from celery import Celery
import os

# Redis broker URL from environment
REDIS_URL = os.getenv("REDIS_URL", "redis://salliptv-redis:6379/0")

celery_app = Celery(
    "salliptv",
    broker=REDIS_URL,
    backend=REDIS_URL,
    include=[
        "workers.parse_worker",
        "workers.ml_worker",
        "workers.epg_worker",
    ]
)

# Celery configuration
celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=7200,  # 2 hours max (TMDB enrichment is long)
    worker_prefetch_multiplier=1,  # Process one task at a time
    # Queue configuration
    task_routes={
        "workers.parse_worker.process_playlist": {"queue": "parsing"},
        "workers.parse_worker.enrich_tmdb_background": {"queue": "ml"},
        "workers.epg_worker.refresh_epg_task": {"queue": "ml"},
        "workers.ml_worker.*": {"queue": "ml"},
    },
    beat_schedule={
        "refresh-epg-daily": {
            "task": "workers.epg_worker.refresh_epg_task",
            "schedule": 6 * 3600,  # Every 6 hours
        },
    },
    # Result expiration
    result_expires=3600,  # 1 hour
)

if __name__ == "__main__":
    celery_app.start()
