#!/bin/bash

# Script E2E Test pour SallIPTV
# Ce script exécute les tests end-to-end sur un émulateur Android ou un device connecté

set -e

# Couleurs pour les logs
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== SallIPTV E2E Tests ===${NC}"
echo ""

# Vérifier que adb est disponible
if ! command -v adb &> /dev/null; then
    echo -e "${RED}❌ adb non trouvé. Assurez-vous que Android SDK est installé.${NC}"
    exit 1
fi

# Vérifier qu'un device est connecté
DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device" | wc -l)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo -e "${RED}❌ Aucun device connecté. Veuillez connecter un device ou démarrer un émulateur.${NC}"
    echo "   Démarrer un émulateur: emulator -avd <nom_avd>"
    exit 1
fi

echo -e "${GREEN}✓ Device trouvé${NC}"
adb devices

echo ""
echo -e "${YELLOW}📦 Compilation et installation de l'APK...${NC}"

# Compiler l'APK debug
./gradlew assembleFreeDebug

# Installer l'APK
adb install -r app/build/outputs/apk/free/debug/app-free-debug.apk

echo ""
echo -e "${YELLOW}🧪 Exécution des tests E2E...${NC}"

# Exécuter les tests
./gradlew connectedFreeDebugAndroidTest

echo ""
echo -e "${GREEN}=== Tests E2E Terminés ===${NC}"
echo ""
echo "Rapports disponibles dans:"
echo "  - app/build/reports/androidTests/connected/"
echo "  - app/build/outputs/androidTest-results/"
echo ""

# Récupérer les screenshots des tests
echo -e "${YELLOW}📸 Récupération des screenshots...${NC}"
mkdir -p test-results
cd test-results

# Pull screenshots depuis le device
adb shell "ls /sdcard/" 2>/dev/null | grep -q "test_" && adb pull /sdcard/test_*.png . 2>/dev/null || echo "Aucun screenshot sur /sdcard"

# Pull fichiers de l'app
cd ..
adb shell "run-as com.salliptv.app ls files/" 2>/dev/null | grep -q "test_" 2>/dev/null && \
    adb shell "run-as com.salliptv.app cp files/test_*.png /sdcard/" 2>/dev/null && \
    adb pull /sdcard/test_*.png test-results/ 2>/dev/null || echo "Screenshots dans l'app"

echo -e "${GREEN}✅ Tests E2E complétés!${NC}"
