package com.salliptv.player.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Génère un identifiant matériel unique et inviolable pour ce device
 * 
 * Le device ID est utilisé pour:
 * - Lier l'abonnement à ce device spécifique
 * - Chiffrer/déchiffrer les données sensibles
 * - Empêcher le partage de compte
 */
object HardwareId {

    private const val PREFS_NAME = "salliptv_secure"
    private const val KEY_DEVICE_ID = "device_id_v2"
    private const val KEY_INSTALL_ID = "install_id"

    /**
     * Génère un device ID unique basé sur le hardware
     * Format: XXXX-XXXX (8 chars hex) pour l'affichage
     */
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Vérifier si on a déjà généré un ID
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        
        if (deviceId == null) {
            // Générer un nouvel ID basé sur le hardware
            deviceId = generateHardwareFingerprint()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        
        // Format court pour l'affichage: XXXX-XXXX
        return deviceId.substring(0, 4).uppercase() + "-" + deviceId.substring(4, 8).uppercase()
    }

    /**
     * Génère le fingerprint complet (utilisé pour la vérification serveur)
     */
    fun getFullFingerprint(context: Context): String {
        val hardware = buildHardwareFingerprint()
        val installId = getInstallId(context)
        return sha256("$hardware|$installId")
    }

    /**
     * Génère un ID basé sur le hardware (ne change pas après factory reset)
     */
    private fun generateHardwareFingerprint(): String {
        val components = listOf(
            Build.BOARD,
            Build.BOOTLOADER,
            Build.BRAND,
            Build.DEVICE,
            Build.HARDWARE,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.PRODUCT,
            Build.SOC_MANUFACTURER,
            Build.SOC_MODEL,
            Build.SUPPORTED_ABIS?.joinToString(",") ?: "unknown"
        )
        
        val fingerprint = components.joinToString("|")
        return sha256(fingerprint).substring(0, 16)
    }

    /**
     * Construit le fingerprint hardware détaillé
     */
    private fun buildHardwareFingerprint(): String {
        return "${Build.BOARD}|${Build.BRAND}|${Build.DEVICE}|${Build.HARDWARE}|${Build.MANUFACTURER}|${Build.MODEL}|${Build.PRODUCT}"
    }

    /**
     * Génère un ID d'installation (change si l'app est réinstallée)
     */
    fun getInstallId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var installId = prefs.getString(KEY_INSTALL_ID, null)
        
        if (installId == null) {
            installId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, installId).apply()
        }
        
        return installId
    }

    /**
     * Vérifie si le device ID a été modifié/tampered
     */
    fun verifyIntegrity(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedId = prefs.getString(KEY_DEVICE_ID, null) ?: return false
        
        // Régénérer et comparer
        val currentId = generateHardwareFingerprint()
        
        // Si le hardware a changé (rare mais possible), on regénère
        if (storedId != currentId) {
            // Peut être un changement légitime (MAJ système, etc.)
            // Ou un tampering - on log et on regénère
            prefs.edit().putString(KEY_DEVICE_ID, currentId).apply()
            return false // Signaler le changement
        }
        
        return true
    }

    /**
     * Calcule SHA-256
     */
    private fun sha256(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            bytesToHex(digest)
        } catch (e: Exception) {
            input
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
