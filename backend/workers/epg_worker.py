"""
EPG Worker — Downloads and parses open source XMLTV EPG data.
Stores in Redis for fast lookup by channel name/tvg-id.

Sources:
- iptv-org/epg: covers 10K+ channels worldwide
- Custom XMLTV URLs from M3U headers

Redis keys:
  epg:name:{normalized_name} → JSON list of programs
  epg:id:{tvg_id} → JSON list of programs
  epg:last_update → timestamp

Each program: {"title", "start", "end", "desc", "category", "icon"}
"""

import gzip
import json
import logging
import os
import re
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from io import BytesIO
from typing import Dict, List, Optional

import httpx
import redis

logger = logging.getLogger(__name__)

REDIS_URL = os.getenv("REDIS_URL", "redis://salliptv-redis:6379/0")
EPG_TTL = 48 * 3600  # 48 hours

# Open source EPG sources — multiple for better coverage
EPG_SOURCES = [
    # France + international
    "https://epgshare01.online/epgshare01/epg_ripper_FR1.xml.gz",
    # Global EPG (huge, covers many countries)
    "http://epg.51zmt.top:8000/e.xml.gz",
    # mjh.nz (good coverage)
    "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/all/epg.xml.gz",
]

_redis = None


def get_redis():
    global _redis
    if _redis is None:
        _redis = redis.Redis.from_url(REDIS_URL, decode_responses=True)
    return _redis


def normalize_channel_name(name: str) -> str:
    """Normalize channel name for matching: 'TF1 HD' → 'tf1'"""
    n = name.lower().strip()
    # Remove quality suffixes
    n = re.sub(r'\s*(hd|fhd|uhd|4k|sd|hevc|h\.?265)\s*$', '', n, flags=re.IGNORECASE)
    # Remove common suffixes: TV, Channel, +1, etc.
    n = re.sub(r'\s*(tv|channel|chan|televizija|televize)\s*$', '', n, flags=re.IGNORECASE)
    n = re.sub(r'\s*\+\d+\s*$', '', n)  # +1, +2 etc
    # Remove country prefixes
    n = re.sub(r'^[a-z]{2,3}\s*[-|:]\s*', '', n)
    # Remove special chars
    n = re.sub(r'[^a-z0-9\s]', '', n)
    n = re.sub(r'\s+', ' ', n).strip()
    return n


def parse_xmltv_time(timestr: str) -> int:
    """Parse XMLTV time format '20240103120000 +0100' → unix timestamp"""
    try:
        # Remove timezone offset for simpler parsing
        clean = timestr.strip()
        if ' ' in clean:
            dt_part, tz_part = clean.rsplit(' ', 1)
        else:
            dt_part = clean
            tz_part = "+0000"

        dt = datetime.strptime(dt_part, "%Y%m%d%H%M%S")

        # Apply timezone offset
        sign = 1 if tz_part[0] == '+' else -1
        tz_hours = int(tz_part[1:3])
        tz_mins = int(tz_part[3:5])
        offset_seconds = sign * (tz_hours * 3600 + tz_mins * 60)

        # Convert to UTC timestamp
        timestamp = int(dt.replace(tzinfo=timezone.utc).timestamp()) - offset_seconds
        return timestamp
    except Exception:
        return 0


def download_and_parse_epg(url: str) -> Dict[str, List[dict]]:
    """
    Download XMLTV EPG and parse into channel → programs mapping.
    Streams XML parsing to avoid loading huge files in RAM.
    """
    programs_by_channel = {}  # channel_id → [programs]
    channel_names = {}  # channel_id → display_name

    try:
        logger.info(f"Downloading EPG: {url}")
        with httpx.Client(timeout=60, follow_redirects=True) as client:
            resp = client.get(url)
            if resp.status_code != 200:
                logger.warning(f"EPG download failed: {resp.status_code} for {url}")
                return {}

            content = resp.content

        # Decompress if gzipped
        if url.endswith('.gz') or content[:2] == b'\x1f\x8b':
            content = gzip.decompress(content)

        logger.info(f"Parsing EPG XML ({len(content)} bytes)")

        # Parse XML
        root = ET.fromstring(content)

        # Extract channel names
        for ch_elem in root.findall('channel'):
            ch_id = ch_elem.get('id', '')
            display = ch_elem.find('display-name')
            if display is not None and display.text:
                channel_names[ch_id] = display.text.strip()

        # Extract programs
        now = int(time.time())
        cutoff_past = now - 6 * 3600      # 6 hours ago
        cutoff_future = now + 48 * 3600    # 48 hours ahead

        for prog_elem in root.findall('programme'):
            ch_id = prog_elem.get('channel', '')
            start_str = prog_elem.get('start', '')
            stop_str = prog_elem.get('stop', '')

            start = parse_xmltv_time(start_str)
            end = parse_xmltv_time(stop_str)

            # Skip programs outside our window
            if end < cutoff_past or start > cutoff_future:
                continue

            title_elem = prog_elem.find('title')
            desc_elem = prog_elem.find('desc')
            cat_elem = prog_elem.find('category')
            icon_elem = prog_elem.find('icon')

            program = {
                "title": title_elem.text.strip() if title_elem is not None and title_elem.text else "",
                "start": start,
                "end": end,
            }

            if desc_elem is not None and desc_elem.text:
                program["desc"] = desc_elem.text.strip()[:500]
            if cat_elem is not None and cat_elem.text:
                program["cat"] = cat_elem.text.strip()
            if icon_elem is not None and icon_elem.get('src'):
                program["icon"] = icon_elem.get('src')

            if ch_id not in programs_by_channel:
                programs_by_channel[ch_id] = []
            programs_by_channel[ch_id].append(program)

        # Sort programs by start time
        for ch_id in programs_by_channel:
            programs_by_channel[ch_id].sort(key=lambda p: p["start"])

        logger.info(f"Parsed {len(programs_by_channel)} channels, {sum(len(p) for p in programs_by_channel.values())} programs from {url}")

    except Exception as e:
        logger.error(f"EPG parse error for {url}: {e}")

    return programs_by_channel, channel_names


def store_epg_in_redis(programs_by_channel: dict, channel_names: dict):
    """Store EPG data in Redis, indexed by channel name and ID."""
    r = get_redis()
    pipe = r.pipeline()
    stored = 0

    for ch_id, programs in programs_by_channel.items():
        if not programs:
            continue

        data = json.dumps(programs, ensure_ascii=False)

        # Store by channel ID (tvg-id)
        pipe.setex(f"epg:id:{ch_id.lower()}", EPG_TTL, data)

        # Store by normalized channel name
        name = channel_names.get(ch_id, ch_id)
        normalized = normalize_channel_name(name)
        if normalized:
            pipe.setex(f"epg:name:{normalized}", EPG_TTL, data)

        stored += 1

        # Execute batch every 500
        if stored % 500 == 0:
            pipe.execute()
            pipe = r.pipeline()

    pipe.setex("epg:last_update", EPG_TTL, str(int(time.time())))
    pipe.execute()

    logger.info(f"Stored EPG for {stored} channels in Redis")
    return stored


def refresh_epg():
    """Download all EPG sources and store in Redis."""
    total_channels = 0

    for url in EPG_SOURCES:
        try:
            result = download_and_parse_epg(url)
            if isinstance(result, tuple):
                programs, names = result
                count = store_epg_in_redis(programs, names)
                total_channels += count
        except Exception as e:
            logger.error(f"Failed to process EPG source {url}: {e}")

    logger.info(f"EPG refresh complete: {total_channels} channels total")
    return total_channels


# Celery task
from celery import shared_task


@shared_task(bind=True, max_retries=2)
def refresh_epg_task(self):
    """Celery task to refresh EPG data. Run daily via beat."""
    try:
        count = refresh_epg()
        return {"channels": count, "timestamp": int(time.time())}
    except Exception as e:
        logger.error(f"EPG refresh task failed: {e}")
        self.retry(countdown=300)
