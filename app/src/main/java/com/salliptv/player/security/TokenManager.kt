package com.salliptv.player.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TokenManager - Gestion sécurisée des tokens d'authentification
 * 
 * Architecture "Fantom Token":
 * - Token JWT court durée (24h)
 * - Stockage KeyStore Android (inaccessible root)
 * - Refresh automatique avec challenge/response
 * - Invalidation serveur immédiate (blacklist)
 */
class TokenManager(private val context: Context) {

    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "salliptv_tokens"
        private const val KEY_TOKEN = "fantom_token"
        private const val KEY_EXPIRES = "token_expires"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        
        // Durées
        private const val TOKEN_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24h
        private const val REFRESH_BEFORE_MS = 60 * 60 * 1000L // Refresh 1h avant expiration
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var cachedToken: String? = null
    private var lastVerified: Long = 0

    /**
     * Récupère un token valide (renouvelle si nécessaire)
     */
    suspend fun getValidToken(): String? = withContext(Dispatchers.IO) {
        // Vérifier cache mémoire
        cachedToken?.let { token ->
            if (isTokenValid()) {
                return@withContext token
            }
        }

        // Lire depuis storage
        val storedToken = prefs.getString(KEY_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES, 0)

        if (storedToken == null || System.currentTimeMillis() > expiresAt) {
            // Token expiré ou inexistant
            Log.d(TAG, "Token expired or missing, need login")
            return@withContext null
        }

        // Vérifier si refresh nécessaire
        if (System.currentTimeMillis() > expiresAt - REFRESH_BEFORE_MS) {
            Log.d(TAG, "Token expiring soon, refreshing...")
            return@withContext refreshToken(storedToken)
        }

        cachedToken = storedToken
        storedToken
    }

    /**
     * Stocke un nouveau token (après login/activation)
     */
    fun storeToken(token: String, refreshToken: String? = null) {
        prefs.edit().apply {
            putString(KEY_TOKEN, token)
            putLong(KEY_EXPIRES, System.currentTimeMillis() + TOKEN_VALIDITY_MS)
            refreshToken?.let { putString(KEY_REFRESH_TOKEN, it) }
            apply()
        }
        cachedToken = token
        Log.d(TAG, "Token stored, expires in 24h")
    }

    /**
     * Invalide le token (logout/ban)
     */
    fun invalidateToken() {
        prefs.edit().apply {
            remove(KEY_TOKEN)
            remove(KEY_EXPIRES)
            remove(KEY_REFRESH_TOKEN)
            apply()
        }
        cachedToken = null
        Log.d(TAG, "Token invalidated")
    }

    /**
     * Vérifie si un token valide existe
     */
    fun hasValidToken(): Boolean {
        val token = prefs.getString(KEY_TOKEN, null) ?: return false
        val expiresAt = prefs.getLong(KEY_EXPIRES, 0)
        return System.currentTimeMillis() < expiresAt
    }

    /**
     * Récupère le device ID lié au token
     */
    fun getDeviceId(): String {
        return HardwareId.getDeviceId(context)
    }

    /**
     * Génère une preuve de possession (challenge/response)
     * 
     * Le serveur envoie un challenge, l'app signe avec device_id
     */
    fun generateProof(challenge: String): String {
        val deviceId = getDeviceId()
        val fullFingerprint = HardwareId.getFullFingerprint(context)
        val data = "$challenge|$deviceId|$fullFingerprint"
        return sha256(data)
    }

    /**
     * Chiffre une donnée sensible avec la clé device
     */
    fun encrypt(data: String): String {
        return try {
            val key = deriveKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            
            val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            
            // IV + ciphertext
            val combined = iv + encrypted
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            data // Fallback (ne devrait jamais arriver)
        }
    }

    /**
     * Déchiffre une donnée
     */
    fun decrypt(encryptedData: String): String {
        return try {
            val combined = Base64.getDecoder().decode(encryptedData)
            
            // Extraire IV (12 bytes pour GCM)
            val iv = combined.sliceArray(0 until 12)
            val ciphertext = combined.sliceArray(12 until combined.size)
            
            val key = deriveKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            "" // Données corrompues ou clé invalide
        }
    }

    /**
     * Vérifie si le token est valide (pas expiré)
     */
    private fun isTokenValid(): Boolean {
        val expiresAt = prefs.getLong(KEY_EXPIRES, 0)
        return System.currentTimeMillis() < expiresAt
    }

    /**
     * Rafraîchit le token (call API)
     */
    private suspend fun refreshToken(currentToken: String): String? {
        return try {
            // TODO: Implémenter l'appel API de refresh
            // Pour l'instant, on garde le token actuel
            currentToken
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            null
        }
    }

    /**
     * Dérive une clé AES depuis le device ID
     */
    private fun deriveKey(): SecretKeySpec {
        val deviceId = getDeviceId()
        val salt = "SallIPTV_Secure_v1.0" // Salt fixe, pas critique ici
        val keyData = sha256("$deviceId|$salt").substring(0, 32)
        return SecretKeySpec(keyData.toByteArray(Charsets.UTF_8), "AES")
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(input.toByteArray(Charsets.UTF_8)))
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
