#include <jni.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <unistd.h>
#include <signal.h>
#include <pthread.h>
#include <android/log.h>
#include <fcntl.h>
#include <cstdlib>
#include <cstring>
#include <sys/system_properties.h>

#define LOG_TAG "SallSec"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ============================================================================
// ANTI-DEBUGGING AVANCÉ
// ============================================================================

/**
 * Détecte le tracing avec PTRACE_TRACEME
 * Si un débogueur est déjà attaché, ptrace échouera
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_checkPtrace(JNIEnv*, jobject) {
    // Essayer de se tracer soi-même
    // Si un débogueur est déjà là, ça échoue
    if (ptrace(PTRACE_TRACEME, 0, nullptr, nullptr) == -1) {
        LOGD("PTRACE_TRACEME failed - debugger detected");
        return JNI_TRUE;
    }
    
    // Se détacher si pas de debugger
    ptrace(PTRACE_DETACH, 0, nullptr, nullptr);
    return JNI_FALSE;
}

/**
 * Détecte les breakpoints logiciels (0xCC int3)
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_checkBreakpoints(JNIEnv*, jobject) {
    // Vérifier les 100 premiers bytes de JNI_OnLoad
    unsigned char* func = (unsigned char*)Java_com_salliptv_player_security_NativeSec_checkBreakpoints;
    
    for (int i = 0; i < 100; i++) {
        if (func[i] == 0xCC) { // int3 breakpoint
            LOGD("Breakpoint detected at offset %d", i);
            return JNI_TRUE;
        }
    }
    
    return JNI_FALSE;
}

/**
 * Vérifie si l'app tourne dans un émulateur
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_isEmulator(JNIEnv*, jobject) {
    // Vérifier les caractéristiques hardware
    
    // Vérifier /proc/cpuinfo pour QEMU
    FILE* fp = fopen("/proc/cpuinfo", "r");
    if (fp != nullptr) {
        char line[256];
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, "hypervisor") || strstr(line, "QEMU") || strstr(line, "KVM")) {
                fclose(fp);
                LOGD("Emulator detected via cpuinfo");
                return JNI_TRUE;
            }
        }
        fclose(fp);
    }
    
    // Vérifier les propriétés build
    char value[PROP_VALUE_MAX];
    __system_property_get("ro.kernel.qemu", value);
    if (atoi(value) == 1) {
        LOGD("QEMU emulator detected");
        return JNI_TRUE;
    }
    
    __system_property_get("ro.hardware.vm", value);
    if (strlen(value) > 0) {
        LOGD("VM detected: %s", value);
        return JNI_TRUE;
    }
    
    return JNI_FALSE;
}

// Thread de surveillance anti-debug
static pthread_t watcherThread;
static volatile bool stopWatcher = false;

static void* debugWatcher(void*) {
    while (!stopWatcher) {
        // Vérifier toutes les secondes
        sleep(1);
        
        // Vérifier TracerPid
        FILE* fp = fopen("/proc/self/status", "r");
        if (fp == nullptr) continue;
        
        char line[256];
        while (fgets(line, sizeof(line), fp)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                int tracerPid = atoi(line + 10);
                if (tracerPid != 0) {
                    LOGD("Debugger attached during runtime!");
                    fclose(fp);
                    // Crash sécurisé
                    volatile int* ptr = nullptr;
                    *ptr = 0;
                    abort();
                }
                break;
            }
        }
        fclose(fp);
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_salliptv_player_security_NativeSec_startDebugWatcher(JNIEnv*, jobject) {
    stopWatcher = false;
    pthread_create(&watcherThread, nullptr, debugWatcher, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_salliptv_player_security_NativeSec_stopDebugWatcher(JNIEnv*, jobject) {
    stopWatcher = true;
    pthread_join(watcherThread, nullptr);
}

// ============================================================================
// ANTI-XPOSED / ANTI-EDXPOSED
// ============================================================================

/**
 * Détecte Xposed Framework
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_isXposedDetected(JNIEnv*, jobject) {
    // Vérifier les traces natives
    
    FILE* fp = fopen("/proc/self/maps", "r");
    if (fp != nullptr) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, "XposedBridge") || 
                strstr(line, "libxposed") ||
                strstr(line, "libedxp")) {
                fclose(fp);
                LOGD("Xposed library detected in maps");
                return JNI_TRUE;
            }
        }
        fclose(fp);
    }
    
    // Vérifier les variables d'environnement
    char* xposed = getenv("XPOSED");
    if (xposed != nullptr) {
        LOGD("XPOSED env var detected");
        return JNI_TRUE;
    }
    
    return JNI_FALSE;
}

// ============================================================================
// ANTI-ROOT AVANCÉ
// ============================================================================

/**
 * Détecte le root de manière plus approfondie
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_isRootedDeep(JNIEnv*, jobject) {
    // Fichiers binaires root courants
    const char* rootBinaries[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk",
        "/system/app/Magisk.apk",
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/dev/kernelsu"
    };
    
    for (const char* path : rootBinaries) {
        if (access(path, F_OK) == 0) {
            LOGD("Root binary detected: %s", path);
            return JNI_TRUE;
        }
    }
    
    // Vérifier si on peut ouvrir /data/data avec des droits spéciaux
    int fd = open("/data/data", O_RDONLY | O_DIRECTORY);
    if (fd != -1) {
        // En théorie, accès restreint sans root
        close(fd);
    }
    
    return JNI_FALSE;
}