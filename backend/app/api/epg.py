"""
EPG API endpoints — serves cached EPG data from Redis
"""

import json
import os
from fastapi import APIRouter, HTTPException
from typing import Optional
import redis

router = APIRouter()

redis_client = redis.Redis.from_url(
    os.getenv("REDIS_URL", "redis://salliptv-redis:6379/0"),
    decode_responses=True
)


@router.get("/by-name/{channel_name}")
async def get_epg_by_name(channel_name: str):
    """Get EPG programs by channel name. Tries exact match, then partial."""
    from workers.epg_worker import normalize_channel_name
    normalized = normalize_channel_name(channel_name)

    # Try exact match first
    data = redis_client.get(f"epg:name:{normalized}")
    if data:
        return {"programs": json.loads(data), "channel": channel_name}

    # Try partial match: search for keys containing our normalized name
    try:
        pattern = f"epg:name:*{normalized}*"
        keys = redis_client.keys(pattern)
        if keys:
            # Pick the shortest key (closest match)
            best_key = min(keys, key=len)
            data = redis_client.get(best_key)
            if data:
                return {"programs": json.loads(data), "channel": channel_name, "matched": best_key.replace("epg:name:", "")}
    except Exception:
        pass

    return {"programs": [], "channel": channel_name}


@router.get("/by-id/{tvg_id}")
async def get_epg_by_id(tvg_id: str):
    """Get EPG programs by tvg-id."""
    data = redis_client.get(f"epg:id:{tvg_id.lower()}")
    if not data:
        return {"programs": [], "tvg_id": tvg_id}
    return {"programs": json.loads(data), "tvg_id": tvg_id}


@router.get("/status")
async def epg_status():
    """Check EPG data status."""
    last_update = redis_client.get("epg:last_update")
    # Count EPG keys
    name_keys = len(redis_client.keys("epg:name:*"))
    id_keys = len(redis_client.keys("epg:id:*"))
    return {
        "last_update": int(last_update) if last_update else None,
        "channels_by_name": name_keys,
        "channels_by_id": id_keys,
    }


@router.post("/refresh")
async def trigger_refresh():
    """Trigger EPG refresh (admin)."""
    from workers.epg_worker import refresh_epg_task
    task = refresh_epg_task.delay()
    return {"task_id": task.id, "status": "queued"}
