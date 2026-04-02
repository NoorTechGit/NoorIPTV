#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>

// ============================================================================
// CONFIGURATION - Clés morcelées (anti-strings analysis)
// ============================================================================

// API_BASE morcelé: "https://api.salliptv.com/v1"
// Stocké en fragments pour éviter l'extraction facile
static const char API_FRAG_1[] = {0x68, 0x74, 0x74, 0x70, 0x73, 0x3a, 0x2f, 0x2f, 0x00}; // "https://"
static const char API_FRAG_2[] = {0x61, 0x70, 0x69, 0x2e, 0x73, 0x61, 0x6c, 0x6c, 0x69, 0x70, 0x74, 0x76, 0x2e, 0x63, 0x6f, 0x6d, 0x00}; // "api.salliptv.com"
static const char API_FRAG_3[] = {0x2f, 0x76, 0x31, 0x00}; // "/v1"

// Clé de chiffrement XOR (rotation)
static unsigned char XOR_KEY[] = {0x4A, 0x9F, 0x23, 0xE1, 0x56, 0xBC, 0x78, 0xD4};

// Signature hash attendue (SHA-256 de ton certificat release)
// Remplace par ton vrai hash après génération du keystore
static const char EXPECTED_SIGNATURE_HASH[] = "0000000000000000000000000000000000000000000000000000000000000000";

#define LOG_TAG "SallSec"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================================
// UTILITAIRES DE CHIFFREMENT SIMPLE
// ============================================================================

/**
 * Déchiffre une chaîne avec XOR rotatif
 * Anti-analyse statique des strings
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_salliptv_player_security_NativeSec_decryptString(JNIEnv* env, jobject, jbyteArray encrypted, jint len) {
    jbyte* data = env->GetByteArrayElements(encrypted, nullptr);
    
    std::string result;
    result.reserve(len);
    
    for (int i = 0; i < len; i++) {
        result += (char)(data[i] ^ XOR_KEY[i % sizeof(XOR_KEY)]);
    }
    
    env->ReleaseByteArrayElements(encrypted, data, 0);
    return env->NewStringUTF(result.c_str());
}

/**
 * Reconstruit l'API_BASE à la volée
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_salliptv_player_security_NativeSec_getApiBase(JNIEnv* env, jobject) {
    std::string apiBase;
    apiBase += API_FRAG_1;
    apiBase += API_FRAG_2;
    apiBase += API_FRAG_3;
    return env->NewStringUTF(apiBase.c_str());
}

// ============================================================================
// DÉTECTION DE TAMPERING (Signature APK)
// ============================================================================

/**
 * Vérifie la signature de l'APK nativement
 * Beaucoup plus difficile à hook qu'en Java/Kotlin
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_verifySignature(JNIEnv* env, jobject, jobject context) {
    // Récupérer le PackageManager
    jclass contextClass = env->FindClass("android/content/Context");
    jmethodID getPackageManager = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject packageManager = env->CallObjectMethod(context, getPackageManager);
    
    // Récupérer le package name
    jmethodID getPackageName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    jstring packageName = (jstring)env->CallObjectMethod(context, getPackageName);
    
    // PackageManager.getPackageInfo()
    jclass pmClass = env->FindClass("android/content/pm/PackageManager");
    jmethodID getPackageInfo = env->GetMethodID(pmClass, "getPackageInfo", 
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    
    // GET_SIGNATURES = 64
    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfo, packageName, 64);
    
    if (packageInfo == nullptr) {
        LOGE("Failed to get package info");
        return JNI_FALSE;
    }
    
    // Récupérer les signatures
    jclass packageInfoClass = env->FindClass("android/content/pm/PackageInfo");
    jfieldID signaturesField = env->GetFieldID(packageInfoClass, "signatures", "[Landroid/content/pm/Signature;");
    jobjectArray signatures = (jobjectArray)env->GetObjectField(packageInfo, signaturesField);
    
    if (signatures == nullptr || env->GetArrayLength(signatures) == 0) {
        LOGE("No signatures found");
        return JNI_FALSE;
    }
    
    // Récupérer la première signature
    jobject signature = env->GetObjectArrayElement(signatures, 0);
    jclass signatureClass = env->FindClass("android/content/pm/Signature");
    jmethodID toByteArray = env->GetMethodID(signatureClass, "toByteArray", "()[B");
    jbyteArray sigBytes = (jbyteArray)env->CallObjectMethod(signature, toByteArray);
    
    // Calculer SHA-256
    jclass messageDigestClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(messageDigestClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring sha256 = env->NewStringUTF("SHA-256");
    jobject md = env->CallStaticObjectMethod(messageDigestClass, getInstance, sha256);
    
    jmethodID digest = env->GetMethodID(messageDigestClass, "digest", "([B)[B");
    jbyteArray hash = (jbyteArray)env->CallObjectMethod(md, digest, sigBytes);
    
    // Convertir en hex string
    jsize hashLen = env->GetArrayLength(hash);
    jbyte* hashBytes = env->GetByteArrayElements(hash, nullptr);
    
    char hexStr[65];
    for (int i = 0; i < hashLen && i < 32; i++) {
        sprintf(hexStr + (i * 2), "%02x", (unsigned char)hashBytes[i]);
    }
    hexStr[64] = '\0';
    
    env->ReleaseByteArrayElements(hash, hashBytes, 0);
    
    // Comparer avec le hash attendu
    bool valid = (strcmp(hexStr, EXPECTED_SIGNATURE_HASH) == 0);
    
    if (!valid) {
        LOGE("Signature mismatch! Got: %s", hexStr);
    }
    
    return valid ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// ANTI-DEBUGGING (Détection basique)
// ============================================================================

/**
 * Vérifie si un débogueur est attaché
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_isDebuggerAttached(JNIEnv*, jobject) {
    // Vérifier /proc/self/status pour TracerPid
    FILE* fp = fopen("/proc/self/status", "r");
    if (fp == nullptr) return JNI_FALSE;
    
    char line[256];
    bool debuggerDetected = false;
    
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int tracerPid = atoi(line + 10);
            if (tracerPid != 0) {
                debuggerDetected = true;
            }
            break;
        }
    }
    
    fclose(fp);
    return debuggerDetected ? JNI_TRUE : JNI_FALSE;
}

/**
 * Vérifie si l'app est debuggable
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_isDebuggable(JNIEnv* env, jobject, jobject context) {
    jclass contextClass = env->FindClass("android/content/Context");
    jmethodID getApplicationInfo = env->GetMethodID(contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    jobject appInfo = env->CallObjectMethod(context, getApplicationInfo);
    
    jclass appInfoClass = env->FindClass("android/content/pm/ApplicationInfo");
    jfieldID flagsField = env->GetFieldID(appInfoClass, "flags", "I");
    jint flags = env->GetIntField(appInfo, flagsField);
    
    // FLAG_DEBUGGABLE = 2
    return (flags & 2) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// ANTI-FRIDA (Détection)
// ============================================================================

/**
 * Détecte la présence de Frida (méthodes basiques)
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_isFridaDetected(JNIEnv*, jobject) {
    // Vérifier les pipes Frida typiques
    const char* fridaPipes[] = {
        "/data/local/tmp/frida-server",
        "/data/local/tmp/frida-gadget",
        "/data/local/tmp/re.frida.server",
        "/sdcard/frida-server",
        "/data/data/re.frida.server"
    };
    
    for (const char* pipe : fridaPipes) {
        if (access(pipe, F_OK) == 0) {
            LOGE("Frida artifact detected: %s", pipe);
            return JNI_TRUE;
        }
    }
    
    // Vérifier les processus suspects
    FILE* fp = popen("ps -A | grep frida", "r");
    if (fp != nullptr) {
        char buffer[256];
        if (fgets(buffer, sizeof(buffer), fp) != nullptr) {
            pclose(fp);
            LOGE("Frida process detected");
            return JNI_TRUE;
        }
        pclose(fp);
    }
    
    return JNI_FALSE;
}

// ============================================================================
// CRASH SÉCURISÉ (Si détection)
// ============================================================================

/**
 * Crash natif silencieux (impossible à catcher en Java)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_salliptv_player_security_NativeSec_secureCrash(JNIEnv*, jobject) {
    // Méthode 1: Accès mémoire invalide
    volatile int* ptr = (int*)0xDEADBEEF;
    *ptr = 0x12345678;
    
    // Fallback si l'accès mémoire échoue (très improbable)
    abort();
}

// ============================================================================
// VALIDATION LICENCE (Serveur)
// ============================================================================

/**
 * Génère un token de validation hardware
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_salliptv_player_security_NativeSec_generateHardwareToken(JNIEnv* env, jobject, jstring deviceId, jstring fingerprint) {
    const char* devId = env->GetStringUTFChars(deviceId, nullptr);
    const char* fp = env->GetStringUTFChars(fingerprint, nullptr);
    
    // Simple hash combiné (à améliorer avec vrai HMAC en production)
    unsigned long hash = 5381;
    
    for (const char* p = devId; *p; p++) {
        hash = ((hash << 5) + hash) + *p;
    }
    for (const char* p = fp; *p; p++) {
        hash = ((hash << 5) + hash) + *p;
    }
    
    char token[64];
    snprintf(token, sizeof(token), "%016lX", hash);
    
    env->ReleaseStringUTFChars(deviceId, devId);
    env->ReleaseStringUTFChars(fingerprint, fp);
    
    return env->NewStringUTF(token);
}

// ============================================================================
// INIT
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    // Anti-LD_PRELOAD
    Dl_info info;
    if (dladdr((void*)JNI_OnLoad, &info) == 0) {
        LOGE("dladdr failed - possible LD_PRELOAD");
        abort();
    }
    
    return JNI_VERSION_1_6;
}
