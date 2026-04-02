package com.salliptv.player.security

import android.util.Log

/**
 * Interface JNI pour les fonctions de sécurité natives
 * 
 * Couche 2: Protection native anti-tampering
 * - Anti-debugging (ptrace, TracerPid)
 * - Anti-Xposed/Frida
 * - Détection émulateur
 * - Chiffrement AES-256-GCM
 * - HMAC-SHA256
 * - Validation temps (anti-replay)
 */
object NativeSec {
    
    private const val TAG = "SallSec"
    
    // Chargement de la librairie native
    init {
        try {
            System.loadLibrary("native-lib")
            Log.d(TAG, "✅ Bibliothèque native chargée")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Erreur chargement lib native: ${e.message}")
        }
    }
    
    // ============================================================================
    // ANTI-DEBUGGING
    // ============================================================================
    
    /**
     * Vérifie si un débogueur est attaché via ptrace
     */
    external fun checkPtrace(): Boolean
    
    /**
     * Détecte les breakpoints logiciels
     */
    external fun checkBreakpoints(): Boolean
    
    /**
     * Détecte si on tourne dans un émulateur
     */
    external fun isEmulator(): Boolean
    
    /**
     * Démarre le thread de surveillance anti-debug
     */
    external fun startDebugWatcher()
    
    /**
     * Arrête le thread de surveillance
     */
    external fun stopDebugWatcher()
    
    // ============================================================================
    // ANTI-XPOSED/ANTI-ROOT
    // ============================================================================
    
    /**
     * Détecte Xposed Framework
     */
    external fun isXposedDetected(): Boolean
    
    /**
     * Détection root approfondie
     */
    external fun isRootedDeep(): Boolean
    
    // ============================================================================
    // CHIFFREMENT
    // ============================================================================
    
    /**
     * Chiffre avec AES-256-GCM
     * @return IV(12) + Ciphertext + Tag(16)
     */
    external fun encryptAesGcm(plaintext: ByteArray): ByteArray
    
    /**
     * Déchiffre avec AES-256-GCM
     */
    external fun decryptAesGcm(encrypted: ByteArray): ByteArray?
    
    /**
     * Calcule HMAC-SHA256
     */
    external fun hmacSha256(data: ByteArray): ByteArray
    
    // ============================================================================
    // VALIDATION TEMPS (Anti-replay)
    // ============================================================================
    
    /**
     * Définit le décalage temps serveur
     */
    external fun setServerTimeOffset(offsetMs: Long)
    
    /**
     * Récupère le timestamp sécurisé (corrigé serveur)
     */
    external fun getSecureTimestamp(): Long
    
    /**
     * Vérifie si un timestamp est valide (anti-replay)
     */
    external fun isTimestampValid(timestamp: Long, windowMs: Long): Boolean
    
    // ============================================================================
    // UTILITAIRES
    // ============================================================================
    
    /**
     * Vérification complète de sécurité
     */
    fun performSecurityCheck(): SecurityStatus {
        val issues = mutableListOf<String>()
        
        // Anti-debug
        if (checkPtrace()) {
            issues.add("DEBUGGER_ATTACHED")
        }
        
        if (checkBreakpoints()) {
            issues.add("BREAKPOINTS_DETECTED")
        }
        
        // Émulateur
        if (isEmulator()) {
            issues.add("EMULATOR_DETECTED")
        }
        
        // Xposed
        if (isXposedDetected()) {
            issues.add("XPOSED_DETECTED")
        }
        
        // Root
        if (isRootedDeep()) {
            issues.add("ROOT_DETECTED")
        }
        
        return SecurityStatus(
            isSecure = issues.isEmpty(),
            issues = issues,
            timestamp = getSecureTimestamp()
        )
    }
    
    data class SecurityStatus(
        val isSecure: Boolean,
        val issues: List<String>,
        val timestamp: Long
    ) {
        fun hasCriticalIssue(): Boolean {
            return issues.any { it in listOf("DEBUGGER_ATTACHED", "XPOSED_DETECTED") }
        }
    }
    
    /**
     * Chiffre une chaîne Base64
     */
    fun encryptString(plaintext: String): String {
        val encrypted = encryptAesGcm(plaintext.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }
    
    /**
     * Déchiffre une chaîne Base64
     */
    fun decryptString(encryptedBase64: String): String? {
        return try {
            val encrypted = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
            val decrypted = decryptAesGcm(encrypted)
            decrypted?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}