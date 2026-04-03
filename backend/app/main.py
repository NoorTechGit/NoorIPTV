"""
SallIPTV Backend API
FastAPI application entry point
"""

from fastapi import FastAPI, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
import logging
import os

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Create FastAPI app
app = FastAPI(
    title="SallIPTV API",
    description="Backend intelligent pour lecteur IPTV",
    version="1.0.0",
    docs_url="/docs" if os.getenv("ENVIRONMENT") != "production" else None,
    redoc_url="/redoc" if os.getenv("ENVIRONMENT") != "production" else None,
)

# CORS (Configure selon ton domaine)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # À restreindre en production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Health check endpoint
@app.get("/health", tags=["health"])
async def health_check():
    """Health check pour Traefik et monitoring"""
    return {
        "status": "healthy",
        "service": "salliptv-api",
        "version": "1.0.0"
    }

# Root endpoint
@app.get("/", tags=["root"])
async def root():
    """Info de base"""
    return {
        "service": "SallIPTV API",
        "version": "1.0.0",
        "documentation": "/docs"
    }

# Import et inclusion des routers
# Note: /device/* endpoints (register, link, inbox) are handled by salliptv-site
from app.api import playlist
from app.api import epg

app.include_router(playlist.router, prefix="/playlist", tags=["playlist"])
app.include_router(epg.router, prefix="/epg", tags=["epg"])

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=os.getenv("ENVIRONMENT") == "development"
    )
