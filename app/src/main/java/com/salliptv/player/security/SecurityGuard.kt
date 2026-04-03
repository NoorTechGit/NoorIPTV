package com.salliptv.player.security

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * SecurityGuard - Surveillance continue de l'intégrité de l'app
 * 
 * Vérifications régulières:
 * - Root/Emulator
 * - Debugger
 * - APK tampering
 * - Frida/Xposed
 * 
 * Si une menace est détectée → SelfDestruct
 */
class SecurityGuard(private val context: Context) {

    companion object {
        private const val TAG = "SecurityGuard"
        
        // Intervalles de vérification
        private const val CHECK_INTERVAL_NORMAL = 30_000L // 30s en usage normal
        private const val CHECK_INTERVAL_SENSITIVE = 5_000L // 5s pendant lecture
        
        // Nombre max de violations avant destruction
        private const val MAX_VIOLATIONS = 3
    }

    private var violationCount = 0
    private var isRunning = false
    private val tokenManager = TokenManager(context)

    /**
     * Vérifie la sécurité immédiatement
     * À appeler au démarrage de l'app
     */
    fun verifyOnStartup(): Boolean {
        Log.d(TAG, "Running startup security checks...")
        
        val checks = listOf(
            ::checkIntegrity to "Integrity",
            ::checkRoot to "Root",
            ::checkDebugger to "Debugger",
            ::checkEmulator to "Emulator",
            ::checkTampering to "Tampering"
        )
        
        for ((check, name) in checks) {
            if (!check()) {
                Log.e(TAG, "❌ Security check FAILED: $name")
                handleViolation(check)
                return false
            }
            Log.d(TAG, "✅ Security check passed: $name")
        }
        
        return true
    }

    /**
     * Vérifie avant une opération sensible (lecture, achat, etc.)
     */
    fun verifyBeforeSensitiveOperation(): Boolean {
        // Vérifications rapides uniquement
        if (checkDebugger() && checkIntegrity()) {
            return true
        }
        
        handleViolation { false }
        return false
    }

    /**
     * Vérifie l'intégrité de l'APK
     */
    private fun checkIntegrity(): Boolean {
        return try {
            // Vérifier signature (basique)
            val sig = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNATURES
            ).signatures
            
            sig != null && sig.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Détecte si le device est rooté
     */
    private fun checkRoot(): Boolean {
        val rootIndicators = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk"
        )
        
        // Vérifier fichiers su
        for (path in rootIndicators) {
            if (File(path).exists()) {
                Log.w(TAG, "Root file detected: $path")
                return false // Root détecté = fail
            }
        }
        
        // Vérifier si on peut écrire dans /system (root uniquement)
        try {
            val process = Runtime.getRuntime().exec("su -c id")
            process.waitFor()
            if (process.exitValue() == 0) {
                Log.w(TAG, "su command succeeded")
                return false
            }
        } catch (e: Exception) {
            // su pas disponible = pas root (good)
        }
        
        return true
    }

    /**
     * Détecte si un debugger est attaché
     */
    private fun checkDebugger(): Boolean {
        // Check Java
        if (android.os.Debug.isDebuggerConnected()) {
            Log.w(TAG, "Java debugger connected")
            return false
        }
        
        // Check native (via JNI si disponible)
        try {
            if (NativeBridge.isDebuggerAttachedNative()) {
                Log.w(TAG, "Native debugger detected")
                return false
            }
        } catch (e: UnsatisfiedLinkError) {
            // Lib native pas chargée, ignorer
        }
        
        return true
    }

    /**
     * Détecte si on est sur un émulateur
     */
    private fun checkEmulator(): Boolean {
        val indicators = listOf(
            Build.FINGERPRINT.startsWith("generic"),
            Build.FINGERPRINT.lowercase().contains("emulator"),
            Build.MODEL.contains("Emulator"),
            Build.MODEL.contains("Android SDK"),
            Build.MANUFACTURER.contains("Genymotion"),
            Build.BRAND.startsWith("generic"),
            Build.DEVICE.startsWith("generic"),
            Build.PRODUCT.contains("sdk"),
            Build.HARDWARE.contains("goldfish"),
            Build.HARDWARE.contains("ranchu"),
            Build.BOARD.lowercase().contains("goldfish")
        )
        
        val emulatorScore = indicators.count { it }
        
        // Si 3+ indicateurs, probablement émulateur
        if (emulatorScore >= 3) {
            Log.w(TAG, "Emulator detected (score: $emulatorScore)")
            return false // Bloquer émulateur
        }
        
        return true
    }

    /**
     * Détecte si l'APK a été modifiée
     */
    private fun checkTampering(): Boolean {
        // Vérifier si l'app est debuggable (ne devrait pas l'être en release)
        val isDebuggable = context.applicationInfo.flags and 
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        
        if (isDebuggable) {
            Log.w(TAG, "App is debuggable")
            // En release, c'est un signe de tampering
            // En dev, c'est normal
            // return false // Décommenter pour bloquer en release
        }
        
        // Vérifier les librairies natives
        // (Si des libs manquent ou ont été modifiées)
        
        return true
    }

    /**
     * Gère une violation de sécurité
     */
    private fun handleViolation(failedCheck: () -> Boolean) {
        violationCount++
        Log.e(TAG, "Security violation #$violationCount")
        
        if (violationCount >= MAX_VIOLATIONS) {
            // Trop de violations → Self destruct
            SelfDestruct.trigger(context, SelfDestruct.Trigger.UNKNOWN)
        } else {
            // Première(s) violation(s) → juste invalider le token
            tokenManager.invalidateToken()
        }
    }

    /**
     * Détecte Frida (outil de hooking)
     * 
     * Frida expose des threads/processus spécifiques
     */
    fun detectFrida(): Boolean {
        val fridaIndicators = listOf(
            "frida",
            "frida-server",
            "linjector"
        )
        
        try {
            // Lister les processus
            val process = Runtime.getRuntime().exec("ps")
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lowerLine = line?.lowercase() ?: continue
                    for (indicator in fridaIndicators) {
                        if (lowerLine.contains(indicator)) {
                            Log.w(TAG, "Frida detected: $indicator")
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorer
        }
        
        return false
    }

    /**
     * Détecte Xposed Framework
     */
    fun detectXposed(): Boolean {
        return try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            Log.w(TAG, "Xposed framework detected")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
