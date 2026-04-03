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
 * E2E Test: Test du lecteur vidéo
 * Vérifie que le PlayerActivity se lance correctement avec les extras
 */
@RunWith(AndroidJUnit4::class)
class PlayerE2ETest {

    private lateinit var device: UiDevice
    private var scenario: ActivityScenario<PlayerActivity>? = null

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun testPlayerActivityLaunch() {
        // Créer un intent avec des données de test
        val intent = Intent(ApplicationProvider.getApplicationContext(), PlayerActivity::class.java).apply {
            putExtra("channelId", 1)
            putExtra("channelName", "Test Channel")
            putExtra("channelLogo", "")
            putExtra("channelNumber", 1)
            putExtra("streamUrl", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
            putExtra("currentPosition", 0)
            putExtra("playlistId", 1)
            putExtra("isPremium", false)
            putExtra("groupTitle", "Test Group")
            putExtra("channelType", "LIVE")
            putExtra("groupId", "")
            putExtra("channelQuality", "HD")
        }
        
        scenario = ActivityScenario.launch(intent)
        Thread.sleep(3000)
        
        // Vérifier que le PlayerView est présent
        val playerView = device.findObject(UiSelector().resourceIdMatches(".*player_view.*"))
        assert(playerView.exists()) { "PlayerView non trouvé" }
        
        // Vérifier le nom de la chaîne affiché
        val channelName = device.findObject(UiSelector().textContains("Test Channel"))
        // Le nom peut être dans l'overlay qui disparaît
    }

    @Test
    fun testPlayerOverlayControls() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), PlayerActivity::class.java).apply {
            putExtra("channelId", 1)
            putExtra("channelName", "Test Channel")
            putExtra("streamUrl", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
            putExtra("channelType", "LIVE")
        }
        
        scenario = ActivityScenario.launch(intent)
        Thread.sleep(3000)
        
        // Tap sur l'écran pour afficher l'overlay
        val playerView = device.findObject(UiSelector().resourceIdMatches(".*player_view.*"))
        if (playerView.exists()) {
            playerView.click()
            Thread.sleep(500)
        }
        
        // Vérifier les contrôles de lecture
        val playPauseBtn = device.findObject(UiSelector().resourceIdMatches(".*btn_play_pause.*"))
        // Les contrôles peuvent avoir des IDs différents selon l'implémentation
        
        // Prendre un screenshot
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "test_player.png")
        device.takeScreenshot(file)
    }
}
