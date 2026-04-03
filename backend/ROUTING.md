# 🌐 Routing Configuration

## URL Structure

Ton site existe déjà sur `salliptv.com`. L'API s'ajoute en sous-chemin:

```
https://salliptv.com/           → Site web existant (WordPress/Static)
https://salliptv.com/api/       → FastAPI Backend (Nouveau)
https://salliptv.com/api/health → Health check
https://salliptv.com/api/docs   → Documentation API (désactivé en prod)
```

## Comment ça marche (Traefik)

```
User ──HTTPS──► Traefik (port 443)
                      │
                      ├─ Path: /api/* ───► StripPrefix(/api)
                      │                            │
                      │                            ▼
                      │                     FastAPI reçoit: /health
                      │                     (pas /api/health)
                      │                            │
                      │                            ▼
                      │                     salliptv-api:8000
                      │
                      └─ Path: /* (autre) ───► Site web existant
```

## Configuration Traefik (Labels)

```yaml
# Dans docker-compose.yml
labels:
  # Match: salliptv.com/api/*
  - "traefik.http.routers.salliptv-api.rule=Host(`salliptv.com`) && PathPrefix(`/api`)"
  
  # Supprime /api avant d'envoyer à FastAPI
  # /api/health devient /health
  - "traefik.http.middlewares.salliptv-api-stripprefix.stripprefix.prefixes=/api"
  
  # Applique le middleware
  - "traefik.http.routers.salliptv-api.middlewares=salliptv-api-stripprefix"
```

## Avantages

✅ **Pas de sous-domaine** → Pas besoin de certificat supplémentaire  
✅ **Simple** → Un seul domaine à gérer  
✅ **SEO** → Pas de confusion pour Google (même domaine)  
✅ **CORS** → Facile (même origine)  

## Test après déploiement

```bash
# Doit marcher (site existant)
curl https://salliptv.com/

# Doit marcher (API)
curl https://salliptv.com/api/health
curl https://salliptv.com/api/
```

## ⚠️ Attention

Si ton site web actuel redirige tout vers `index.html` (SPA), il faut exclure `/api`:
```nginx
# Dans la config de ton site (nginx/apache)
location /api {
    # Ne pas rediriger, laisser Traefik gérer
    proxy_pass http://salliptv-api:8000; # Si Traefik gère pas déjà
}

location / {
    # Ton site normal
    try_files $uri $uri/ /index.html;
}
```

Mais avec Traefik devant, c'est lui qui route AVANT ton site web, donc pas de souci.
