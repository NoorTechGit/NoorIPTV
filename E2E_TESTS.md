# Tests E2E - SallIPTV

Ce document décrit les tests End-to-End (E2E) pour l'application SallIPTV Android.

## 📁 Structure des Tests

```
app/src/androidTest/java/com/salliptv/player/
├── SplashToHomeE2ETest.kt      # Test de lancement et chargement initial
├── SettingsFlowE2ETest.kt       # Test du flux Settings (playlist, QR)
├── MainNavigationE2ETest.kt     # Test de navigation principale
├── PlayerE2ETest.kt             # Test du lecteur vidéo
└── E2ETestRunner.kt             # Runner personnalisé
```

## 🚀 Exécution des Tests

### Prérequis

1. **Device Android ou Émulateur**
   - Android 5.0+ (API 21+)
   - Mode développeur activé (pour devices physiques)

2. **ADB Installé**
   ```bash
   adb --version
   ```

3. **Émulateur (optionnel)**
   ```bash
   # Lister les AVD disponibles
   emulator -list-avds
   
   # Démarrer un émulateur
   emulator -avd <nom_avd>
   ```

### Méthode 1: Script Automatisé

```bash
chmod +x e2e-test.sh
./e2e-test.sh
```

### Méthode 2: Commandes Gradle

```bash
# Compiler et tester
cd /Users/mamadousall/Documents/Perso/salliptv-projet
./gradlew connectedFreeDebugAndroidTest

# Tester uniquement (si déjà installé)
./gradlew connectedFreeDebugAndroidTest -x assembleFreeDebug

# Test spécifique
./gradlew connectedFreeDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.salliptv.player.SplashToHomeE2ETest
```

### Méthode 3: Via Android Studio

1. Ouvrir le projet dans Android Studio
2. Naviguer vers `app/src/androidTest/java/com/salliptv/player/`
3. Clic droit sur une classe de test → "Run 'NomDuTest'"

## 📋 Description des Tests

### 1. SplashToHomeE2ETest

**Scénario:** Teste le lancement initial de l'app

- ✅ L'application démarre sans crash
- ✅ La playlist demo (PorscheTV) est injectée automatiquement
- ✅ Les onglets principaux sont visibles (LIVE, VOD, SERIES, FAVORIS, RÉCENT)
- ✅ Les sections Home sont chargées

### 2. SettingsFlowE2ETest

**Scénario:** Teste les fonctionnalités de Settings

- ✅ L'écran Settings s'ouvre avec l'ID appareil
- ✅ Le QR code est généré
- ✅ La section d'ajout manuel M3U est fonctionnelle
- ✅ La section d'ajout manuel Xtream est fonctionnelle
- ✅ La liste des playlists s'affiche

### 3. MainNavigationE2ETest

**Scénario:** Teste la navigation principale

- ✅ Navigation entre les onglets (LIVE, VOD, SERIES, HOME)
- ✅ Sélection d'une catégorie en mode LIVE
- ✅ Affichage du nombre de chaînes
- ✅ Fonction de recherche
- ✅ Navigation vers Settings

### 4. PlayerE2ETest

**Scénario:** Teste le lecteur vidéo

- ✅ Le PlayerActivity se lance avec les extras
- ✅ Le PlayerView (ExoPlayer) est initialisé
- ✅ L'overlay des contrôles s'affiche

## 📊 Rapports et Résultats

### Rapports HTML

```
app/build/reports/androidTests/connected/index.html
```

### Résultats XML (JUnit)

```
app/build/outputs/androidTest-results/connected/*.xml
```

### Screenshots

Les captures d'écran sont sauvegardées dans:
- `test-results/` (après exécution du script)
- `/sdcard/` sur le device (pendant les tests)

## 🔧 Configuration

### Runner de Test

Le runner personnalisé `E2ETestRunner` est configuré dans `build.gradle`:

```gradle
defaultConfig {
    testInstrumentationRunner "com.salliptv.player.E2ETestRunner"
}
```

### Dépendances

```gradle
androidTestImplementation 'androidx.test.ext:junit:1.1.5'
androidTestImplementation 'androidx.test:runner:1.5.2'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
androidTestImplementation 'androidx.test.uiautomator:uiautomator:2.2.0'
```

## 🐛 Dépannage

### "No tests found"

```bash
# Nettoyer et rebuild
./gradlew clean
./gradlew assembleFreeDebug
./gradlew assembleFreeDebugAndroidTest
```

### "Device offline"

```bash
# Redémarrer adb
adb kill-server
adb start-server
adb devices
```

### Tests instables

- Augmenter les `Thread.sleep()` dans les tests si nécessaire
- Vérifier la connexion réseau sur l'émulateur/device
- S'assurer que l'animation système est désactivée (dans Options Développeur)

## 📝 Ajouter un Nouveau Test

```kotlin
package com.salliptv.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonNouveauTest {
    
    @Test
    fun testMaFonctionnalite() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        
        // Votre code de test ici
        val element = device.findObject(UiSelector().textContains("Texte"))
        element.click()
        
        // Assertions
        assert(element.exists())
    }
}
```

## 🔄 CI/CD Integration

Pour GitHub Actions:

```yaml
name: E2E Tests
on: [push, pull_request]

jobs:
  e2e:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Start Emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 29
          script: ./gradlew connectedCheck
      
      - name: Upload Results
        uses: actions/upload-artifact@v3
        with:
          name: e2e-results
          path: app/build/reports/androidTests/
```
