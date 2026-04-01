# SallIPTV - IPTV Player pour Smart TV

## Qu'est-ce que ce projet ?

Player IPTV style TiViMate, conçu pour Android TV (et plus tard Tizen/webOS/Vidaa). L'app ne fournit aucun contenu — l'utilisateur entre ses propres playlists M3U ou identifiants Xtream Codes.

## Architecture Android

```
app/src/main/java/com/salliptv/player/
├── MainActivity.java        # UI principale : sidebar catégories + grille chaînes
├── PlayerActivity.java      # Lecteur plein écran ExoPlayer, overlay chaîne
├── SettingsActivity.java    # Gestion playlists (M3U / Xtream Codes)
├── model/
│   ├── Playlist.java        # Entity Room : playlist M3U ou Xtream
│   ├── Channel.java         # Entity Room : chaîne avec logo, groupe, URL
│   ├── Category.java        # Entity Room : catégorie (live/VOD/séries)
│   └── EpgProgram.java      # Programme EPG (titre, horaires)
├── data/
│   ├── AppDatabase.java     # Room Database
│   ├── ChannelDao.java      # DAO chaînes (favoris, recherche, par groupe)
│   └── PlaylistDao.java     # DAO playlists
├── parser/
│   ├── M3uParser.java       # Parser M3U/M3U8 (streaming, gros fichiers)
│   └── XtreamApi.java       # Client Xtream Codes API (OkHttp)
└── adapter/
    ├── ChannelAdapter.java   # RecyclerView grille chaînes (Glide logos)
    └── CategoryAdapter.java  # RecyclerView sidebar catégories
```

## Identifiants

- **namespace** (Java package) : `com.salliptv.player`
- **applicationId** (Play Store) : `com.salliptv.app`

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Stack technique

| Composant | Librairie |
|-----------|-----------|
| Video | Media3 ExoPlayer (HLS, MPEG-TS, RTSP) |
| HTTP | OkHttp3 |
| JSON | Gson |
| Images | Glide |
| DB locale | Room |
| UI TV | Leanback + custom layouts |

## Xtream Codes API

Base URL: `http://{server}:{port}`

| Endpoint | Action |
|----------|--------|
| `/player_api.php?username=X&password=X` | Login + info serveur |
| `&action=get_live_categories` | Catégories live |
| `&action=get_vod_categories` | Catégories VOD |
| `&action=get_series_categories` | Catégories séries |
| `&action=get_live_streams&category_id=X` | Chaînes live |
| `&action=get_vod_streams&category_id=X` | Films VOD |
| `&action=get_series&category_id=X` | Séries |
| `&action=get_short_epg&stream_id=X` | EPG court |

Stream URLs:
- Live: `http://{server}:{port}/{user}/{pass}/{stream_id}.m3u8`
- VOD: `http://{server}:{port}/movie/{user}/{pass}/{stream_id}.mp4`
- Series: `http://{server}:{port}/series/{user}/{pass}/{stream_id}.mp4`

## Format M3U

```
#EXTM3U url-tvg="http://epg.example.com/epg.xml"
#EXTINF:-1 tvg-id="ch1" tvg-name="Channel 1" tvg-logo="http://logo.png" group-title="News",Channel 1 HD
http://stream.example.com/live/stream1.ts
```

Attributs clés : `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `tvg-chno`

## UI / Navigation TV

- **D-pad** : toute la navigation au clavier/télécommande
- **Sidebar gauche** : catégories/groupes
- **Grille droite** : chaînes avec logos (3 colonnes)
- **Player** : overlay info chaîne, channel up/down, numéros directs
- **Thème** : fond sombre (#1a1a2e), accent rouge (#e94560)

## Features à implémenter

### MVP (v1.0)
- [x] Parser M3U
- [x] Client Xtream Codes API
- [x] Lecteur vidéo ExoPlayer (HLS/TS)
- [x] UI TV avec navigation D-pad
- [x] Catégories + grille chaînes
- [x] Favoris
- [x] Recherche

### v1.1
- [ ] EPG grille (guide TV multi-jours)
- [ ] Catch-up / replay
- [ ] VOD + Séries browsing amélioré
- [ ] Multi-playlist (fusionner plusieurs sources)
- [ ] Historique récent

### v2.0
- [ ] Enregistrement live TV
- [ ] Picture-in-Picture (PiP)
- [ ] Thèmes personnalisables
- [ ] Contrôle parental (PIN)
- [ ] Cloud sync favoris/settings
- [ ] Port vers Tizen (Samsung) / webOS (LG) / Vidaa (Hisense)

## Modèle commercial

- **Free** : 1 playlist, streaming basique
- **Pro** (4.99€/an ou 9.99€ one-time) : multi-playlist, EPG, enregistrement, 0 pub

## Pièges connus

1. **Xtream Codes API inconsistante** : doublons de clés JSON, dates parfois string/timestamp, arrays retournés comme objets. Parser défensivement.
2. **Gros playlists** : certains providers ont 10K+ chaînes. Parser en streaming, lazy loading obligatoire.
3. **DRM** : ExoPlayer supporte Widevine L1/L3 mais les flux IPTV sont rarement DRM.
4. **Fire TV** : tester sur Fire TV Stick (low RAM). Optimiser Glide (cache, thumbnails).
5. **Stores TV** : bien formuler la description ("lecteur multimédia pour vos abonnements légaux").
