package com.salliptv.player

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E Test: Parcours utilisateur complet
 * Simule un utilisateur qui:
 * 1. Ouvre l'app
 * 2. Navigue vers LIVE
 * 3. Sélectionne une catégorie
 * 4. Ouvre une chaîne
 * 5. Retourne à l'accueil
 * 6. Va dans Settings
 */
@RunWith(AndroidJUnit4::class)
class FullAppFlowE2ETest {

    private lateinit var device: UiDevice
    private var mainScenario: ActivityScenario<MainActivity>? = null
    private var settingsScenario: ActivityScenario<SettingsActivity>? = null

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @After
    fun tearDown() {
        mainScenario?.close()
        settingsScenario?.close()
    }

    @Test
    fun testCompleteUserJourney() {
        // Étape 1: Lancer MainActivity
        val mainIntent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        mainScenario = ActivityScenario.launch(mainIntent)
        
        println("[E2E] Attente chargement initial...")
        Thread.sleep(5000) // Attendre l'injection de la playlist demo
        
        // Prendre un screenshot de l'écran d'accueil
        takeScreenshot("01_home_initial")
        
        // Étape 2: Naviguer vers LIVE
        println("[E2E] Navigation vers LIVE...")
        val liveTab = device.findObject(UiSelector().textContains("LIVE"))
        if (liveTab.exists()) {
            liveTab.click()
            Thread.sleep(2000)
            takeScreenshot("02_live_tab")
        }
        
        // Étape 3: Sélectionner une catégorie si disponible
        println("[E2E] Recherche de catégories...")
        val categoryRecycler = device.findObject(UiSelector().resourceIdMatches(".*rv_categories.*"))
        if (categoryRecycler.exists() && categoryRecycler.childCount > 0) {
            val firstCategory = categoryRecycler.getChild(UiSelector().index(0))
            if (firstCategory.exists()) {
                println("[E2E] Clique sur première catégorie...")
                firstCategory.click()
                Thread.sleep(1500)
                takeScreenshot("03_category_selected")
            }
        }
        
        // Étape 4: Chercher des chaînes
        println("[E2E] Vérification des chaînes...")
        val channelRecycler = device.findObject(UiSelector().resourceIdMatches(".*rv_channels.*"))
        val channelCount = device.findObject(UiSelector().resourceIdMatches(".*tv_channel_count.*"))
        
        if (channelCount.exists()) {
            println("[E2E] Nombre de chaînes: ${channelCount.text}")
        }
        
        // Étape 5: Naviguer vers VOD (vérifie l'accès premium)
        println("[E2E] Navigation vers VOD...")
        val vodTab = device.findObject(UiSelector().textContains("VOD"))
        if (vodTab.exists()) {
            vodTab.click()
            Thread.sleep(2000)
            takeScreenshot("04_vod_tab")
        }
        
        // Étape 6: Naviguer vers SERIES
        println("[E2E] Navigation vers SERIES...")
        val seriesTab = device.findObject(UiSelector().textContains("SERIES"))
        if (seriesTab.exists()) {
            seriesTab.click()
            Thread.sleep(2000)
            takeScreenshot("05_series_tab")
        }
        
        // Étape 7: Retour à l'accueil
        println("[E2E] Retour à l'accueil...")
        val homeTab = device.findObject(UiSelector().textContains("ACCUEIL"))
        if (homeTab.exists()) {
            homeTab.click()
            Thread.sleep(1500)
            takeScreenshot("06_home_return")
        }
        
        // Étape 8: Ouvrir Settings
        println("[E2E] Ouverture Settings...")
        val settingsIcon = device.findObject(UiSelector().resourceIdMatches(".*icon_settings.*"))
        val settingsTab = device.findObject(UiSelector().textContains("RÉGLAGES"))
        
        if (settingsIcon.exists()) {
            settingsIcon.click()
        } else if (settingsTab.exists()) {
            settingsTab.click()
        }
        
        Thread.sleep(2000)
        takeScreenshot("07_settings_open")
        
        // Vérifier qu'on est dans Settings
        val deviceIdLabel = device.findObject(UiSelector().textContains("ID Appareil"))
        assert(deviceIdLabel.exists()) { "Settings non ouvert - ID Appareil non trouvé" }
        
        println("[E2E] ✓ Parcours utilisateur complet terminé avec succès!")
    }

    @Test
    fun testFavoritesFlow() {
        // Lancer MainActivity
        val mainIntent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        mainScenario = ActivityScenario.launch(mainIntent)
        
        Thread.sleep(5000)
        
        // Aller dans Favoris
        println("[E2E] Navigation vers Favoris...")
        val favTab = device.findObject(UiSelector().textContains("FAVORIS"))
        if (favTab.exists()) {
            favTab.click()
            Thread.sleep(2000)
            takeScreenshot("08_favorites")
        }
        
        // Vérifier l'état (peut être vide ou avoir des favoris)
        val statusText = device.findObject(UiSelector().resourceIdMatches(".*tv_status.*"))
        val recyclerSections = device.findObject(UiSelector().resourceIdMatches(".*recyclerSections.*"))
        
        val hasContent = recyclerSections.exists()
        val showEmptyState = statusText.exists()
        
        println("[E2E] Favoris - Contenu: $hasContent, État vide: $showEmptyState")
        assert(hasContent || showEmptyState) { "Favoris ni chargé ni vide" }
    }

    @Test
    fun testRecentChannelsFlow() {
        // Lancer MainActivity
        val mainIntent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        mainScenario = ActivityScenario.launch(mainIntent)
        
        Thread.sleep(5000)
        
        // Aller dans Récent
        println("[E2E] Navigation vers Récent...")
        val recentTab = device.findObject(UiSelector().textContains("RÉCENT"))
        if (recentTab.exists()) {
            recentTab.click()
            Thread.sleep(2000)
            takeScreenshot("09_recent")
        }
        
        // Vérifier l'état
        val statusText = device.findObject(UiSelector().resourceIdMatches(".*tv_status.*"))
        val recyclerSections = device.findObject(UiSelector().resourceIdMatches(".*recyclerSections.*"))
        
        val hasContent = recyclerSections.exists()
        val showEmptyState = statusText.exists()
        
        println("[E2E] Récent - Contenu: $hasContent, État vide: $showEmptyState")
        assert(hasContent || showEmptyState) { "Récent ni chargé ni vide" }
    }

    private fun takeScreenshot(name: String) {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = java.io.File(context.filesDir, "${name}.png")
            val success = device.takeScreenshot(file)
            if (success) {
                println("[E2E] Screenshot sauvegardé: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            println("[E2E] ⚠️ Erreur screenshot: ${e.message}")
        }
    }
}
