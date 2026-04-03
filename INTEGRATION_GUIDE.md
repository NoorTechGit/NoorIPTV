# Guide d'Intégration Complète

> Comment intégrer tout le backend + sécurité dans ton app existante

---

## 🚀 1. Initialisation au démarrage (Application ou MainActivity)

```kotlin
class SallIPTVApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // === SÉCURITÉ : Vérification au démarrage ===
        val guard = SecurityGuard(this)
        if (!guard.verifyOnStartup()) {
            // L'app s'est auto-détruite (mod détecté)
            // On arrête l'initialisation
            return
        }
        
        // === TOKEN : Vérifier abonnement ===
        val tokenManager = TokenManager(this)
        if (!tokenManager.hasValidToken()) {
            // Pas de token valide → montrer écran activation
            // L'utilisateur doit entrer un code d'activation
            ActivationActivity.start(this)
        }
    }
}
```

---

## 📺 2. Ajouter une playlist (avec backend parsing rapide)

```kotlin
class AddPlaylistActivity : AppCompatActivity() {

    private lateinit var playlistManager: PlaylistManager
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_playlist)
        
        playlistManager = PlaylistManager(this, channelDao)
    }

    fun onAddPlaylistClick(url: String) {
        // Vérifier sécurité avant opération sensible
        if (!SecurityGuard(this).verifyBeforeSensitiveOperation()) {
            showError("Security check failed")
            return
        }
        
        lifecycleScope.launch {
            playlistManager.addPlaylist(url, playlistId = 1)
                .collect { state ->
                    when(state) {
                        is PlaylistManager.State.Downloading -> {
                            progressBar.progress = state.progress
                            statusText.text = "Downloading..."
                        }
                        is PlaylistManager.State.Processing -> {
                            progressBar.progress = state.progress
                            statusText.text = state.message
                        }
                        is PlaylistManager.State.Saving -> {
                            progressBar.progress = state.progress
                            statusText.text = "Saving ${state.count} channels..."
                        }
                        is PlaylistManager.State.Success -> {
                            showSuccess("${state.totalChannels} channels added!")
                            finish() // Retour à l'accueil
                        }
                        is PlaylistManager.State.Error -> {
                            showError(state.message)
                        }
                    }
                }
        }
    }
}
```

---

## 🔐 3. Écran d'activation (code d'abonnement)

```kotlin
class ActivationActivity : AppCompatActivity() {

    private val apiService: SallIPTVApiService by lazy {
        SallIPTVApiService.create()
    }

    fun onActivateClick(code: String) {
        lifecycleScope.launch {
            try {
                val deviceId = HardwareId.getDeviceId(this@ActivationActivity)
                val fingerprint = HardwareId.getFullFingerprint(this@ActivationActivity)
                
                // Appel API activation
                val response = apiService.activateDevice(
                    ActivationRequest(
                        code = code,
                        device_id = deviceId,
                        fingerprint = fingerprint
                    )
                )
                
                if (response.isSuccessful) {
                    val token = response.body()?.token
                    val refreshToken = response.body()?.refresh_token
                    
                    // Stocker token
                    TokenManager(this@ActivationActivity)
                        .storeToken(token!!, refreshToken)
                    
                    showSuccess("Activated!")
                    finish()
                } else {
                    showError("Invalid code or device banned")
                }
            } catch (e: Exception) {
                showError("Network error: ${e.message}")
            }
        }
    }
}
```

---

## ▶️ 4. Lecture d'une chaîne (vérification token)

```kotlin
class PlayerActivity : AppCompatActivity() {

    private lateinit var exoPlayer: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Vérifier token avant lecture
        if (!TokenManager(this).hasValidToken()) {
            showError("Subscription expired")
            finish()
            return
        }
        
        // Vérifier sécurité
        if (!SecurityGuard(this).verifyBeforeSensitiveOperation()) {
            showError("Security check failed")
            finish()
            return
        }
        
        // Lecture normale
        val channel = getChannelFromIntent()
        playChannel(channel)
    }
    
    private fun playChannel(channel: Channel) {
        exoPlayer.setMediaItem(
            MediaItem.fromUri(channel.streamUrl!!)
        )
        exoPlayer.prepare()
        exoPlayer.play()
    }
}
```

---

## 🛡️ 5. Résumé des classes à utiliser

| Classe | Quand l'utiliser | Fonction |
|--------|------------------|----------|
| `SecurityGuard` | Au démarrage, avant opérations sensibles | Vérifie root/debugger/tampering |
| `TokenManager` | Pour toute action protégée | Gère JWT, chiffrement local |
| `HardwareId` | Pour identifier le device | Device ID unique hardware-bound |
| `SelfDestruct` | En cas de menace détectée | Nuke l'app (tokens + DB) |
| `PlaylistManager` | Pour ajouter une playlist | Parsing rapide via backend |
| `PremiumManager` | Déjà existant | Gestion achat/abonnement |

---

## 📋 Checklist intégration

- [ ] Ajouter dépendances Gradle (Retrofit, OkHttp)
- [ ] Copier tous les fichiers `data/remote`, `repository`, `security`
- [ ] Modifier `Application.onCreate()` pour ajouter SecurityGuard
- [ ] Modifier écran ajout playlist pour utiliser `PlaylistManager`
- [ ] Créer écran activation si pas existant
- [ ] Vérifier TokenManager avant chaque lecture
- [ ] Tester avec une vraie playlist M3U
- [ ] Tester scénario "backend down" (fallback local)

---

## ⚡ Performance attendue

| Scénario | Avant | Après |
|----------|-------|-------|
| Ajout playlist 1k chaînes | 15-20s | **3-5s** |
| Ajout playlist 10k chaînes | 60-90s | **8-12s** |
| Temps démarrage app | Normal | +50ms (checks sécurité) |
| Vérification avant lecture | - | **< 10ms** |

---

## 🚨 Gestion des erreurs

### Backend down
```kotlin
// PlaylistManager gère automatiquement
// → Fallback sur M3uParser local
```

### Token expiré
```kotlin
if (!tokenManager.hasValidToken()) {
    redirectToActivation()
}
```

### Mod détecté
```kotlin
// SelfDestruct trigger automatiquement
// → App crash, tokens effacés, DB corrompue
```

### Réseau indisponible
```kotlin
// TokenManager utilise cache local 24h
// → Fonctionne offline pendant 24h max
```
