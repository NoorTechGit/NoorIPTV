"""
Celery workers for IPTV playlists:
  - process_playlist: parse M3U → save result JSON → return immediately (~25s)
  - enrich_tmdb: background enrichment with TMDB posters/info (async, hours for 100K+)

The app gets channels fast. TMDB data fills in over time via cache.
Next refresh includes cached TMDB data automatically.
"""

import gzip
import hashlib
import json
import os
import re
import logging
from pathlib import Path
from typing import Dict, Any
from celery import shared_task
from celery.exceptions import MaxRetriesExceededError

logger = logging.getLogger(__name__)

UPLOAD_DIR = Path(os.getenv("UPLOAD_DIR", "/tmp/salliptv_uploads"))
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)


def parse_category(raw_category: str) -> dict:
    """
    Parse IPTV group-title into clean category + lang badge.

    "VOD - ACTION [EN]"  → {"category": "ACTION", "lang": "EN", "prefix": "VOD"}
    "SRS - DISNEY+ [FR]" → {"category": "DISNEY+", "lang": "FR", "prefix": "SRS"}
    "SRS - APPLE+ [MULTISUB]" → {"category": "APPLE+", "lang": "MULTISUB", "prefix": "SRS"}
    "|FR| SPORTS"        → {"category": "SPORTS", "lang": "FR", "prefix": None}
    "F1 - MOTOGP"        → {"category": "F1 - MOTOGP", "lang": None, "prefix": None}
    """
    cat = raw_category.strip()
    lang = None
    prefix = None

    # Extract [XX] language badge at end
    lang_match = re.search(r'\[([A-Z]{2,8})\]\s*$', cat)
    if lang_match:
        lang = lang_match.group(1)
        cat = cat[:lang_match.start()].strip()

    # Extract prefix: "VOD -", "SRS -", etc.
    prefix_match = re.match(r'^(VOD|SRS|SERIES|LIVE|TV)\s*[-–—]\s*', cat, re.IGNORECASE)
    if prefix_match:
        prefix = prefix_match.group(1).upper()
        cat = cat[prefix_match.end():].strip()

    # Extract |XX| language prefix
    if not lang:
        lang_prefix = re.match(r'^\|?([A-Z]{2,5})\|?\s*[-–—:]?\s*', cat)
        if lang_prefix:
            lang = lang_prefix.group(1)
            cat = cat[lang_prefix.end():].strip()

    # Title case
    if cat == cat.upper() and len(cat) > 3:
        cat = cat.title()

    return {"category": cat or raw_category, "lang": lang, "prefix": prefix}


def parse_m3u_streaming(gz_path: str, update_fn=None) -> Dict[str, Any]:
    """Parse M3U from gzipped file, line by line. Never loads full content in RAM."""
    channels = []
    vod = []
    series = []
    current_channel = None

    with gzip.open(gz_path, 'rt', encoding='utf-8', errors='ignore') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            if line.startswith('#EXTINF:'):
                # Extract name: everything after the last comma that follows a quoted attribute
                # Handle: #EXTINF:-1 tvg-logo="x" group-title="y",Channel Name
                # The name is after the last comma following a closing quote or the EXTINF header
                name = "Unknown"
                last_comma = line.rfind(',')
                if last_comma != -1:
                    candidate = line[last_comma + 1:].strip()
                    # If it looks like an attribute (contains = or starts with tvg-), search earlier
                    if '=' not in candidate and not candidate.startswith('tvg-'):
                        name = candidate
                    else:
                        # Fallback: find comma after last closing quote
                        last_quote = line.rfind('"')
                        if last_quote != -1:
                            comma_after = line.find(',', last_quote)
                            if comma_after != -1:
                                name = line[comma_after + 1:].strip()

                # Clean name: remove any remaining tvg- attributes leaked in
                name = re.sub(r'tvg-\w+="[^"]*"', '', name).strip()
                name = re.sub(r'\s+', ' ', name).strip()
                if not name:
                    name = "Unknown"

                logo_match = re.search(r'tvg-logo="([^"]*)"', line)
                logo = logo_match.group(1) if logo_match and logo_match.group(1) else None

                group_match = re.search(r'group-title="([^"]*)"', line)
                category = group_match.group(1) if group_match and group_match.group(1) else "Unknown"

                # Parse category: "VOD - ACTION [EN]" → category="Action", lang="EN"
                cat_info = parse_category(category)
                current_channel = {
                    "name": name,
                    "logo": logo,
                    "category": cat_info["category"],
                }
                if cat_info["lang"]:
                    current_channel["lang"] = cat_info["lang"]

            elif line.startswith('http') and current_channel:
                current_channel["url"] = line

                # Extract stream_id from Xtream URL: http://server/user/pass/12910
                stream_id_match = re.search(r'/(\d+)(?:\.\w+)?$', line)
                if stream_id_match:
                    current_channel["stream_id"] = int(stream_id_match.group(1))

                if '/movie/' in line or '/vod/' in line:
                    vod.append(current_channel)
                elif '/series/' in line:
                    series.append(current_channel)
                else:
                    channels.append(current_channel)

                current_channel = None

                total = len(channels) + len(vod) + len(series)
                if total % 5000 == 0 and update_fn:
                    update_fn(min(80, 30 + total // 500))

    return {"channels": channels, "vod": vod, "series": series}


def dedup_live_channels(channels: list) -> list:
    """
    Process live channels: extract badges, assign groupId for quality variants.

    "TF1 HD" and "TF1 FHD" get the same groupId so the app can group them.
    All channels are kept (for quality switching), but marked with groupId.
    The app shows only the best quality and offers alternatives.
    """
    try:
        from workers.tmdb_enricher import extract_badges
    except ImportError:
        return channels

    QUALITY_RANK = {"4K": 5, "UHD": 5, "FHD": 4, "HD": 3, "SD": 1}
    groups = {}  # (clean_name_lower, category) → list of channels

    for ch in channels:
        badges = extract_badges(ch.get("name", ""))
        clean = badges["clean_title"] or ch.get("name", "")
        quality = badges.get("quality")
        lang = badges.get("lang") or ch.get("lang")

        if lang:
            ch["lang"] = lang
        if quality:
            ch["quality"] = quality
        if clean:
            ch["clean_name"] = clean

        key = (clean.lower(), ch.get("category", ""))
        if key not in groups:
            groups[key] = []
        groups[key].append(ch)

    # Assign groupId and sort by quality within each group
    for (clean_lower, cat), variants in groups.items():
        group_id = f"{cat}_{clean_lower}".replace(" ", "_")

        # Sort: best quality first
        variants.sort(key=lambda c: QUALITY_RANK.get(c.get("quality", ""), 0), reverse=True)

        for i, ch in enumerate(variants):
            ch["group_id"] = group_id
            ch["alt_count"] = len(variants) - 1
            ch["is_primary"] = (i == 0)

    # Flatten back to list
    result = [ch for variants in groups.values() for ch in variants]

    primary_count = sum(1 for ch in result if ch.get("is_primary", True))
    logger.info(f"Processed {len(channels)} live channels: {primary_count} unique, {len(result) - primary_count} alternatives")
    return result


def group_series(episodes: list) -> list:
    """
    Group 567K episodes into unique series.
    "Breaking Bad S01E01", "Breaking Bad S01E02" → one entry "Breaking Bad"
    with episode count, season list, and first episode URL.
    """
    try:
        from workers.tmdb_enricher import extract_badges
    except ImportError:
        return episodes

    series_map = {}  # clean_name → series info

    for ep in episodes:
        raw_name = ep.get("name", "")
        badges = extract_badges(raw_name)
        clean = badges["clean_title"] or raw_name
        season = badges.get("season")
        episode_num = badges.get("episode")
        lang = badges.get("lang") or ep.get("lang")
        quality = badges.get("quality")

        key = f"{clean.lower()}|{lang or ''}"

        if key not in series_map:
            series_map[key] = {
                "name": raw_name,
                "clean_name": clean,
                "logo": ep.get("logo"),
                "category": ep.get("category"),
                "url": ep.get("url"),  # first episode URL
                "lang": lang,
                "quality": quality,
                "seasons": set(),
                "episode_count": 0,
            }
            # Copy TMDB metadata from enrichment
            for k in ("poster_hd", "backdrop", "overview", "rating", "year", "genres", "cast", "tmdb_id"):
                if k in ep:
                    series_map[key][k] = ep[k]

        series_map[key]["episode_count"] += 1
        if season:
            series_map[key]["seasons"].add(season)
        # Keep best quality
        if quality and (not series_map[key].get("quality") or quality in ("4K", "FHD")):
            series_map[key]["quality"] = quality
        # Keep poster if found
        if ep.get("poster_hd") and not series_map[key].get("poster_hd"):
            series_map[key]["poster_hd"] = ep["poster_hd"]
        if ep.get("backdrop") and not series_map[key].get("backdrop"):
            series_map[key]["backdrop"] = ep["backdrop"]

    # Convert to list
    result = []
    for info in series_map.values():
        entry = {
            "name": info["name"],
            "clean_name": info["clean_name"],
            "logo": info.get("logo"),
            "category": info.get("category"),
            "url": info.get("url"),
            "episode_count": info["episode_count"],
            "season_count": len(info["seasons"]),
        }
        if info.get("lang"):
            entry["lang"] = info["lang"]
        if info.get("quality"):
            entry["quality"] = info["quality"]
        for k in ("poster_hd", "backdrop", "overview", "rating", "year", "genres", "cast"):
            if info.get(k):
                entry[k] = info[k]
        result.append(entry)

    logger.info(f"Grouped {len(episodes)} episodes into {len(result)} series")
    return result


def try_enrich_from_cache(channels, media_type):
    """
    Quick pass: enrich channels from Redis/PostgreSQL cache only.
    No TMDB API calls. Skips if cache is empty (first run).
    """
    try:
        from workers.tmdb_enricher import get_redis, get_db, _row_to_meta, _apply_metadata, normalize_title
    except ImportError:
        return channels

    # Check if cache has any data — if empty, skip entirely (first run)
    conn = None
    try:
        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT COUNT(*) as cnt FROM tmdb_cache")
        cache_size = cur.fetchone()["cnt"]
        if cache_size == 0:
            conn.close()
            logger.info(f"TMDB cache empty, skipping cache enrichment for {len(channels)} {media_type}")
            return channels
    except Exception:
        if conn:
            conn.close()
        return channels

    r = get_redis()
    db_type = "series" if media_type == "series" else "movie"
    hits = 0

    # Load all cached titles for this media type in one query (batch)
    try:
        cur.execute(
            "SELECT title_normalized, poster_path, backdrop_path, overview, release_year, "
            "vote_average, genres, cast_names, tmdb_id FROM tmdb_cache "
            "WHERE media_type = %s AND tmdb_id IS NOT NULL",
            (db_type,)
        )
        cache_map = {row["title_normalized"]: row for row in cur.fetchall()}
    except Exception:
        cache_map = {}

    conn.close()

    if not cache_map:
        logger.info(f"No cached {media_type} titles, skipping")
        return channels

    for ch in channels:
        title = normalize_title(ch.get("name", ""))
        if not title or len(title) < 2:
            continue
        title_lower = title.lower().strip()

        row = cache_map.get(title_lower)
        if row:
            meta = _row_to_meta(row)
            _apply_metadata(ch, meta)
            hits += 1

    logger.info(f"Cache enrichment: {hits}/{len(channels)} hits for {media_type} (cache has {len(cache_map)} entries)")
    return channels


@shared_task(bind=True, max_retries=3, default_retry_delay=10)
def process_playlist(self, file_path: str, content_type: str, device_id: str, job_id: str = None) -> Dict[str, Any]:
    """
    Parse playlist → enrich from cache only (instant) → save result → return.
    Kicks off background TMDB enrichment task separately.
    """
    try:
        self.update_state(state='PROCESSING', meta={'progress': 10})

        if not os.path.exists(file_path):
            raise FileNotFoundError(f"Upload file not found: {file_path}")

        file_size = os.path.getsize(file_path)
        logger.info(f"Processing {content_type} file: {file_path} ({file_size} bytes)")

        self.update_state(state='PROCESSING', meta={'progress': 20})

        if content_type == "m3u":
            parsed = parse_m3u_streaming(
                file_path,
                update_fn=lambda p: self.update_state(state='PROCESSING', meta={'progress': p})
            )
        elif content_type == "xtream":
            self.update_state(state='PROCESSING', meta={'progress': 20})

            with gzip.open(file_path, 'rt', encoding='utf-8') as f:
                raw_data = json.load(f)

            # Split by type: flat array of channel objects with a "type" field
            parsed = {"channels": [], "vod": [], "series": []}
            for item in raw_data:
                ch = {
                    "name": item.get("name", ""),
                    "logo": item.get("logo"),
                    "category": item.get("category", "Unknown"),
                    "url": item.get("url", ""),
                    "stream_id": item.get("stream_id", 0),
                }
                # Copy provider metadata (Xtream gives us poster/plot for VOD/series)
                for field in ("plot", "cast", "rating", "poster", "backdrop"):
                    if item.get(field):
                        ch[field] = item[field]

                item_type = item.get("type", "LIVE")
                if item_type == "VOD":
                    parsed["vod"].append(ch)
                elif item_type == "SERIES":
                    parsed["series"].append(ch)
                else:
                    parsed["channels"].append(ch)

            logger.info(f"Parsed Xtream data: {len(parsed['channels'])} live, {len(parsed['vod'])} vod, {len(parsed['series'])} series")
        else:
            raise ValueError(f"Unknown content type: {content_type}")

        self.update_state(state='PROCESSING', meta={'progress': 80})

        # Extract badges from all channels (lang, quality, year — instant)
        try:
            from workers.tmdb_enricher import extract_badges
            for ch in parsed["channels"]:
                b = extract_badges(ch.get("name", ""))
                if b["lang"]: ch["lang"] = b["lang"]
                if b["quality"]: ch["quality"] = b["quality"]
                if b["clean_title"]: ch["clean_name"] = b["clean_title"]
            if content_type == "xtream":
                # Apply badges to VOD and series too for Xtream data
                for ch in parsed["vod"] + parsed["series"]:
                    b = extract_badges(ch.get("name", ""))
                    if b["lang"]: ch["lang"] = b["lang"]
                    if b["quality"]: ch["quality"] = b["quality"]
                    if b["clean_title"]: ch["clean_name"] = b["clean_title"]
        except ImportError:
            pass

        # Clean category names: just remove "SRS -" / "VOD -" prefix, keep [FR] [EN] etc.
        if content_type == "xtream":
            for ch in parsed["channels"] + parsed["vod"] + parsed["series"]:
                raw_cat = ch.get("category", "Unknown")
                # Remove only the type prefix (SRS -, VOD -, LIVE -, TV -)
                cleaned = re.sub(r'^(VOD|SRS|SERIES|LIVE|TV)\s*[-–—]\s*', '', raw_cat.strip(), flags=re.IGNORECASE).strip()
                if cleaned:
                    ch["category"] = cleaned
                # Extract lang for badge field
                lang_match = re.search(r'\[([A-Z]{2,8})\]\s*$', cleaned)
                if lang_match and not ch.get("lang"):
                    ch["lang"] = lang_match.group(1)

        # Quick cache-only enrichment for VOD/Series (no API calls, instant)
        vod_enriched = try_enrich_from_cache(parsed["vod"], "movie")
        series_enriched = try_enrich_from_cache(parsed["series"], "series")

        self.update_state(state='PROCESSING', meta={'progress': 85})

        # Fingerprint
        names = [c["name"] for c in parsed["channels"][:1000]]
        normalized = sorted([n.lower().strip() for n in names if n])
        fingerprint = hashlib.sha256(json.dumps(normalized).encode()).hexdigest()[:16]

        self.update_state(state='PROCESSING', meta={'progress': 90})

        # Save result JSON gzip
        result_file = None
        if job_id:
            result_path = str(UPLOAD_DIR / f"{job_id}_result.json.gz")

            if content_type == "xtream":
                # Xtream data is already clean: skip dedup and series grouping
                # Series are already one entry per show (not per episode)
                live = [dict(ch, type="LIVE") for ch in parsed["channels"]]
                try:
                    from workers.tmdb_enricher import enrich_live_channel_logos
                    live_enriched = enrich_live_channel_logos([dict(ch) for ch in parsed["channels"]])
                    live = [dict(ch, type="LIVE") for ch in live_enriched]
                except Exception:
                    pass

                # VOD: TMDB poster wins, provider poster as fallback
                vod_list = []
                for ch in vod_enriched:
                    entry = dict(ch, type="VOD")
                    if not entry.get("poster_hd") and entry.get("poster"):
                        entry["poster_hd"] = entry["poster"]
                    if not entry.get("backdrop") and entry.get("backdrop"):
                        pass  # already set by TMDB
                    vod_list.append(entry)

                # Series: TMDB poster wins, provider poster as fallback
                series_list = []
                for ch in series_enriched:
                    entry = dict(ch, type="SERIES")
                    if not entry.get("poster_hd") and entry.get("poster"):
                        entry["poster_hd"] = entry["poster"]
                    series_list.append(entry)
            else:
                # M3U path: deduplicate live channels + group series episodes
                live_deduped = dedup_live_channels(parsed["channels"])
                try:
                    from workers.tmdb_enricher import enrich_live_channel_logos
                    live_deduped = enrich_live_channel_logos(live_deduped)
                except Exception:
                    pass
                live = [dict(ch, type="LIVE") for ch in live_deduped]
                vod_list = [dict(ch, type="VOD") for ch in vod_enriched]

                # Group series episodes into unique series entries
                series_grouped = group_series(series_enriched)
                series_list = [dict(ch, type="SERIES") for ch in series_grouped]

            all_channels = live + vod_list + series_list

            with gzip.open(result_path, 'wt', encoding='utf-8') as f:
                f.write('[')
                first = True
                for ch in all_channels:
                    if not first:
                        f.write(',')
                    json.dump(ch, f, ensure_ascii=False)
                    first = False
                f.write(']')

            result_file = result_path
            logger.info(f"Saved result: {result_path} ({len(all_channels)} entries)")

        self.update_state(state='PROCESSING', meta={'progress': 95})

        # Cleanup source gzip
        try:
            os.remove(file_path)
        except OSError:
            pass

        result = {
            "fingerprint": fingerprint,
            "total_channels": len(parsed["channels"]),
            "total_vod": len(parsed["vod"]),
            "total_series": len(parsed["series"]),
        }
        if result_file:
            result["result_file"] = result_file

        logger.info(f"Done: {result['total_channels']} live, {result['total_vod']} vod, {result['total_series']} series")

        # Kick off background TMDB enrichment (non-blocking)
        # Send unique names (first 20K each) for background TMDB enrichment
        vod_names = list(set(ch.get("name", "") for ch in parsed["vod"] if ch.get("name")))[:20000]
        series_names = list(set(ch.get("name", "") for ch in parsed["series"] if ch.get("name")))[:20000]
        enrich_tmdb_background.delay(vod_names, series_names)

        self.update_state(state='COMPLETED', meta={'progress': 100, 'result': result})
        return result

    except Exception as exc:
        logger.error(f"Error processing playlist: {exc}")
        try:
            if os.path.exists(file_path):
                os.remove(file_path)
        except OSError:
            pass
        try:
            self.retry(countdown=10)
        except MaxRetriesExceededError:
            self.update_state(state='FAILED', meta={'error': str(exc)})
            raise


@shared_task(bind=True, max_retries=1)
def enrich_tmdb_background(self, vod_names, series_names):
    """
    Background task: populate TMDB cache for titles not yet cached.
    Runs on 'ml' queue so it doesn't block parsing.
    Next playlist refresh will automatically include these results.
    """
    try:
        from workers.tmdb_enricher import enrich_channels

        logger.info(f"Background TMDB enrichment: {len(vod_names)} VOD + {len(series_names)} series")

        # Convert names to minimal channel dicts for the enricher
        vod_channels = [{"name": n} for n in vod_names]
        series_channels = [{"name": n} for n in series_names]

        enrich_channels(vod_channels, "movie")
        enrich_channels(series_channels, "series")

        logger.info("Background TMDB enrichment completed")
    except Exception as e:
        logger.error(f"Background TMDB error: {e}")


@shared_task(bind=True, max_retries=0)
def continue_tmdb_enrichment(self):
    """
    Continuous TMDB enrichment — runs every 5 min via beat.
    Finds uncached titles from the latest result file and enriches a batch.
    Stops when all titles are cached.
    """
    try:
        import psycopg2
        from psycopg2.extras import RealDictCursor
        from workers.tmdb_enricher import enrich_channels, get_db

        conn = get_db()
        cur = conn.cursor()

        # Count uncached titles
        cur.execute("SELECT COUNT(*) as cnt FROM tmdb_cache WHERE tmdb_id IS NULL AND poster_path IS NULL")
        uncached_misses = cur.fetchone()["cnt"]
        cur.execute("SELECT COUNT(*) as cnt FROM tmdb_cache")
        total_cached = cur.fetchone()["cnt"]
        conn.close()

        # Find latest result file to get fresh titles
        result_files = sorted(UPLOAD_DIR.glob("*_result.json.gz"), key=lambda f: f.stat().st_mtime, reverse=True)
        if not result_files:
            return {"status": "no_result_files"}

        latest = result_files[0]

        # Read uncached titles from result
        import gzip as gz
        titles_to_enrich = []
        with gz.open(str(latest), 'rt', encoding='utf-8', errors='ignore') as f:
            import json as j
            data = j.load(f)
            # Get VOD and SERIES names not yet in cache
            conn2 = get_db()
            cur2 = conn2.cursor()
            for ch in data:
                if ch.get("type") not in ("VOD", "SERIES"):
                    continue
                name = ch.get("name", "")
                if not name:
                    continue
                from workers.tmdb_enricher import normalize_title
                normalized = normalize_title(name).lower().strip()
                if not normalized or len(normalized) < 2:
                    continue
                media = "series" if ch.get("type") == "SERIES" else "movie"
                cur2.execute(
                    "SELECT 1 FROM tmdb_cache WHERE title_normalized = %s AND media_type = %s",
                    (normalized, media)
                )
                if not cur2.fetchone():
                    titles_to_enrich.append({"name": name, "type": media})
            conn2.close()

        if not titles_to_enrich:
            logger.info(f"TMDB enrichment complete — all titles cached ({total_cached} total)")
            return {"status": "complete", "total_cached": total_cached}

        # Enrich a batch of 500
        batch = titles_to_enrich[:500]
        vod_batch = [t for t in batch if t["type"] == "movie"]
        series_batch = [t for t in batch if t["type"] == "series"]

        logger.info(f"Continuing TMDB enrichment: {len(vod_batch)} VOD + {len(series_batch)} series (remaining: {len(titles_to_enrich)})")

        if vod_batch:
            enrich_channels([{"name": t["name"]} for t in vod_batch], "movie")
        if series_batch:
            enrich_channels([{"name": t["name"]} for t in series_batch], "series")

        return {"status": "enriched", "batch": len(batch), "remaining": len(titles_to_enrich) - len(batch)}

    except Exception as e:
        logger.error(f"Continue TMDB enrichment error: {e}")
        return {"status": "error", "error": str(e)}


@shared_task(bind=True, max_retries=0)
def enrich_logos_background(self):
    """
    Background task: find HD logos for live channels via TMDB.
    Runs periodically via beat. Caches in Redis.
    """
    try:
        import gzip as gz
        from workers.tmdb_enricher import search_channel_logo, normalize_title, get_redis
        import time as t

        r = get_redis()
        if not r:
            return {"status": "no_redis"}

        # Find latest result file
        result_files = sorted(UPLOAD_DIR.glob("*_result.json.gz"), key=lambda f: f.stat().st_mtime, reverse=True)
        if not result_files:
            return {"status": "no_result_files"}

        # Get unique live channel names not yet cached
        with gz.open(str(result_files[0]), 'rt', encoding='utf-8', errors='ignore') as f:
            data = json.load(f)

        live_names = set()
        for ch in data:
            if ch.get("type") == "LIVE":
                name = normalize_title(ch.get("name", "")).lower().strip()
                if name and len(name) >= 2:
                    # Check if already cached
                    if not r.exists(f"logo:{name}"):
                        live_names.add(name)

        if not live_names:
            logger.info("All live channel logos are cached")
            return {"status": "complete"}

        # Enrich batch of 100
        batch = list(live_names)[:100]
        found = 0
        for name in batch:
            logo_url = search_channel_logo(name)
            if logo_url:
                r.setex(f"logo:{name}", 30 * 86400, logo_url)
                found += 1
            else:
                r.setex(f"logo:{name}", 7 * 86400, "none")  # Cache miss for 7 days
            t.sleep(0.05)  # Rate limit

        logger.info(f"Logo enrichment: {found}/{len(batch)} found, {len(live_names) - len(batch)} remaining")
        return {"status": "enriched", "found": found, "batch": len(batch), "remaining": len(live_names) - len(batch)}

    except Exception as e:
        logger.error(f"Logo enrichment error: {e}")
        return {"status": "error", "error": str(e)}
