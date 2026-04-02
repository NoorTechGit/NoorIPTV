package com.salliptv.player

import android.app.Application
import android.util.Log
import com.salliptv.player.security.NativeSec

/**
 * Application class pour SalliPTV
 * 
 * Initialise les couches de sécurité au démarrage
 */
class SalliApplication : Application() {
    
    companion object {
        private const val TAG = "SalliApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "🚀 SalliPTV démarrage...")
        
        // Initialiser la sécurité native
        initializeSecurity()
        
        // Vérification de sécurité au démarrage
        performStartupSecurityCheck()
    }
    
    /**
     * Initialise les mécanismes de sécurité
     */
    private fun initializeSecurity() {
        try {
            // Démarrer le watcher anti-debug
            NativeSec.startDebugWatcher()
            Log.d(TAG, "🔒 Watcher anti-debug démarré")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur initialisation sécurité: ${e.message}")
        }
    }
    
    /**
     * Vérification de sécurité au démarrage
     */
    private fun performStartupSecurityCheck() {
        Thread {
            try {
                val status = NativeSec.performSecurityCheck()
                
                if (!status.isSecure) {
                    Log.w(TAG, "⚠️ Problèmes de sécurité détectés: ${status.issues}")
                    
                    if (status.hasCriticalIssue()) {
                        Log.e(TAG, "🚫 Problème critique détecté - Arrêt")
                        // En production: arrêter l'app ou demander désinstallation
                        // System.exit(0)
                    }
                } else {
                    Log.d(TAG, "✅ Vérification sécurité OK")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Erreur vérification sécurité: ${e.message}")
            }
        }.start()
    }
    
    override fun onTerminate() {
        super.onTerminate()
        
        // Arrêter le watcher proprement
        try {
            NativeSec.stopDebugWatcher()
        } catch (e: Exception) {
            // Ignorer
        }
    }
}