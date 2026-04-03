# Guide d'intégration Android - Backend API

## 📱 Changements requis dans l'app

### 1. Ajouter les dépendances Retrofit (dans build.gradle)

```gradle
dependencies {
    // Retrofit
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
    implementation 'com.squareup.okhttp3:logging-interceptor:4.12.0'
}
```

### 2. Modifier le parsing des playlists

**Avant (local parsing - lent) :**
```kotlin
// Dans PlaylistManager.kt ou MainActivity.kt
suspend fun parsePlaylist(content: String, type: String) {
    // Parsing local bloquant - 30-60s
    val channels = parseM3ULocal(content)
    saveToDatabase(channels)
}
```

**Après (API parsing - rapide) :**
```kotlin
// Utiliser PlaylistRepository
class PlaylistManager(
    private val context: Context,
    private val channelDao: ChannelDao
) {
    private val repository = PlaylistRepository(context, channelDao)

    suspend fun parsePlaylist(content: ByteArray, type: String, playlistId: Long) {
        // 1. Vérifier si backend disponible
        val isBackendAvailable = repository.checkBackendHealth()
        
        if (isBackendAvailable) {
            // 2. Utiliser API (rapide - 2-3s)
            repository.processPlaylist(content, type, playlistId)
                .onSuccess {
                    Log.d("Playlist", "Processing completed!")
                }
                .onFailure { error ->
                    Log.e("Playlist", "Error: ${error.message}")
                    // Fallback: parsing local
                    parseLocally(content, type, playlistId)
                }
        } else {
            // Fallback: parsing local si backend down
            parseLocally(content, type, playlistId)
        }
    }
}
```

### 3. Modifier la lecture des streams

**Avant (URL en clair) :**
```kotlin
// Dans PlayerActivity.kt
player.setMediaItem(MediaItem.fromUri(channel.url))
```

**Après (URL chiffrée + décryptage) :**
```kotlin
// Dans PlayerActivity.kt
import com.salliptv.player.security.SecureBridge

// Décrypter l'URL
val realUrl = SecureBridge.decryptStreamUrl(
    channel.url, // Cette URL est maintenant chiffrée
    SecureBridge.getDeviceId(context)
)

player.setMediaItem(MediaItem.fromUri(realUrl))
```

### 4. Modifier le Channel entity (Room)

**Avant :**
```kotlin
@Entity
data class Channel(
    val id: Long,
    val name: String,
    val url: String, // URL en clair
    val logo: String?
)
```

**Après :**
```kotlin
@Entity
data class Channel(
    val id: Long,
    val name: String,
    val url: String, // URL CHIFFRÉE (cryptée par le serveur)
    val logo: String?,
    val tmdbId: Int? = null, // Pour les posters TMDB
    val category: String? = null
)
```

### 5. Migration de la base de données

```kotlin
// Dans AppDatabase.kt
@Database(entities = [Channel::class, ...], version = 2) // Incrémenter version
abstract class AppDatabase : RoomDatabase() {
    // ...
}

// Migration
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE channels ADD COLUMN tmdbId INTEGER")
        database.execSQL("ALTER TABLE channels ADD COLUMN category TEXT")
    }
}
```

## 🔄 Flow complet

```
1. User ajoute playlist URL dans l'app
   ↓
2. App télécharge le fichier M3U (15MB)
   ↓
3. App compresse en gzip → POST /api/playlist/upload
   ↓
4. Serveur parse (2-3s) + chiffre les URLs
   ↓
5. App poll /api/playlist/status/{job_id}
   ↓
6. App reçoit JSON avec URLs chiffrées
   ↓
7. App sauvegarde dans Room DB
   ↓
8. User clique sur chaîne
   ↓
9. App décrypte URL via JNI (SecureBridge)
   ↓
10. ExoPlayer lit le stream (direct fournisseur)
```

## 🚀 Test rapide

```kotlin
// Dans MainActivity.kt ou un test
lifecycleScope.launch {
    val repository = PlaylistRepository(context, channelDao)
    
    // Test 1: Vérifier backend
    val isUp = repository.checkBackendHealth()
    Log.d("Test", "Backend available: $isUp")
    
    // Test 2: Upload test
    val testContent = "#EXTM3U\n#EXTINF:-1,Test Channel\nhttp://test.com/stream.ts".toByteArray()
    val result = repository.uploadPlaylist(testContent, "m3u")
    Log.d("Test", "Upload result: $result")
}
```

## ⚠️ Points d'attention

1. **Fallback local** : Toujours garder le parsing local comme backup si serveur down
2. **Chiffrement** : Les URLs sont chiffrées par le serveur, déchiffrées côté client
3. **JNI** : La librairie native doit être compilée pour chaque architecture (arm64, x86_64)
4. **Gzip** : Le contenu doit être compressé avant envoi pour économiser la bande passante

## 📋 Prochaines étapes

1. Copier les fichiers créés dans ton projet
2. Ajouter les dépendances Retrofit
3. Modifier `PlaylistManager` pour utiliser `PlaylistRepository`
4. Tester avec une vraie playlist M3U
5. Implémenter le JNI natif (optionnel pour MVP)
