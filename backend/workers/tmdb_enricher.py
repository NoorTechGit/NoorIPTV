"""
TMDB Enricher — Fetches metadata for VOD/Series from TMDB API.
Uses PostgreSQL cache: same title never queried twice.

Normalizes IPTV names like "FR - Armageddon (1998) [HD]" → "Armageddon"
Then searches TMDB, caches the result, returns poster/backdrop/info.
"""

import os
import re
import logging
import time
from typing import Optional, Dict, Any, List
import httpx
import psycopg2
import redis
from psycopg2.extras import RealDictCursor

logger = logging.getLogger(__name__)

TMDB_API_KEY = os.getenv("TMDB_API_KEY", "")
TMDB_BASE = "https://api.themoviedb.org/3"
TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p"
DATABASE_URL = os.getenv("DATABASE_URL", "")

# Rate limit: TMDB allows ~40 req/s
TMDB_RATE_LIMIT_DELAY = 0.03  # 30ms between requests
REDIS_URL = os.getenv("REDIS_URL", "redis://salliptv-redis:6379/0")
REDIS_CACHE_TTL = 30 * 24 * 3600  # 30 days

# Redis L1 cache (fast, in-memory)
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


def redis_get_tmdb(title_key: str) -> Optional[Dict]:
    """L1 cache lookup in Redis"""
    r = get_redis()
    if not r:
        return None
    try:
        data = r.get(f"tmdb:{title_key}")
        if data:
            import json as _json
            return _json.loads(data)
    except Exception:
        pass
    return None


def redis_set_tmdb(title_key: str, metadata: Dict):
    """L1 cache write to Redis"""
    r = get_redis()
    if not r:
        return
    try:
        import json as _json
        r.setex(f"tmdb:{title_key}", REDIS_CACHE_TTL, _json.dumps(metadata, ensure_ascii=False))
    except Exception:
        pass


def get_db():
    """Get PostgreSQL connection"""
    return psycopg2.connect(DATABASE_URL, cursor_factory=RealDictCursor)


def normalize_title(raw_name: str) -> str:
    """
    Normalize IPTV channel/movie name for TMDB search.
    Returns clean title only.
    """
    info = extract_badges(raw_name)
    return info["clean_title"]


def extract_badges(raw_name: str) -> dict:
    """
    Extract all metadata badges from an IPTV name and return clean title.

    "FR - Armageddon (1998) [HD]" → {
        "clean_title": "Armageddon",
        "lang": "FR",
        "year": 1998,
        "quality": "HD",
        "season": None,
        "episode": None,
    }
    """
    name = raw_name.strip()
    lang = None
    quality = None
    year = None
    season = None
    episode = None

    # Extract language prefix: "FR -", "|EN|", "AR|", "VF-", "MULTI -", etc.
    lang_match = re.match(r'^[\|]?([A-Z]{2,5})[\|\s]*[-–—:]?\s*', name)
    if lang_match:
        lang = lang_match.group(1).upper()
        name = name[lang_match.end():]

    # Extract quality: [HD], (4K), FHD, UHD, etc.
    q_match = re.search(r'[\[\(]\s*(HD|FHD|UHD|4K|SD|720p|1080p|2160p)\s*[\]\)]', name, re.IGNORECASE)
    if q_match:
        quality = q_match.group(1).upper()
        name = name[:q_match.start()] + name[q_match.end():]
    else:
        q_match2 = re.search(r'\s+(4K|FHD|UHD|HD|SD)\s*$', name, re.IGNORECASE)
        if q_match2:
            quality = q_match2.group(1).upper()
            name = name[:q_match2.start()]

    # Extract year
    year_match = re.search(r'\((\d{4})\)', name)
    if year_match:
        y = int(year_match.group(1))
        if 1900 <= y <= 2030:
            year = y
        name = name[:year_match.start()] + name[year_match.end():]

    # Extract season/episode: S01E01, S01 E01, S01, Season 1 Episode 2, etc.
    se_match = re.search(r'\s*S(\d{1,2})\s*E(\d{1,3})\s*$', name, re.IGNORECASE)
    if not se_match:
        se_match = re.search(r'\s*S(\d{1,2})\s*$', name, re.IGNORECASE)
    if se_match:
        season = int(se_match.group(1))
        try:
            if se_match.group(2):
                episode = int(se_match.group(2))
        except IndexError:
            pass
        name = name[:se_match.start()]

    # Cleanup
    name = re.sub(r'[\s\-–—:]+$', '', name).strip()

    return {
        "clean_title": name,
        "lang": lang,
        "year": year,
        "quality": quality,
        "season": season,
        "episode": episode,
    }


def extract_year(raw_name: str) -> Optional[int]:
    """Extract year from title if present"""
    return extract_badges(raw_name).get("year")


def search_tmdb(title: str, media_type: str = "movie", year: Optional[int] = None) -> Optional[Dict]:
    """
    Search TMDB for a title. Returns the best match or None.
    """
    if not TMDB_API_KEY or TMDB_API_KEY.startswith("your_"):
        return None

    endpoint = f"{TMDB_BASE}/search/{'tv' if media_type == 'series' else 'movie'}"
    params = {
        "api_key": TMDB_API_KEY,
        "query": title,
        "language": "fr-FR",  # Prefer French results
        "include_adult": "false",
    }
    if year and media_type == "movie":
        params["year"] = str(year)

    try:
        with httpx.Client(timeout=10) as client:
            resp = client.get(endpoint, params=params)
            if resp.status_code != 200:
                return None

            data = resp.json()
            results = data.get("results", [])
            if not results:
                # Retry without year
                if year:
                    params.pop("year", None)
                    resp = client.get(endpoint, params=params)
                    results = resp.json().get("results", [])
                if not results:
                    return None

            # Best match = first result (TMDB ranks by relevance)
            best = results[0]
            return best
    except Exception as e:
        logger.warning(f"TMDB search error for '{title}': {e}")
        return None


def get_tmdb_details(tmdb_id: int, media_type: str = "movie") -> Optional[Dict]:
    """Get full details including cast"""
    if not TMDB_API_KEY or TMDB_API_KEY.startswith("your_"):
        return None

    endpoint = f"{TMDB_BASE}/{'tv' if media_type == 'series' else 'movie'}/{tmdb_id}"
    params = {
        "api_key": TMDB_API_KEY,
        "language": "fr-FR",
        "append_to_response": "credits",
    }

    try:
        with httpx.Client(timeout=10) as client:
            resp = client.get(endpoint, params=params)
            if resp.status_code != 200:
                return None
            return resp.json()
    except Exception as e:
        logger.warning(f"TMDB details error for {tmdb_id}: {e}")
        return None


def _row_to_meta(row: Dict) -> Dict:
    """Convert PostgreSQL row to metadata dict"""
    meta = {}
    if row.get("poster_path"):
        meta["poster_hd"] = f"{TMDB_IMAGE_BASE}/w500{row['poster_path']}"
    if row.get("backdrop_path"):
        meta["backdrop"] = f"{TMDB_IMAGE_BASE}/w1280{row['backdrop_path']}"
    if row.get("overview"):
        meta["overview"] = row["overview"]
    if row.get("release_year"):
        meta["year"] = row["release_year"]
    if row.get("vote_average"):
        meta["rating"] = round(float(row["vote_average"]), 1)
    if row.get("genres"):
        meta["genres"] = row["genres"]
    if row.get("cast_names"):
        meta["cast"] = row["cast_names"]
    if row.get("tmdb_id"):
        meta["tmdb_id"] = row["tmdb_id"]
    return meta


def _apply_metadata(ch: Dict, meta: Dict):
    """Apply cached metadata to a channel dict"""
    for key in ("poster_hd", "backdrop", "overview", "year", "rating", "genres", "cast", "tmdb_id"):
        if key in meta:
            ch[key] = meta[key]


def search_channel_logo(channel_name: str) -> Optional[str]:
    """
    Search for HD logo of a TV channel via TMDB TV search.
    Returns poster/logo URL or None.
    """
    if not TMDB_API_KEY or TMDB_API_KEY.startswith("your_"):
        return None

    clean = normalize_title(channel_name)
    if not clean or len(clean) < 2:
        return None

    try:
        with httpx.Client(timeout=10) as client:
            # Search TMDB for TV network/channel
            resp = client.get(f"{TMDB_BASE}/search/tv", params={
                "api_key": TMDB_API_KEY,
                "query": clean,
                "language": "fr-FR",
            })
            if resp.status_code == 200:
                results = resp.json().get("results", [])
                if results:
                    logo = results[0].get("poster_path")
                    if logo:
                        return f"{TMDB_IMAGE_BASE}/w200{logo}"

            # Try searching as a company/network
            resp2 = client.get(f"{TMDB_BASE}/search/company", params={
                "api_key": TMDB_API_KEY,
                "query": clean,
            })
            if resp2.status_code == 200:
                results = resp2.json().get("results", [])
                if results:
                    logo = results[0].get("logo_path")
                    if logo:
                        return f"{TMDB_IMAGE_BASE}/w200{logo}"
    except Exception:
        pass

    return None


def enrich_live_channel_logos(channels: List[Dict]) -> List[Dict]:
    """
    Enrich live channel logos from cache. No API calls — cache only.
    Use continue_logo_enrichment task for API calls.
    """
    r = get_redis()
    hits = 0
    for ch in channels:
        name = normalize_title(ch.get("name", ""))
        if not name or len(name) < 2:
            continue
        key = f"logo:{name.lower()}"
        if r:
            try:
                url = r.get(key)
                if url and url != "none":
                    ch["logo_hd"] = url
                    hits += 1
            except Exception:
                pass
    if hits > 0:
        logger.info(f"Logo cache: {hits}/{len(channels)} HD logos applied")
    return channels


def enrich_channels(channels: List[Dict], media_type: str, update_fn=None) -> List[Dict]:
    """
    Enrich a list of channels with TMDB metadata.
    Uses PostgreSQL cache — same title never queried twice.

    Args:
        channels: list of {"name": ..., "logo": ..., "category": ..., "url": ...}
        media_type: "movie" or "series"
        update_fn: callback for progress updates

    Returns: enriched channels with poster_hd, backdrop, overview, year, rating, genres
    """
    if not channels:
        return channels

    conn = None
    try:
        conn = get_db()
        cur = conn.cursor()
    except Exception as e:
        logger.error(f"DB connection failed: {e}")
        return channels  # Return unenriched if DB is down

    enriched = 0
    cached_hits = 0
    api_calls = 0
    total = len(channels)

    for i, ch in enumerate(channels):
        raw_name = ch.get("name", "")
        badges = extract_badges(raw_name)
        title = badges["clean_title"]

        # Always apply extracted badges (lang, year, quality)
        if badges["lang"]:
            ch["lang"] = badges["lang"]
        if badges["year"]:
            ch["year"] = badges["year"]
        if badges["quality"]:
            ch["quality"] = badges["quality"]
        if badges["season"]:
            ch["season"] = badges["season"]
        if badges["episode"]:
            ch["episode"] = badges["episode"]
        if title:
            ch["clean_name"] = title

        if not title or len(title) < 2:
            continue

        title_lower = title.lower().strip()
        db_type = "series" if media_type == "series" else "movie"

        cache_key = f"{db_type}:{title_lower}"

        # L1: Redis (microseconds)
        redis_hit = redis_get_tmdb(cache_key)
        if redis_hit:
            _apply_metadata(ch, redis_hit)
            cached_hits += 1
            enriched += 1
            continue

        # L2: PostgreSQL (milliseconds)
        cur.execute(
            "SELECT * FROM tmdb_cache WHERE title_normalized = %s AND media_type = %s",
            (title_lower, db_type)
        )
        cached = cur.fetchone()

        if cached:
            cached_hits += 1
            meta = _row_to_meta(cached)
            _apply_metadata(ch, meta)
            # Promote to L1
            redis_set_tmdb(cache_key, meta)
            enriched += 1
            continue

        # Cache miss — query TMDB API
        year = extract_year(raw_name)
        result = search_tmdb(title, db_type, year)
        api_calls += 1

        if result:
            tmdb_id = result.get("id")
            poster_path = result.get("poster_path")
            backdrop_path = result.get("backdrop_path")
            overview = result.get("overview", "")

            if db_type == "series":
                release_date = result.get("first_air_date", "")
            else:
                release_date = result.get("release_date", "")
            release_year = int(release_date[:4]) if release_date and len(release_date) >= 4 else None

            vote_avg = result.get("vote_average", 0)
            genre_ids = result.get("genre_ids", [])

            # Get details for cast and genre names
            genres_str = ""
            cast_str = ""
            details = get_tmdb_details(tmdb_id, db_type) if tmdb_id else None
            api_calls += 1

            if details:
                genres_list = [g["name"] for g in details.get("genres", [])]
                genres_str = ", ".join(genres_list[:5])

                credits = details.get("credits", {})
                cast_list = [c["name"] for c in credits.get("cast", [])[:5]]
                cast_str = ", ".join(cast_list)

            # Save to cache
            try:
                cur.execute("""
                    INSERT INTO tmdb_cache (title_normalized, media_type, tmdb_id, poster_path, backdrop_path,
                        overview, release_year, vote_average, genres, cast_names, original_title)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (title_normalized, media_type) DO UPDATE SET
                        tmdb_id = EXCLUDED.tmdb_id,
                        poster_path = EXCLUDED.poster_path,
                        backdrop_path = EXCLUDED.backdrop_path,
                        overview = EXCLUDED.overview,
                        release_year = EXCLUDED.release_year,
                        vote_average = EXCLUDED.vote_average,
                        genres = EXCLUDED.genres,
                        cast_names = EXCLUDED.cast_names,
                        updated_at = NOW()
                """, (title_lower, db_type, tmdb_id, poster_path, backdrop_path,
                      overview, release_year, vote_avg, genres_str, cast_str, title))
                conn.commit()
            except Exception as e:
                logger.warning(f"Cache write error: {e}")
                conn.rollback()

            # Build metadata and apply
            meta = {}
            if poster_path:
                meta["poster_hd"] = f"{TMDB_IMAGE_BASE}/w500{poster_path}"
            if backdrop_path:
                meta["backdrop"] = f"{TMDB_IMAGE_BASE}/w1280{backdrop_path}"
            if overview:
                meta["overview"] = overview
            if release_year:
                meta["year"] = release_year
            if vote_avg:
                meta["rating"] = round(vote_avg, 1)
            if genres_str:
                meta["genres"] = genres_str
            if cast_str:
                meta["cast"] = cast_str
            if tmdb_id:
                meta["tmdb_id"] = tmdb_id

            _apply_metadata(ch, meta)
            # Write to Redis L1
            redis_set_tmdb(cache_key, meta)
            enriched += 1
        else:
            # No TMDB result — cache the miss to avoid re-querying
            try:
                cur.execute("""
                    INSERT INTO tmdb_cache (title_normalized, media_type)
                    VALUES (%s, %s)
                    ON CONFLICT (title_normalized, media_type) DO NOTHING
                """, (title_lower, db_type))
                conn.commit()
            except Exception:
                conn.rollback()

        # Rate limiting
        if api_calls % 35 == 0:
            time.sleep(1)
        else:
            time.sleep(TMDB_RATE_LIMIT_DELAY)

        # Progress update every 100 items
        if i % 100 == 0 and update_fn:
            update_fn(i, total)

    if conn:
        conn.close()

    logger.info(f"TMDB enrichment: {enriched}/{total} enriched, {cached_hits} cache hits, {api_calls} API calls")
    return channels
