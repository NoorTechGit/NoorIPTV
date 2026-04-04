"""
Playlist API endpoints
"""

import base64
import gzip
import json
import uuid
from fastapi import APIRouter, HTTPException, Header, Depends, File, Form, UploadFile
from fastapi.responses import JSONResponse, FileResponse
from typing import Optional
import redis
import os

from app.models.schemas import (
    PlaylistUploadRequest,
    PlaylistUploadResponse,
    PlaylistStatusResponse,
    JobStatus,
    PlaylistResult
)
from app.celery_app import celery_app
from workers.parse_worker import process_playlist

router = APIRouter()

# Redis client for job status tracking
redis_client = redis.Redis.from_url(
    os.getenv("REDIS_URL", "redis://salliptv-redis:6379/0"),
    decode_responses=True
)


def get_device_id(x_device_id: Optional[str] = Header(None)) -> str:
    """Extract device ID from header"""
    if not x_device_id:
        raise HTTPException(status_code=401, detail="Missing device ID")
    return x_device_id


@router.post("/upload", response_model=PlaylistUploadResponse)
async def upload_playlist(
    body: PlaylistUploadRequest,
    x_device_id: Optional[str] = Header(None)
):
    """
    Upload a playlist file for processing

    Accepts a JSON body with device_id, content_type, and raw_content
    (base64-encoded gzip). The device_id may also be supplied via the
    X-Device-ID header; the body field takes precedence.
    Returns a job ID for polling status.
    """
    device_id = body.device_id or x_device_id
    if not device_id:
        raise HTTPException(status_code=401, detail="Missing device ID")

    content_type = body.content_type

    # Validate content type
    if content_type not in ["m3u", "xtream"]:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid content type: {content_type}. Must be 'm3u' or 'xtream'"
        )

    # Decode the base64 payload so we can validate its size
    try:
        content = base64.b64decode(body.raw_content)
    except Exception:
        raise HTTPException(status_code=400, detail="raw_content is not valid base64")

    # Validate content size (max 50MB)
    if len(content) > 50 * 1024 * 1024:
        raise HTTPException(
            status_code=413,
            detail="Content too large. Maximum size is 50MB"
        )

    # Generate job ID
    job_id = str(uuid.uuid4())

    # Decode base64 and save to disk (worker expects a file path)
    upload_dir = os.getenv("UPLOAD_DIR", "/tmp/salliptv_uploads")
    os.makedirs(upload_dir, exist_ok=True)
    file_path = os.path.join(upload_dir, f"{job_id}.gz")

    raw_bytes = base64.b64decode(body.raw_content)
    with open(file_path, "wb") as f:
        f.write(raw_bytes)

    # Set initial status in Redis
    redis_client.setex(
        f"job:{job_id}",
        3600,  # 1 hour expiry
        json.dumps({
            "status": "pending",
            "progress": 0,
            "device_id": device_id,
        })
    )

    # Queue the task — pass file path, not raw content
    task = process_playlist.delay(file_path, content_type, device_id, job_id)

    # Store task ID for tracking
    redis_client.setex(
        f"job_task:{job_id}",
        3600,
        task.id
    )

    return PlaylistUploadResponse(
        job_id=job_id,
        status="processing",
        estimated_seconds=5
    )


@router.post("/upload/file", response_model=PlaylistUploadResponse)
async def upload_playlist_file(
    file: UploadFile = File(...),
    content_type: str = Form(default="m3u"),
    device_id: str = Form(...)
):
    """
    Upload a gzipped playlist file via multipart form.
    Saves to disk and passes file path to worker (no RAM bloat).
    """
    if content_type not in ["m3u", "xtream"]:
        raise HTTPException(status_code=400, detail="Invalid content_type")

    # Save to disk in streaming mode
    upload_dir = os.getenv("UPLOAD_DIR", "/tmp/salliptv_uploads")
    os.makedirs(upload_dir, exist_ok=True)

    job_id = str(uuid.uuid4())
    file_path = os.path.join(upload_dir, f"{job_id}.gz")

    total_size = 0
    max_size = 200 * 1024 * 1024
    with open(file_path, "wb") as f:
        while chunk := await file.read(65536):
            total_size += len(chunk)
            if total_size > max_size:
                os.remove(file_path)
                raise HTTPException(status_code=413, detail="File too large (200MB max)")
            f.write(chunk)

    redis_client.setex(
        f"job:{job_id}", 3600,
        json.dumps({"status": "pending", "progress": 0, "device_id": device_id})
    )

    # Pass file path and job_id to worker
    task = process_playlist.delay(file_path, content_type, device_id, job_id)
    redis_client.setex(f"job_task:{job_id}", 3600, task.id)

    return PlaylistUploadResponse(job_id=job_id, status="processing", estimated_seconds=10)


@router.get("/status/{job_id}", response_model=PlaylistStatusResponse)
async def get_playlist_status(
    job_id: str,
    device_id: str = Depends(get_device_id)
):
    """
    Get the status of a playlist processing job
    """
    # Get job status from Redis
    job_data = redis_client.get(f"job:{job_id}")
    
    if not job_data:
        raise HTTPException(status_code=404, detail="Job not found")
    
    job_info = json.loads(job_data)
    
    # Verify device ownership
    if job_info.get("device_id") != device_id:
        raise HTTPException(status_code=403, detail="Access denied")
    
    # Get task ID
    task_id = redis_client.get(f"job_task:{job_id}")
    
    if task_id:
        # Get Celery task status
        task_result = celery_app.AsyncResult(task_id)
        
        if task_result.state == "PENDING":
            status = JobStatus.PENDING
            progress = 0
        elif task_result.state == "PROCESSING":
            status = JobStatus.PROCESSING
            progress = task_result.info.get("progress", 0) if task_result.info else 0
        elif task_result.state in ("COMPLETED", "SUCCESS"):
            status = JobStatus.COMPLETED
            progress = 100
            result = task_result.result
        elif task_result.state in ("FAILED", "FAILURE"):
            status = JobStatus.FAILED
            progress = 0
            error = str(task_result.info) if task_result.info else "Unknown error"
        else:
            status = JobStatus.PROCESSING
            progress = task_result.info.get("progress", 0) if task_result.info else 0
    else:
        status = JobStatus(job_info.get("status", "pending"))
        progress = job_info.get("progress", 0)
    
    response = PlaylistStatusResponse(
        job_id=job_id,
        status=status,
        progress=progress
    )
    
    # Add result if completed
    if status == JobStatus.COMPLETED and 'result' in locals():
        response.result = result
    
    # Add error if failed
    if status == JobStatus.FAILED and 'error' in locals():
        response.error = error
    
    return response


@router.get("/result/{job_id}")
async def download_result(job_id: str):
    """Download the parsed result as gzipped JSON"""
    upload_dir = os.getenv("UPLOAD_DIR", "/tmp/salliptv_uploads")
    result_path = os.path.join(upload_dir, f"{job_id}_result.json.gz")

    if not os.path.exists(result_path):
        raise HTTPException(status_code=404, detail="Result not found")

    return FileResponse(
        result_path,
        media_type="application/gzip",
        filename=f"{job_id}.json.gz"
    )


@router.get("/test")
async def test_endpoint():
    """Test endpoint to verify API is working"""
    return {"message": "Playlist API is working", "version": "1.0.0"}
