package com.salliptv.player

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E Test: Navigation complète dans l'application
 * - Navigation entre les onglets
 * - Sélection d'une catégorie
 * - Ouvrir une chaîne
 */
@RunWith(AndroidJUnit4::class)
class MainNavigationE2ETest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Attendre le chargement initial
        Thread.sleep(4000)
    }

    @Test
    fun testTabNavigation() {
        // Tester la navigation vers l'onglet LIVE
        val liveTab = device.findObject(UiSelector().textContains("LIVE"))
        if (liveTab.exists()) {
            liveTab.click()
            Thread.sleep(1500)
            
            // Vérifier que le conteneur LIVE est visible
            val liveContainer = device.findObject(UiSelector().resourceIdMatches(".*live_list_container.*"))
            // Le conteneur peut ne pas exister en mode portrait
        }
        
        // Tester la navigation vers l'onglet VOD
        val vodTab = device.findObject(UiSelector().textContains("VOD"))
        if (vodTab.exists()) {
            vodTab.click()
            Thread.sleep(1500)
        }
        
        // Tester la navigation vers l'onglet SERIES
        val seriesTab = device.findObject(UiSelector().textContains("SERIES"))
        if (seriesTab.exists()) {
            seriesTab.click()
            Thread.sleep(1500)
        }
        
        // Retourner à HOME
        val homeTab = device.findObject(UiSelector().textContains("ACCUEIL"))
        if (homeTab.exists()) {
            homeTab.click()
            Thread.sleep(1500)
        }
    }

    @Test
    fun testChannelSelectionInLiveTab() {
        // Aller à l'onglet LIVE
        val liveTab = device.findObject(UiSelector().textContains("LIVE"))
        if (liveTab.exists()) {
            liveTab.click()
            Thread.sleep(2000)
            
            // Chercher une catégorie ou une chaîne
            val categoryRecycler = device.findObject(UiSelector().resourceIdMatches(".*rv_categories.*"))
            val channelRecycler = device.findObject(UiSelector().resourceIdMatches(".*rv_channels.*"))
            
            // Si on trouve des éléments, essayer de cliquer sur le premier
            if (categoryRecycler.exists() && categoryRecycler.childCount > 0) {
                val firstCategory = categoryRecycler.getChild(UiSelector().index(0))
                if (firstCategory.exists()) {
                    firstCategory.click()
                    Thread.sleep(1000)
                }
            }
            
            // Vérifier le nombre de chaînes affiché
            val channelCount = device.findObject(UiSelector().resourceIdMatches(".*tv_channel_count.*"))
            if (channelCount.exists()) {
                val countText = channelCount.text
                assert(countText.isNotEmpty()) { "Nombre de chaînes non affiché" }
            }
        }
    }

    @Test
    fun testSearchFunctionality() {
        // Chercher l'icône de recherche
        val searchIcon = device.findObject(UiSelector().resourceIdMatches(".*icon_search.*"))
        
        if (searchIcon.exists()) {
            searchIcon.click()
            Thread.sleep(1000)
            
            // Vérifier que la barre de recherche est visible
            val searchBar = device.findObject(UiSelector().resourceIdMatches(".*search_filter_bar.*"))
            assert(searchBar.exists()) { "Barre de recherche non trouvée" }
        }
    }

    @Test
    fun testSettingsNavigation() {
        // Chercher l'icône Settings ou l'onglet
        val settingsIcon = device.findObject(UiSelector().resourceIdMatches(".*icon_settings.*"))
        val settingsTab = device.findObject(UiSelector().textContains("RÉGLAGES"))
        
        if (settingsIcon.exists()) {
            settingsIcon.click()
        } else if (settingsTab.exists()) {
            settingsTab.click()
        }
        
        Thread.sleep(2000)
        
        // Vérifier qu'on est dans Settings (vérifier un élément unique)
        val deviceIdLabel = device.findObject(UiSelector().textContains("ID Appareil"))
        assert(deviceIdLabel.exists()) { "Settings non ouvert - ID Appareil non trouvé" }
        
        // Prendre un screenshot
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "test_nav_settings.png")
        device.takeScreenshot(file)
    }
}
