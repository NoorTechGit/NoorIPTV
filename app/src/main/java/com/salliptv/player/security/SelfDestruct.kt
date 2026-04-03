package com.salliptv.player.security

import android.content.Context
import android.util.Log
import com.salliptv.player.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * SelfDestruct - Mécanisme d'auto-destruction de l'app
 * 
 * Déclenché si:
 * - Root détecté
 * - Debugger attaché  
 * - APK modifiée/tampered
 * - Frida/Xposed détecté
 * - Tentative de reverse engineering
 * 
 * Actions:
 * 1. Efface les tokens (rend l'app inutilisable)
 * 2. Corrompt la base de données
 * 3. Report au serveur (ban device)
 * 4. Crash brutal (pas de message explicite)
 */
object SelfDestruct {

    private const val TAG = "SelfDestruct"

    enum class Trigger {
        ROOT_DETECTED,
        DEBUGGER_DETECTED,
        APK_TAMPERED,
        FRIDA_DETECTED,
        EMULATOR_DETECTED,
        INTEGRITY_FAILED,
        TOKEN_MANIPULATION,
        UNKNOWN
    }

    /**
     * Déclenche l'auto-destruction
     * 
     * @param context Context Android
     * @param trigger Raison du déclenchement
     * @param silent Si true, destruction silencieuse (recommandé)
     */
    fun trigger(context: Context, trigger: Trigger, silent: Boolean = true) {
        Log.w(TAG, "☠️ SELF-DESTRUCT TRIGGERED: $trigger")

        // Exécuter sur thread principal pour rapidité
        GlobalScope.launch(Dispatchers.Main) {
            try {
                // 1. Effacer les tokens (immédiatement)
                clearTokens(context)

                // 2. Corrompre la DB
                corruptDatabase(context)

                // 3. Effacer les préférences
                corruptPreferences(context)

                // 4. Report au serveur (async, ne pas bloquer)
                reportBreach(context, trigger)

                // 5. Crash ou comportement étrange
                if (silent) {
                    silentCrash()
                } else {
                    obviousCrash()
                }

            } catch (e: Exception) {
                // Même si ça fail, on crash
                Log.e(TAG, "Self-destruct error", e)
                System.exit(0)
            }
        }
    }

    /**
     * Efface tous les tokens d'authentification
     */
    private fun clearTokens(context: Context) {
        try {
            val tokenManager = TokenManager(context)
            tokenManager.invalidateToken()

            // Effacer aussi les prefs PremiumManager
            context.getSharedPreferences("app_analytics", Context.MODE_PRIVATE).edit().clear().apply()

            Log.d(TAG, "Tokens cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear tokens", e)
        }
    }

    /**
     * Corrompt la base de données locale
     */
    private fun corruptDatabase(context: Context) {
        try {
            // Fermer la DB
            AppDatabase.getInstance(context).close()

            // Corrompre le fichier
            val dbFile = context.getDatabasePath("salliptv.db")
            if (dbFile.exists()) {
                // Écrire des données aléatoires au début
                dbFile.writeBytes("CORRUPTED".toByteArray())
                Log.d(TAG, "Database corrupted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to corrupt DB", e)
        }
    }

    /**
     * Corrompt les préférences sensibles
     */
    private fun corruptPreferences(context: Context) {
        try {
            // Lister et corrompre toutes les prefs de l'app
            val prefsDir = File(context.filesDir.parent, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                prefsDir.listFiles()?.forEach { file ->
                    if (file.name.contains("salliptv", ignoreCase = true)) {
                        try {
                            file.writeText("<!-- CORRUPTED -->")
                        } catch (e: Exception) {
                            // Ignorer
                        }
                    }
                }
            }
            Log.d(TAG, "Preferences corrupted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to corrupt prefs", e)
        }
    }

    /**
     * Report la violation au serveur (pour blacklist)
     */
    private fun reportBreach(context: Context, trigger: Trigger) {
        try {
            val deviceId = HardwareId.getDeviceId(context)
            val fingerprint = HardwareId.getFullFingerprint(context)

            // TODO: Envoyer au serveur
            // En async, ne pas bloquer le crash

            Log.d(TAG, "Breach reported: device=$deviceId, trigger=$trigger")
        } catch (e: Exception) {
            // Ignorer si fail
        }
    }

    /**
     * Crash silencieux (comportement "buggy")
     * L'utilisateur pense juste que l'app plante
     */
    private fun silentCrash() {
        // Lancer une exception native (plus difficile à catcher)
        NativeBridge.triggerNativeCrash()
    }

    /**
     * Crash évident (force close)
     */
    private fun obviousCrash() {
        throw SecurityException("Application integrity check failed")
    }

    /**
     * Vérifie si l'app a déjà été self-destructed
     * (pour éviter les boucles)
     */
    fun isAlreadyDestructed(context: Context): Boolean {
        return try {
            val tokenManager = TokenManager(context)
            !tokenManager.hasValidToken() && !context.getDatabasePath("salliptv.db").exists()
        } catch (e: Exception) {
            true
        }
    }
}

/**
 * Interface JNI pour les opérations natives
 */
object NativeBridge {
    
    init {
        try {
            System.loadLibrary("salliptv_secure")
        } catch (e: UnsatisfiedLinkError) {
            // Lib native pas encore compilée (développement)
            Log.w("NativeBridge", "Native library not loaded")
        }
    }

    /**
     * Déclenche un crash natif (SIGSEGV)
     */
    external fun triggerNativeCrash()
    
    /**
     * Vérifie si un debugger est attaché (native)
     */
    external fun isDebuggerAttachedNative(): Boolean
    
    /**
     * Vérifie si le device est rooté (native)
     */
    external fun isDeviceRootedNative(): Boolean
    
    /**
     * Vérifie la signature APK (native)
     */
    external fun verifyApkSignatureNative(): Boolean
}
