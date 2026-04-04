"""
ML Worker — AI-powered enrichment using GLM API.
Handles smart categorization, channel tagging, and content recommendations.

Used by Celery beat for periodic enrichment tasks.
Future: hero banner selection, trending detection, smart grouping.
"""

import gzip
import json
import logging
import os
from pathlib import Path
from typing import Dict, List, Optional

import httpx
import redis
from celery import shared_task

logger = logging.getLogger(__name__)

REDIS_URL = os.getenv("REDIS_URL", "redis://salliptv-redis:6379/0")
GLM_API_URL = os.getenv("GLM_API_URL", "https://api.z.ai/api/anthropic")
GLM_API_KEY = os.getenv("GLM_API_KEY", "")
GLM_MODEL = os.getenv("GLM_MODEL", "glm-4.5-air")

UPLOAD_DIR = Path(os.getenv("UPLOAD_DIR", "/tmp/salliptv_uploads"))

_redis = None


def get_redis():
    global _redis
    if _redis is None:
        try:
            _redis = redis.Redis.from_url(REDIS_URL, decode_responses=True)
            _redis.ping()
        except Exception:
            _redis = None
    return _redis


def call_glm(system_prompt: str, user_prompt: str, max_tokens: int = 2000) -> Optional[str]:
    """
    Call GLM via Anthropic-compatible API.
    Returns the text response or None on failure.
    """
    if not GLM_API_KEY:
        logger.warning("GLM_API_KEY not set, skipping AI enrichment")
        return None

    try:
        with httpx.Client(timeout=30) as client:
            resp = client.post(
                f"{GLM_API_URL}/v1/messages",
                headers={
                    "x-api-key": GLM_API_KEY,
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
                json={
                    "model": GLM_MODEL,
                    "max_tokens": max_tokens,
                    "system": system_prompt,
                    "messages": [{"role": "user", "content": user_prompt}],
                },
            )
            if resp.status_code != 200:
                logger.warning(f"GLM API error: {resp.status_code} {resp.text[:200]}")
                return None

            data = resp.json()
            return data.get("content", [{}])[0].get("text", "")
    except Exception as e:
        logger.error(f"GLM call failed: {e}")
        return None


def smart_categorize_channels(channel_names: List[str]) -> Dict[str, dict]:
    """
    Use GLM to categorize a batch of channel names.
    Returns {name: {category, country, tags}} for each channel.
    """
    prompt = (
        "You are an IPTV channel classifier. For each channel name, return a JSON object "
        "with: category (one of: sport, news, kids, music, movie, entertainment, documentary, "
        "lifestyle, religion, adult, education, shopping, weather, other), "
        "country (ISO code like FR, UK, US, DE, or null), "
        "tags (array of relevant tags like football, basketball, anime, etc).\n\n"
        "Return ONLY a JSON object mapping each channel name to its classification. "
        "No explanation, no markdown."
    )
    names_str = "\n".join(f"- {n}" for n in channel_names)
    response = call_glm(prompt, f"Classify these channels:\n{names_str}", max_tokens=3000)
    if not response:
        return {}

    # Strip markdown fences if present
    text = response.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[-1]
    if text.endswith("```"):
        text = text.rsplit("```", 1)[0]
    text = text.strip()

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        logger.warning(f"GLM returned invalid JSON: {text[:200]}")
        return {}


# ── Celery Tasks ──


@shared_task(bind=True, max_retries=1)
def categorize_channels_batch(self):
    """
    Periodic task: use GLM to classify uncategorized live channels.
    Processes 100 per run, self-chains until all done.
    """
    r = get_redis()
    if not r:
        return {"status": "no_redis"}

    result_files = sorted(UPLOAD_DIR.glob("*_result.json.gz"), key=lambda f: f.stat().st_mtime, reverse=True)
    if not result_files:
        return {"status": "no_data"}

    # Collect uncategorized live channels
    with gzip.open(str(result_files[0]), 'rt', encoding='utf-8', errors='ignore') as f:
        data = json.load(f)

    uncategorized = []
    for ch in data:
        if ch.get("type") != "LIVE":
            continue
        name = ch.get("clean_name") or ch.get("name", "")
        if not name:
            continue
        key = f"smart_cat:{name.lower().strip()}"
        if not r.exists(key):
            uncategorized.append(name)

    if not uncategorized:
        logger.info("All live channels are categorized")
        return {"status": "complete"}

    # Process batch of 100
    batch = uncategorized[:100]
    result = smart_categorize_channels(batch)

    # Store results in Redis
    stored = 0
    for name, info in result.items():
        key = f"smart_cat:{name.lower().strip()}"
        r.setex(key, 30 * 86400, json.dumps(info, ensure_ascii=False))
        stored += 1

    remaining = len(uncategorized) - len(batch)
    logger.info(f"Smart categorization: {stored}/{len(batch)} categorized, {remaining} remaining")

    # Self-chain if more to process
    if remaining > 0:
        categorize_channels_batch.apply_async(countdown=10)

    return {"status": "enriched", "stored": stored, "remaining": remaining}


@shared_task(bind=True, max_retries=0)
def generate_home_sections(self):
    """
    Generate smart home page sections from categorized channels.
    Groups channels into: Sport Live, News 24/7, Kids, Trending, etc.
    Stores the result in Redis for the Android app to fetch.
    """
    r = get_redis()
    if not r:
        return {"status": "no_redis"}

    result_files = sorted(UPLOAD_DIR.glob("*_result.json.gz"), key=lambda f: f.stat().st_mtime, reverse=True)
    if not result_files:
        return {"status": "no_data"}

    with gzip.open(str(result_files[0]), 'rt', encoding='utf-8', errors='ignore') as f:
        data = json.load(f)

    # Build sections from smart categories
    sections = {
        "sport_live": [],
        "news_247": [],
        "kids": [],
        "movies": [],
        "series": [],
        "music": [],
        "entertainment": [],
        "favorites": [],
        "recent": [],
    }

    for ch in data:
        ch_type = ch.get("type", "LIVE")
        name = ch.get("clean_name") or ch.get("name", "")

        if ch_type == "VOD":
            sections["movies"].append(ch)
        elif ch_type == "SERIES":
            sections["series"].append(ch)
        elif ch_type == "LIVE":
            # Check smart category
            key = f"smart_cat:{name.lower().strip()}"
            cat_data = r.get(key)
            if cat_data:
                try:
                    info = json.loads(cat_data)
                    cat = info.get("category", "other")
                    if cat == "sport":
                        sections["sport_live"].append(ch)
                    elif cat == "news":
                        sections["news_247"].append(ch)
                    elif cat == "kids":
                        sections["kids"].append(ch)
                    elif cat == "music":
                        sections["music"].append(ch)
                    elif cat in ("entertainment", "lifestyle"):
                        sections["entertainment"].append(ch)
                    else:
                        sections["entertainment"].append(ch)
                except Exception:
                    sections["entertainment"].append(ch)
            else:
                # Fallback: use category field from playlist
                cat_name = (ch.get("category", "") or "").lower()
                if any(w in cat_name for w in ("sport", "foot", "bein", "canal+", "rmc")):
                    sections["sport_live"].append(ch)
                elif any(w in cat_name for w in ("news", "info", "bfm", "lci")):
                    sections["news_247"].append(ch)
                elif any(w in cat_name for w in ("kids", "enfant", "gulli", "cartoon", "disney")):
                    sections["kids"].append(ch)
                else:
                    sections["entertainment"].append(ch)

    # Store sections in Redis (24h TTL)
    for section_name, channels in sections.items():
        # Keep only necessary fields for the app
        trimmed = []
        for ch in channels[:50]:  # Max 50 per section
            entry = {
                "name": ch.get("name", ""),
                "logo": ch.get("logo") or ch.get("logo_hd"),
                "category": ch.get("category", ""),
                "type": ch.get("type", "LIVE"),
            }
            if ch.get("poster_hd"):
                entry["poster_hd"] = ch["poster_hd"]
            if ch.get("backdrop"):
                entry["backdrop"] = ch["backdrop"]
            if ch.get("rating"):
                entry["rating"] = ch["rating"]
            if ch.get("year"):
                entry["year"] = ch["year"]
            if ch.get("url"):
                entry["url"] = ch["url"]
            trimmed.append(entry)

        r.setex(f"home:{section_name}", 24 * 3600, json.dumps(trimmed, ensure_ascii=False))

    logger.info(f"Generated home sections: " + ", ".join(f"{k}={len(v)}" for k, v in sections.items()))
    return {"status": "ok", "sections": {k: len(v) for k, v in sections.items()}}
