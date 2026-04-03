package com.salliptv.player

import android.app.Application
import android.content.Intent
import android.util.Log
import com.salliptv.player.security.SecurityGuard
import com.salliptv.player.security.TokenManager

/**
 * Application class pour SalliPTV
 * 
 * Initialise les couches de sécurité au démarrage
 * Architecture: Token Fantom + Self-Destruct + Backend API
 */
class SalliApplication : Application() {
    
    companion object {
        private const val TAG = "SalliApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "🚀 SalliPTV démarrage...")
        
        // === SÉCURITÉ : Vérification au démarrage ===
        val guard = SecurityGuard(this)
        if (!guard.verifyOnStartup()) {
            // L'app s'est auto-détruite (mod détecté) ou doit s'arrêter
            Log.e(TAG, "🚫 Sécurité compromise - Arrêt de l'application")
            return
        }
        Log.d(TAG, "✅ Vérification sécurité OK")
        
        // === TOKEN : Vérifier abonnement ===
        val tokenManager = TokenManager(this)
        if (!tokenManager.hasValidToken()) {
            Log.w(TAG, "⚠️ Pas de token valide - Redirection activation")
            // Rediriger vers activation au démarrage de la première activité
            // On ne le fait pas ici car Application n'a pas de context UI
        } else {
            Log.d(TAG, "✅ Token valide présent")
        }
    }
    
    /**
     * Vérifie si l'app peut démarrer (appelé par MainActivity)
     */
    fun canStartApp(): Boolean {
        val guard = SecurityGuard(this)
        return guard.verifyOnStartup()
    }
    
    /**
     * Vérifie si l'utilisateur a un abonnement actif
     */
    fun hasActiveSubscription(): Boolean {
        return TokenManager(this).hasValidToken()
    }
    
    /**
     * Récupère le device ID unique (SallIPTV)
     */
    fun getSallDeviceId(): String {
        return com.salliptv.player.security.HardwareId.getDeviceId(this)
    }
}
