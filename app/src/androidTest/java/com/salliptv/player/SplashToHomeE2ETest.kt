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
import kotlin.concurrent.thread

/**
 * E2E Test: Vérifier que l'application démarre correctement
 * et charge la playlist demo (PorscheTV) automatiquement
 */
@RunWith(AndroidJUnit4::class)
class SplashToHomeE2ETest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun testAppLaunchAndDemoPlaylistLoading() {
        // Attendre que l'activité principale soit chargée
        Thread.sleep(3000)
        
        // Vérifier que la barre de navigation est présente
        val liveTab = device.findObject(UiSelector().textContains("LIVE"))
        assert(liveTab.exists()) { "Onglet LIVE non trouvé" }
        
        // Vérifier que les sections Home sont chargées ou que le statut est visible
        val recyclerView = device.findObject(UiSelector().resourceIdMatches(".*recyclerSections.*"))
        val statusText = device.findObject(UiSelector().resourceIdMatches(".*tv_status.*"))
        
        assert(recyclerView.exists() || statusText.exists()) { 
            "Ni les sections ni le statut ne sont visibles" 
        }
        
        // Prendre un screenshot pour vérification visuelle
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "test_launch.png")
        device.takeScreenshot(file)
    }
    
    @Test
    fun testNavigationTabsExist() {
        Thread.sleep(2000)
        
        // Vérifier tous les onglets principaux
        val tabs = listOf("LIVE", "VOD", "SERIES", "FAVORIS", "RÉCENT")
        
        for (tabText in tabs) {
            val tab = device.findObject(UiSelector().textContains(tabText))
            assert(tab.exists()) { "Onglet $tabText non trouvé" }
        }
    }
}
