"""
Authentication router for SallIPTV
Handles device activation, token management, and security events
"""

from datetime import datetime, timedelta
from typing import Optional
import hashlib
import json
import os
import secrets

from fastapi import APIRouter, Depends, HTTPException, Header
from pydantic import BaseModel
from jose import jwt, JWTError
from redis.asyncio import Redis

router = APIRouter(tags=["authentication"])

# JWT Configuration
JWT_ALGORITHM = "HS256"
JWT_EXPIRATION_HOURS = 24
REFRESH_TOKEN_EXPIRATION_DAYS = 30

# TODO: Move to settings
SECRET_KEY = "your-secret-key-change-in-production"
ADMIN_TOKEN = "your-admin-token-change-in-production"


class DeviceRegistrationRequest(BaseModel):
    device_id: str
    fingerprint: str
    activation_code: Optional[str] = None
    is_premium: bool = False


class DeviceRegistrationResponse(BaseModel):
    device_id: str
    is_premium: bool
    message: str


class ActivationRequest(BaseModel):
    code: str
    device_id: str
    fingerprint: str


class ActivationResponse(BaseModel):
    token: str
    refresh_token: str
    expires_in: int
    device_id: str


class TokenRefreshRequest(BaseModel):
    refresh_token: str
    device_id: str


class TokenRefreshResponse(BaseModel):
    token: str
    expires_in: int


class VerifyRequest(BaseModel):
    device_id: str
    challenge: str
    proof: str


class VerifyResponse(BaseModel):
    valid: bool
    is_premium: bool


class DeviceStatusResponse(BaseModel):
    device_id: str
    is_premium: bool
    is_banned: bool
    activated_at: Optional[datetime]
    expires_at: Optional[datetime]


class BreachReportRequest(BaseModel):
    device_id: str
    reason: str
    details: Optional[str] = None


def generate_token(device_id: str, is_premium: bool) -> str:
    """Generate JWT token for device"""
    payload = {
        "device_id": device_id,
        "is_premium": is_premium,
        "iat": datetime.utcnow(),
        "exp": datetime.utcnow() + timedelta(hours=JWT_EXPIRATION_HOURS),
        "type": "access"
    }
    return jwt.encode(payload, SECRET_KEY, algorithm=JWT_ALGORITHM)


def generate_refresh_token(device_id: str) -> str:
    """Generate refresh token"""
    payload = {
        "device_id": device_id,
        "iat": datetime.utcnow(),
        "exp": datetime.utcnow() + timedelta(days=REFRESH_TOKEN_EXPIRATION_DAYS),
        "type": "refresh",
        "jti": secrets.token_hex(16)
    }
    return jwt.encode(payload, SECRET_KEY, algorithm=JWT_ALGORITHM)


def verify_token(token: str) -> Optional[dict]:
    """Verify and decode JWT token"""
    try:
        return jwt.decode(token, SECRET_KEY, algorithms=[JWT_ALGORITHM])
    except JWTError:
        return None


def generate_challenge() -> str:
    """Generate challenge for proof verification"""
    return secrets.token_hex(16)


def verify_proof(device_id: str, fingerprint: str, challenge: str, proof: str) -> bool:
    """Verify device proof (challenge/response)"""
    expected = hashlib.sha256(f"{challenge}|{device_id}|{fingerprint}".encode()).hexdigest()
    return secrets.compare_digest(expected, proof)


_redis_client = None

async def get_redis() -> Redis:
    """Get async Redis connection"""
    global _redis_client
    if _redis_client is None:
        redis_url = os.getenv("REDIS_URL", "redis://salliptv-redis:6379/0")
        _redis_client = Redis.from_url(redis_url, decode_responses=False)
    return _redis_client


@router.post("/register", response_model=DeviceRegistrationResponse)
async def register_device(request: DeviceRegistrationRequest):
    """Register a new device or update existing device info"""
    redis = await get_redis()
    
    # Check if device is banned
    is_banned = await redis.exists(f"ban:{request.device_id}")
    if is_banned:
        raise HTTPException(status_code=403, detail="Device banned")
    
    # Store device info
    device_key = f"device:{request.device_id}"
    await redis.hset(device_key, mapping={
        "fingerprint": request.fingerprint,
        "last_seen": datetime.utcnow().isoformat(),
        "is_premium": str(request.is_premium).lower()
    })
    await redis.expire(device_key, 90 * 24 * 3600)
    
    return DeviceRegistrationResponse(
        device_id=request.device_id,
        is_premium=request.is_premium,
        message="Device registered successfully"
    )


@router.post("/activate", response_model=ActivationResponse)
async def activate_device(request: ActivationRequest):
    """Activate a device with an activation code"""
    redis = await get_redis()
    
    if await redis.exists(f"ban:{request.device_id}"):
        raise HTTPException(status_code=403, detail="Device banned")
    
    # Validate code (simplified - replace with your logic)
    code_valid = await validate_activation_code(request.code, redis)
    if not code_valid:
        raise HTTPException(status_code=400, detail="Invalid activation code")
    
    # Generate tokens
    token = generate_token(request.device_id, is_premium=True)
    refresh_token = generate_refresh_token(request.device_id)
    
    # Store activation
    await redis.hset(f"device:{request.device_id}", mapping={
        "is_premium": "true",
        "activated_at": datetime.utcnow().isoformat(),
        "fingerprint": request.fingerprint,
    })
    
    return ActivationResponse(
        token=token,
        refresh_token=refresh_token,
        expires_in=JWT_EXPIRATION_HOURS * 3600,
        device_id=request.device_id
    )


@router.post("/refresh", response_model=TokenRefreshResponse)
async def refresh_token(request: TokenRefreshRequest):
    """Refresh access token using refresh token"""
    try:
        payload = jwt.decode(request.refresh_token, SECRET_KEY, algorithms=[JWT_ALGORITHM])
        
        if payload.get("type") != "refresh":
            raise HTTPException(status_code=400, detail="Invalid token type")
        
        if payload["device_id"] != request.device_id:
            raise HTTPException(status_code=400, detail="Device mismatch")
            
    except JWTError:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    
    redis = await get_redis()
    if await redis.exists(f"ban:{request.device_id}"):
        raise HTTPException(status_code=403, detail="Device banned")
    
    is_premium = await redis.hget(f"device:{request.device_id}", "is_premium")
    is_premium = is_premium == b"true" if is_premium else False
    
    new_token = generate_token(request.device_id, is_premium)
    
    return TokenRefreshResponse(token=new_token, expires_in=JWT_EXPIRATION_HOURS * 3600)


@router.post("/verify", response_model=VerifyResponse)
async def verify_device(request: VerifyRequest):
    """Verify device with challenge/response"""
    redis = await get_redis()
    
    if await redis.exists(f"ban:{request.device_id}"):
        return VerifyResponse(valid=False, is_premium=False)
    
    fingerprint = await redis.hget(f"device:{request.device_id}", "fingerprint")
    if not fingerprint:
        return VerifyResponse(valid=False, is_premium=False)
    
    valid = verify_proof(request.device_id, fingerprint.decode(), 
                        request.challenge, request.proof)
    
    if not valid:
        return VerifyResponse(valid=False, is_premium=False)
    
    is_premium = await redis.hget(f"device:{request.device_id}", "is_premium")
    is_premium = is_premium == b"true" if is_premium else False
    
    return VerifyResponse(valid=True, is_premium=is_premium)


@router.get("/device/{device_id}/status", response_model=DeviceStatusResponse)
async def get_device_status(device_id: str):
    """Get device status"""
    redis = await get_redis()
    
    is_banned = await redis.exists(f"ban:{device_id}")
    device_info = await redis.hgetall(f"device:{device_id}")
    
    if not device_info:
        return DeviceStatusResponse(
            device_id=device_id, is_premium=False, is_banned=is_banned,
            activated_at=None, expires_at=None
        )
    
    is_premium = device_info.get(b"is_premium") == b"true"
    activated_at = device_info.get(b"activated_at")
    
    return DeviceStatusResponse(
        device_id=device_id,
        is_premium=is_premium,
        is_banned=is_banned,
        activated_at=datetime.fromisoformat(activated_at.decode()) if activated_at else None,
        expires_at=None
    )


@router.post("/security/breach")
async def report_breach(request: BreachReportRequest):
    """Report a security breach from device"""
    redis = await get_redis()
    
    # Log the breach
    breach_key = f"breach:{request.device_id}:{datetime.utcnow().timestamp()}"
    await redis.hset(breach_key, mapping={
        "reason": request.reason,
        "details": request.details or "",
        "timestamp": datetime.utcnow().isoformat()
    })
    await redis.expire(breach_key, 365 * 24 * 3600)
    
    # Auto-ban after 3 breaches
    breach_count = len(await redis.keys(f"breach:{request.device_id}:*"))
    if breach_count >= 3:
        await redis.setex(f"ban:{request.device_id}", 90 * 24 * 3600, "1")
        return {"message": "Breach reported", "action": "device_banned"}
    
    return {"message": "Breach reported", "count": breach_count}


@router.post("/device/{device_id}/ban")
async def ban_device(device_id: str, admin_token: str = Header(..., alias="X-Admin-Token")):
    """Ban a device (admin only)"""
    if admin_token != ADMIN_TOKEN:
        raise HTTPException(status_code=403, detail="Unauthorized")
    
    redis = await get_redis()
    await redis.setex(f"ban:{device_id}", 90 * 24 * 3600, "1")
    
    return {"message": f"Device {device_id} banned"}


@router.post("/device/{device_id}/unban")
async def unban_device(device_id: str, admin_token: str = Header(..., alias="X-Admin-Token")):
    """Unban a device (admin only)"""
    if admin_token != ADMIN_TOKEN:
        raise HTTPException(status_code=403, detail="Unauthorized")
    
    redis = await get_redis()
    await redis.delete(f"ban:{device_id}")
    
    return {"message": f"Device {device_id} unbanned"}


@router.post("/{device_id}/link")
async def create_link(device_id: str):
    """
    Create a short-lived link code for QR-based web companion pairing.
    The TV app displays the QR code; the user scans it on their phone
    to add playlists or upgrade to premium.
    """
    redis = await get_redis()

    if await redis.exists(f"ban:{device_id}"):
        raise HTTPException(status_code=403, detail="Device banned")

    # Generate a 6-char alphanumeric code
    code = secrets.token_hex(3).upper()  # e.g. "A1B2C3"

    # Store code → device_id mapping (expires in 10 min)
    await redis.setex(f"link:{code}", 600, device_id)

    # Also store the current active code for this device so /inbox can find it
    await redis.setex(f"device_link:{device_id}", 600, code)

    web_base = os.getenv("WEB_BASE_URL", "https://salliptv.com")
    url = f"{web_base}/link?code={code}"

    return {"url": url, "code": code, "expires_in": 600}


@router.get("/{device_id}/inbox")
async def get_inbox(device_id: str):
    """
    Poll for messages sent to this device from the web companion.

    Returns status:
      - "empty"    → nothing pending
      - "upgrade"  → premium was activated via web
      - "playlist" → a playlist was submitted via web, data contains playlist info
    """
    redis = await get_redis()

    if await redis.exists(f"ban:{device_id}"):
        raise HTTPException(status_code=403, detail="Device banned")

    # Check for pending inbox message (set by the web companion)
    inbox_key = f"inbox:{device_id}"
    raw = await redis.get(inbox_key)

    if not raw:
        return {"status": "empty", "data": None}

    message = json.loads(raw)

    # Consume the message (one-shot delivery)
    await redis.delete(inbox_key)

    return {"status": message.get("type", "empty"), "data": message.get("data")}


async def validate_activation_code(code: str, redis) -> bool:
    """Validate activation code - replace with your logic"""
    # Simple check: code must exist and not be used
    code_exists = await redis.exists(f"code:{code}")
    if not code_exists:
        return False

    used = await redis.exists(f"code:{code}:used")
    return not used
