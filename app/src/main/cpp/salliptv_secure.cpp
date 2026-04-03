/*
 * SallIPTV Secure Native Library
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <signal.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <android/log.h>

#define LOG_TAG "SallIPTV_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Clé secrète dérivée (en dur dans le binaire, difficile à extraire)
static const char SECRET_KEY[] = "S4ll1PTV_S3cur3_K3y_v1_2026";

extern "C" {

/*
 * Déclenche un crash natif (SIGSEGV)
 * Utilisé par SelfDestruct pour arrêter l'app brutalement
 */
JNIEXPORT void JNICALL
Java_com_salliptv_player_security_NativeBridge_triggerNativeCrash(JNIEnv *env, jobject thiz) {
    LOGD("Triggering native crash (SIGSEGV)");
    
    // Écrire dans une adresse invalide = SIGSEGV
    volatile int *ptr = (volatile int *)0x0;
    *ptr = 42;  // Crash garanti
}

/*
 * Vérifie si un debugger est attaché via ptrace
 * Retourne true si debugger détecté
 */
JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeBridge_isDebuggerAttachedNative(JNIEnv *env, jobject thiz) {
    // Méthode 1: ptrace PT_TRACEME
    // Si un debugger est attaché, ptrace échoue
    if (ptrace(PTRACE_TRACEME, 0, NULL, NULL) == -1) {
        LOGD("Debugger detected via ptrace");
        return JNI_TRUE;
    }
    
    // Détacher immédiatement si pas de debugger
    ptrace(PTRACE_DETACH, 0, NULL, NULL);
    
    // Méthode 2: Vérifier /proc/self/status
    FILE *fp = fopen("/proc/self/status", "r");
    if (fp != NULL) {
        char line[256];
        while (fgets(line, sizeof(line), fp)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                int tracerPid = atoi(line + 10);
                fclose(fp);
                if (tracerPid != 0) {
                    LOGD("Debugger detected via TracerPid: %d", tracerPid);
                    return JNI_TRUE;
                }
                return JNI_FALSE;
            }
        }
        fclose(fp);
    }
    
    // Méthode 3: Vérifier si on peut lire /proc/self/mem
    // Un debugger attaché bloque souvent cet accès
    int fd = open("/proc/self/mem", O_RDONLY);
    if (fd == -1) {
        LOGD("Debugger possibly detected (mem access blocked)");
        return JNI_TRUE;
    }
    close(fd);
    
    return JNI_FALSE;
}

/*
 * Vérifie si le device est rooté (checks natifs)
 * Retourne true si root détecté
 */
JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeBridge_isDeviceRootedNative(JNIEnv *env, jobject thiz) {
    // Check 1: Fichiers su courants
    const char *suPaths[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk",
        "/system/app/Magisk.apk",
        "/sbin/.magisk",
        "/dev/.magisk.unblock",
        NULL
    };
    
    for (int i = 0; suPaths[i] != NULL; i++) {
        struct stat st;
        if (stat(suPaths[i], &st) == 0) {
            LOGD("Root file detected: %s", suPaths[i]);
            return JNI_TRUE;
        }
    }
    
    // Check 2: Accès écriture /system (root uniquement)
    int testFile = open("/system/.salliptv_test", O_CREAT | O_WRONLY, 0644);
    if (testFile != -1) {
        close(testFile);
        remove("/system/.salliptv_test");
        LOGD("Root detected (can write to /system)");
        return JNI_TRUE;
    }
    
    // Check 3: Vérifier si on peut exécuter su
    int pipefd[2];
    if (pipe(pipefd) == 0) {
        pid_t pid = fork();
        if (pid == 0) {
            // Processus enfant
            close(pipefd[0]);
            dup2(pipefd[1], STDOUT_FILENO);
            execl("/system/bin/su", "su", "-c", "id", NULL);
            _exit(1);  // execl a échoué = su pas disponible
        } else if (pid > 0) {
            // Processus parent
            close(pipefd[1]);
            char buffer[64];
            int status;
            waitpid(pid, &status, 0);
            ssize_t n = read(pipefd[0], buffer, sizeof(buffer) - 1);
            close(pipefd[0]);
            
            if (n > 0) {
                buffer[n] = '\0';
                if (strstr(buffer, "uid=0") != NULL) {
                    LOGD("Root detected (su command works)");
                    return JNI_TRUE;
                }
            }
        }
    }
    
    // Check 4: Vérifier les propriétés système
    // Certains ROMs rootées modifient ro.build.tags
    char buildTags[128] = {0};
    FILE *fp = popen("getprop ro.build.tags", "r");
    if (fp != NULL) {
        if (fgets(buildTags, sizeof(buildTags), fp) != NULL) {
            // Enlever le newline
            size_t len = strlen(buildTags);
            if (len > 0 && buildTags[len-1] == '\n') {
                buildTags[len-1] = '\0';
            }
            
            if (strstr(buildTags, "test-keys") != NULL) {
                LOGD("Root detected (test-keys build)");
                pclose(fp);
                return JNI_TRUE;
            }
        }
        pclose(fp);
    }
    
    return JNI_FALSE;
}

/*
 * Vérifie la signature de l'APK (native)
 * Retourne true si signature valide
 * 
 * NOTE: Cette fonction nécessite le chemin vers l'APK
 * et la clé publique attendue (hash)
 */
JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeBridge_verifyApkSignatureNative(JNIEnv *env, jobject thiz) {
    // Récupérer le contexte pour obtenir le chemin de l'APK
    jclass contextClass = env->FindClass("android/content/Context");
    jmethodID getPackageCodePath = env->GetMethodID(contextClass, "getPackageCodePath", "()Ljava/lang/String;");
    
    // On ne peut pas facilement accéder au contexte ici sans le passer
    // Simplification: vérification faite côté Java, cette fonction est un placeholder
    // pour des vérifications plus avancées si besoin
    
    LOGD("APK signature verification (placeholder - use Java implementation)");
    return JNI_TRUE;
}

/*
 * Chiffre une chaîne avec XOR + rotation (obfuscation simple)
 * Pas de vraie sécurité crypto, juste pour décourager l'inspection
 */
JNIEXPORT jstring JNICALL
Java_com_salliptv_player_security_NativeBridge_nativeEncrypt(JNIEnv *env, jobject thiz, jstring input) {
    const char *inputStr = env->GetStringUTFChars(input, NULL);
    if (inputStr == NULL) {
        return NULL;
    }
    
    size_t len = strlen(inputStr);
    char *output = (char *)malloc(len + 1);
    if (output == NULL) {
        env->ReleaseStringUTFChars(input, inputStr);
        return NULL;
    }
    
    // XOR avec clé dérivée + rotation
    for (size_t i = 0; i < len; i++) {
        char keyChar = SECRET_KEY[i % strlen(SECRET_KEY)];
        output[i] = inputStr[i] ^ keyChar ^ ((i * 7) & 0xFF);
    }
    output[len] = '\0';
    
    jstring result = env->NewStringUTF(output);
    
    free(output);
    env->ReleaseStringUTFChars(input, inputStr);
    
    return result;
}

/*
 * Déchiffre une chaîne (inverse de nativeEncrypt)
 */
JNIEXPORT jstring JNICALL
Java_com_salliptv_player_security_NativeBridge_nativeDecrypt(JNIEnv *env, jobject thiz, jstring input) {
    // Même opération que chiffrement (XOR symétrique)
    return Java_com_salliptv_player_security_NativeBridge_nativeEncrypt(env, thiz, input);
}

/*
 * Vérifie si l'app tourne dans un émulateur (checks natifs)
 */
JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeBridge_isEmulatorNative(JNIEnv *env, jobject thiz) {
    // Check 1: QEMU hardware
    char hardware[128] = {0};
    FILE *fp = popen("getprop ro.hardware", "r");
    if (fp != NULL) {
        if (fgets(hardware, sizeof(hardware), fp) != NULL) {
            size_t len = strlen(hardware);
            if (len > 0 && hardware[len-1] == '\n') {
                hardware[len-1] = '\0';
            }
            
            if (strstr(hardware, "goldfish") != NULL ||
                strstr(hardware, "ranchu") != NULL ||
                strstr(hardware, "qemu") != NULL ||
                strstr(hardware, "virtual") != NULL) {
                LOGD("Emulator detected via hardware: %s", hardware);
                pclose(fp);
                return JNI_TRUE;
            }
        }
        pclose(fp);
    }
    
    // Check 2: Vérifier les drivers QEMU
    if (access("/dev/qemu_pipe", F_OK) == 0 ||
        access("/dev/goldfish_pipe", F_OK) == 0) {
        LOGD("Emulator detected via QEMU pipes");
        return JNI_TRUE;
    }
    
    // Check 3: Vérifier /proc/cpuinfo pour QEMU
    fp = fopen("/proc/cpuinfo", "r");
    if (fp != NULL) {
        char line[256];
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, "QEMU") != NULL ||
                strstr(line, "qemu") != NULL) {
                LOGD("Emulator detected via cpuinfo");
                fclose(fp);
                return JNI_TRUE;
            }
        }
        fclose(fp);
    }
    
    return JNI_FALSE;
}

/*
 * Vérifie si Frida est présente (détection basique)
 */
JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeBridge_detectFridaNative(JNIEnv *env, jobject thiz) {
    // Check 1: Port Frida par défaut (27042)
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock != -1) {
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons(27042);
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        
        if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
            LOGD("Frida detected (port 27042 open)");
            close(sock);
            return JNI_TRUE;
        }
        close(sock);
    }
    
    // Check 2: Threads nommés (Frida nomme ses threads)
    // Cette vérification est complexe en C natif, simplifiée ici
    
    return JNI_FALSE;
}

} // extern "C"
