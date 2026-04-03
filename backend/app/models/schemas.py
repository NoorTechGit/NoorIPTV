"""
Pydantic models for API requests/responses
"""

from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any
from datetime import datetime
from enum import Enum


class PlaylistType(str, Enum):
    M3U = "m3u"
    XTREAM = "xtream"


class PlaylistUploadResponse(BaseModel):
    job_id: str = Field(..., description="Unique job identifier for polling")
    status: str = Field(default="processing", description="Job status")
    estimated_seconds: int = Field(default=5, description="Estimated processing time")


class JobStatus(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"


class PlaylistStatusResponse(BaseModel):
    job_id: str
    status: JobStatus
    progress: Optional[int] = Field(None, description="Progress percentage (0-100)")
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class Channel(BaseModel):
    id: str
    name: str
    logo: Optional[str] = None
    category: Optional[str] = None
    tmdb_id: Optional[int] = None
    stream_url_encrypted: str


class PlaylistResult(BaseModel):
    fingerprint: str
    channels: List[Channel]
    vod: List[Dict[str, Any]]
    series: List[Dict[str, Any]]


class PlaylistUploadRequest(BaseModel):
    device_id: str
    content_type: str  # "m3u" or "xtream"
    raw_content: str   # base64 encoded gzip content


class DeviceRegisterRequest(BaseModel):
    device_id: str
    device_model: Optional[str] = None
    app_version: Optional[str] = None


class DeviceRegisterResponse(BaseModel):
    user_id: str
    token: str
    fingerprint_group: Optional[str] = None
