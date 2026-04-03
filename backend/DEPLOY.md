# 🚀 Guide de Déploiement SallIPTV Backend

## 📋 Prérequis Serveur

- **IP**: `45.159.222.88` (Contabo)
- **RAM**: 12GB
- **Docker**: Déjà installé ✅
- **Traefik**: Déjà installé et configuré ✅
- **Réseau Traefik**: `traefik-public` (externe)

## 🗂️ Structure du Projet

```
backend/
├── docker-compose.yml       # Orchestration containers
├── Dockerfile               # Image API FastAPI
├── Dockerfile.worker        # Image Celery Workers
├── requirements.txt         # Dépendances Python
├── .env.example            # Template variables
├── .dockerignore           
├── DEPLOY.md               # Ce fichier
├── app/                    # Code FastAPI
│   ├── main.py            # Entry point
│   └── ...
├── workers/               # Celery tasks
├── models/                # SQLAlchemy models
├── services/              # Business logic
└── deploy/                # Configs infra
    ├── postgres/
    │   ├── init.sql
    │   └── postgresql.conf
    └── redis/
        └── redis.conf
```

## 🔧 Préparation (Local)

### 1. Créer le fichier .env

```bash
cd backend/
cp .env.example .env
nano .env  # Éditer avec les vraies valeurs
```

**Valeurs requises:**
- `POSTGRES_PASSWORD`: Mot de passe fort (>20 caractères)
- `JWT_SECRET`: Clé aléatoire 64 caractères (`openssl rand -hex 32`)
- `ENCRYPTION_KEY`: Clé 32 bytes hex (`openssl rand -hex 32`)
- `TMDB_API_KEY`: Clé API TMDB (gratuit sur themoviedb.org)

### 2. Vérifier les configurations

- `docker-compose.yml`: OK (utilise réseau `traefik-public` existant)
- `deploy/postgres/postgresql.conf`: Optimisé 12GB
- `deploy/redis/redis.conf`: Limite 2GB mémoire

## 🚀 Déploiement (Sur Serveur)

### Méthode 1: SCP (Manuel)

```bash
# 1. Depuis ton Mac, copier le dossier backend/
scp -r backend/ root@45.159.222.88:/opt/salliptv/

# 2. SSH sur serveur
ssh root@45.159.222.88

# 3. Créer .env sur serveur
cd /opt/salliptv/backend
nano .env  # Coller les valeurs

# 4. Lancer
 docker compose up -d
```

### Méthode 2: Git (Recommandé)

```bash
# Sur le serveur
cd /opt
rm -rf salliptv  # Si existe
git clone <repo-url> salliptv
cd salliptv/backend

# Créer .env
cp .env.example .env
nano .env

# Lancer
docker compose up -d
```

## ✅ Vérification Post-Déploiement

```bash
# 1. Vérifier containers
docker ps | grep salliptv

# 2. Vérifier logs API
docker logs -f salliptv-api

# 3. Test health endpoint
curl https://salliptv.com/api/health
# Attendu: {"status": "healthy", ...}

# 4. Test root
curl https://salliptv.com/api/
# Attendu: {"service": "SallIPTV API", ...}

# 5. Vérifier Traefik labels
docker inspect salliptv-api | grep traefik
```

## 🔍 Commandes Utiles

```bash
# Logs
docker compose logs -f api
docker compose logs -f worker-parsing
docker compose logs -f worker-ml

# Restart
docker compose restart api

# Scale workers
docker compose up -d --scale salliptv-worker-parsing=2

# Maintenance DB
docker exec -it salliptv-db psql -U iptv -d iptv

# Redis CLI
docker exec -it salliptv-redis redis-cli

# Stats ressources
docker stats
```

## 🔄 Mise à jour (Zero-downtime)

```bash
cd /opt/salliptv/backend
git pull  # ou copie nouvelle version
docker compose build --no-cache
docker compose up -d
```

## ⚠️ Avant de déployer - CHECKLIST

- [ ] `.env` créé avec vraies valeurs
- [ ] `TMDB_API_KEY` obtenue (themoviedb.org)
- [ ] Domaine DNS pointé vers `45.159.222.88`
- [ ] Certificat SSL (Traefik gère LetsEncrypt automatiquement)
- [ ] Port 443 ouvert sur firewall
- [ ] Backup de la DB prévu (pas encore implémenté)

## 📊 Monitoring

- **API Health**: `https://salliptv.com/api/health`
- **API Docs**: `https://salliptv.com/api/docs` (si activé en prod)
- **Traefik Dashboard**: (si activé) `https://traefik.yourdomain.com`
- **Flower** (optionnel): `docker compose --profile monitoring up -d`

## 🆘 Troubleshooting

**Container restart loop:**
```bash
docker logs salliptv-api  # Voir l'erreur
```

**DB connection failed:**
```bash
docker compose ps  # Vérifier salliptv-db est healthy
docker network inspect salliptv-internal  # Vérifier network
```

**Traefik ne route pas:**
```bash
docker network connect traefik-public salliptv-api  # Si pas connecté
docker inspect traefik | grep -A5 "traefik-public"
```

## 🔒 Sécurité

- **Jamais** commiter `.env`
- **Jamais** exposer PostgreSQL/Redis sur internet (réseau interne uniquement)
- **Toujours** utiliser HTTPS (Traefik + LetsEncrypt)
- **Secrets** changés en production vs dev

---
**Date création**: 2024
**Serveur**: 45.159.222.88 (Contabo)
**Status**: 🟡 PRÊT POUR DÉPLOIEMENT (en attente validation)
