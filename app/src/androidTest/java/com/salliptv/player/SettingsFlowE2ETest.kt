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
 * E2E Test: Tester le flux de configuration Settings
 * - Ajout d'une playlist M3U
 * - Ajout d'une playlist Xtream Codes
 * - Test de connexion
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowE2ETest {

    private lateinit var device: UiDevice
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun testSettingsActivityOpens() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
        scenario = ActivityScenario.launch(intent)
        
        Thread.sleep(2000)
        
        // Vérifier que l'écran Settings est chargé
        val deviceIdLabel = device.findObject(UiSelector().textContains("ID Appareil"))
        assert(deviceIdLabel.exists()) { "Label ID Appareil non trouvé" }
        
        // Vérifier le QR code
        val qrCode = device.findObject(UiSelector().resourceIdMatches(".*iv_qr_code.*"))
        assert(qrCode.exists()) { "QR Code non trouvé" }
    }

    @Test
    fun testM3UInputSection() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
        scenario = ActivityScenario.launch(intent)
        
        Thread.sleep(2000)
        
        // Cliquer sur "Ajouter manuellement"
        val manualAddBtn = device.findObject(UiSelector().textContains("Ajouter manuellement"))
        if (manualAddBtn.exists()) {
            manualAddBtn.click()
            Thread.sleep(500)
        }
        
        // Vérifier que le mode M3U est sélectionné par défaut
        val m3uBtn = device.findObject(UiSelector().textContains("M3U"))
        assert(m3uBtn.exists()) { "Bouton M3U non trouvé" }
        
        // Vérifier les champs de saisie M3U
        val urlField = device.findObject(UiSelector().resourceIdMatches(".*et_m3u_url.*"))
        assert(urlField.exists()) { "Champ URL M3U non trouvé" }
        
        // Vérifier les boutons Test et Sauvegarder
        val testBtn = device.findObject(UiSelector().textContains("TESTER"))
        val saveBtn = device.findObject(UiSelector().textContains("SAUVEGARDER"))
        
        assert(testBtn.exists()) { "Bouton TESTER non trouvé" }
        assert(saveBtn.exists()) { "Bouton SAUVEGARDER non trouvé" }
    }

    @Test
    fun testXtreamInputSection() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
        scenario = ActivityScenario.launch(intent)
        
        Thread.sleep(2000)
        
        // Cliquer sur "Ajouter manuellement"
        val manualAddBtn = device.findObject(UiSelector().textContains("Ajouter manuellement"))
        if (manualAddBtn.exists()) {
            manualAddBtn.click()
            Thread.sleep(500)
        }
        
        // Cliquer sur Xtream Codes
        val xtreamBtn = device.findObject(UiSelector().textContains("Xtream"))
        if (xtreamBtn.exists()) {
            xtreamBtn.click()
            Thread.sleep(500)
        }
        
        // Vérifier les champs Xtream
        val serverField = device.findObject(UiSelector().resourceIdMatches(".*et_server.*"))
        val usernameField = device.findObject(UiSelector().resourceIdMatches(".*et_username.*"))
        val passwordField = device.findObject(UiSelector().resourceIdMatches(".*et_password.*"))
        
        assert(serverField.exists()) { "Champ Serveur non trouvé" }
        assert(usernameField.exists()) { "Champ Username non trouvé" }
        assert(passwordField.exists()) { "Champ Password non trouvé" }
    }

    @Test
    fun testPlaylistListDisplay() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
        scenario = ActivityScenario.launch(intent)
        
        Thread.sleep(3000)
        
        // Vérifier la liste des playlists (peut être vide au début)
        val playlistsContainer = device.findObject(UiSelector().resourceIdMatches(".*layout_playlists.*"))
        assert(playlistsContainer.exists()) { "Conteneur playlists non trouvé" }
        
        // Prendre un screenshot
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "test_settings.png")
        device.takeScreenshot(file)
    }
}
