package com.salliptv.player

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runner personnalisé pour les tests E2E
 * Permet de configurer l'environnement de test
 */
class E2ETestRunner : AndroidJUnitRunner() {
    
    override fun onCreate(arguments: Bundle?) {
        // Désactiver les animations pour des tests plus stables
        arguments?.putString("disableAnalytics", "true")
        super.onCreate(arguments)
    }
    
    override fun finish(resultCode: Int, results: Bundle?) {
        super.finish(resultCode, results)
    }
}
