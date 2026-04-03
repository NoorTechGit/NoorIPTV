# 🎯 Plan Migration SallIPTV - Backend First

> **Serveur:** `45.159.222.88` (Contabo)  
> **Architecture:** Hybride (Client télécharge → Serveur parse → Retour JSON)  
> **API URL:** `https://salliptv.com/api`

---

## ✅ CHECKLIST GLOBALE

### Phase 1: Backend Setup ✅ COMPLET
- [x] Connexion SSH au serveur
- [x] Installation Docker & Docker Compose
- [x] Setup PostgreSQL + Redis
- [x] Création structure dossiers
- [x] Test health endpoint
- [x] Intégration Traefik (salliptv.com/api)

### Phase 2: API Core ✅ COMPLET
- [x] FastAPI structure
- [x] Endpoint `/auth/register` (device_id)
- [x] Endpoint `/playlist/upload` (réception gzip)
- [x] Endpoint `/playlist/status/{job_id}` (polling)
- [x] Modèles Pydantic (Playlist, Channel, VOD)

### Phase 3: Worker Parsing ✅ COMPLET
- [x] Celery + Redis queue
- [x] Parse M3U (regex)
- [x] Parse Xtream (JSON)
- [x] Calcul fingerprint (SHA256)
- [x] Enrichissement TMDB (placeholder)
- [x] Chiffrement AES (URLs)
- [x] Destruction mémoire (sécurité)

### Phase 4: Client Android - Modification (À FAIRE)
- [ ] Ajouter Retrofit/API Service
- [ ] Modifier `PlaylistRepository` (local → API)
- [ ] Compression gzip avant envoi
- [ ] Polling status job
- [ ] Migration Room (champ `encrypted_url`)

### Phase 5: Sécurité & Lecture (À FAIRE)
- [ ] JNI C++ (decrypt AES)
- [ ] Modifier Player (url claire → decryptée)
- [ ] Certificate pinning

### Phase 6: Intelligence IA (À FAIRE)
- [ ] pgvector (embeddings)
- [ ] Worker ML (recommandations)
- [ ] Endpoint `/recommendations`
- [ ] Carousel "Pour vous" dans UI

---

## 🚀 ENDPOINTS API DISPONIBLES

### Health Check
```bash
GET https://salliptv.com/api/health
Response: {"status": "healthy", "service": "salliptv-api", "version": "1.0.0"}
```

### Upload Playlist
```bash
POST https://salliptv.com/api/playlist/upload
Headers:
  - X-Device-ID: <device_id>
  - X-Content-Type: m3u | xtream
  - Content-Type: application/octet-stream
Body: <gzip compressed playlist>

Response: {"job_id": "uuid", "status": "processing", "estimated_seconds": 5}
```

### Check Status
```bash
GET https://salliptv.com/api/playlist/status/{job_id}
Headers:
  - X-Device-ID: <device_id>

Response:
  {"job_id": "...", "status": "processing", "progress": 45}
  ou
  {"job_id": "...", "status": "completed", "result": {...}}
```

---

## 🐳 ARCHITECTURE DOCKER

```
salliptv-api          (2GB) - FastAPI + Gunicorn
salliptv-db           (3GB) - PostgreSQL + pgvector
salliptv-redis        (2GB) - Cache + Queue Celery
salliptv-worker-parsing (2GB) - Parse M3U/Xtream
salliptv-worker-ml    (2GB) - Recommandations IA
salliptv-beat         (256MB) - Scheduler Celery
```

**Total:** ~11.25GB / 12GB

---

## 📱 PROCHAINES ÉTAPES

1. **Modifier l'app Android** pour utiliser l'API
2. **Test parsing** avec une vraie playlist M3U
3. **Implémenter TMDB** enrichment
4. **Créer JNI** pour décryptage sécurisé

---

**Status:** 🟢 **BACKEND PRÊT** - Phase 1-3 complètes
