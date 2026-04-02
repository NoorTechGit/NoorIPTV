#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <android/log.h>

#define LOG_TAG "SallSec"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ============================================================================
// CHIFFREMENT SIMPLE (XOR + ROTATION)
// 
// Note: Ceci est une implémentation simplifiée pour démo.
// En production, utiliser OpenSSL avec AES-256-GCM:
// 1. vcpkg install openssl:arm64-android
// 2. Ajouter find_package(OpenSSL REQUIRED) dans CMakeLists.txt
// 3. Décommenter le code OpenSSL complet ci-dessous
// ============================================================================

// Clé XOR morcelée (à reconstruire à la volée)
static const unsigned char KEY_PART1[] = {0x4A, 0x9F, 0x23, 0xE1, 0x56, 0xBC, 0x78, 0xD4};
static const unsigned char KEY_PART2[] = {0x12, 0x45, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23};

static void reconstructKey(unsigned char* key) {
    memcpy(key, KEY_PART1, 8);
    memcpy(key + 8, KEY_PART2, 8);
}

/**
 * Chiffrement XOR simple avec clé variable
 * ⚠️ Pour démo uniquement - PAS POUR PRODUCTION
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_salliptv_player_security_NativeSec_encryptAesGcm(JNIEnv* env, jobject, jbyteArray plaintext) {
    jsize len = env->GetArrayLength(plaintext);
    jbyte* data = env->GetByteArrayElements(plaintext, nullptr);
    
    // Reconstruire la clé
    unsigned char key[16];
    reconstructKey(key);
    
    // Chiffrement XOR avec la clé
    jbyteArray result = env->NewByteArray(len);
    jbyte* resultData = env->GetByteArrayElements(result, nullptr);
    
    for (jsize i = 0; i < len; i++) {
        // XOR + rotation simple
        resultData[i] = data[i] ^ key[i % 16];
    }
    
    env->ReleaseByteArrayElements(plaintext, data, 0);
    env->ReleaseByteArrayElements(result, resultData, 0);
    
    // Effacer la clé de la mémoire
    memset(key, 0, 16);
    
    return result;
}

/**
 * Déchiffrement XOR
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_salliptv_player_security_NativeSec_decryptAesGcm(JNIEnv* env, jobject, jbyteArray encrypted) {
    // XOR est symétrique, donc même fonction
    return Java_com_salliptv_player_security_NativeSec_encryptAesGcm(env, nullptr, encrypted);
}

/**
 * Calcule HMAC simple (hash d'empilement)
 * ⚠️ Pour démo uniquement - PAS POUR PRODUCTION
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_salliptv_player_security_NativeSec_hmacSha256(JNIEnv* env, jobject, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    
    // Hash simple (sum+rotate) - PAS SÉCURISÉ, pour démo uniquement
    unsigned char hash[32] = {0};
    unsigned int sum = 0;
    
    for (jsize i = 0; i < len; i++) {
        sum = (sum << 3) + (unsigned char)bytes[i];
        hash[i % 32] ^= (sum & 0xFF);
    }
    
    // Padding avec le sum
    for (int i = 0; i < 32; i++) {
        hash[i] ^= ((sum >> (i % 8)) & 0xFF);
    }
    
    env->ReleaseByteArrayElements(data, bytes, 0);
    
    jbyteArray result = env->NewByteArray(32);
    env->SetByteArrayRegion(result, 0, 32, (jbyte*)hash);
    
    return result;
}

// ============================================================================
// VALIDATION TEMPS (Anti-replay)
// ============================================================================

static long long serverTimeOffset = 0;

extern "C" JNIEXPORT void JNICALL
Java_com_salliptv_player_security_NativeSec_setServerTimeOffset(JNIEnv*, jobject, jlong offset) {
    serverTimeOffset = offset;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_salliptv_player_security_NativeSec_getSecureTimestamp(JNIEnv*, jobject) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long long localTime = (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    return localTime + serverTimeOffset;
}

/**
 * Vérifie si un timestamp est dans la fenêtre de validité (anti-replay)
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_salliptv_player_security_NativeSec_isTimestampValid(JNIEnv* env, jobject, jlong timestamp, jlong windowMs) {
    jlong current = Java_com_salliptv_player_security_NativeSec_getSecureTimestamp(env, nullptr);
    jlong diff = current - timestamp;
    
    // Timestamp dans le futur ou trop vieux
    if (diff < -5000 || diff > windowMs) { // -5s pour tolérance
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

/*
// ============================================================================
// IMPLEMENTATION OPENSSL COMPLÈTE (pour production)
// Décommenter et configurer CMakeLists.txt avec OpenSSL
// ============================================================================

#include <openssl/evp.h>
#include <openssl/aes.h>
#include <openssl/rand.h>
#include <openssl/hmac.h>

// Clé AES-256 morcelée
static const unsigned char KEY_PART3[] = {0x67, 0x89, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB};
static const unsigned char KEY_PART4[] = {0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB};

static void reconstructKey256(unsigned char* key) {
    memcpy(key, KEY_PART1, 8);
    memcpy(key + 8, KEY_PART2, 8);
    memcpy(key + 16, KEY_PART3, 8);
    memcpy(key + 24, KEY_PART4, 8);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_salliptv_player_security_NativeSec_encryptAesGcm_OpenSSL(JNIEnv* env, jobject, jbyteArray plaintext) {
    jsize len = env->GetArrayLength(plaintext);
    jbyte* data = env->GetByteArrayElements(plaintext, nullptr);
    
    unsigned char key[32];
    reconstructKey256(key);
    
    unsigned char iv[12];
    RAND_bytes(iv, 12);
    
    unsigned char ciphertext[4096];
    unsigned char tag[16];
    int ciphertext_len;
    
    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, nullptr, nullptr);
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr);
    EVP_EncryptInit_ex(ctx, nullptr, nullptr, key, iv);
    
    EVP_EncryptUpdate(ctx, ciphertext, &ciphertext_len, (unsigned char*)data, len);
    
    int final_len;
    EVP_EncryptFinal_ex(ctx, ciphertext + ciphertext_len, &final_len);
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, tag);
    EVP_CIPHER_CTX_free(ctx);
    
    env->ReleaseByteArrayElements(plaintext, data, 0);
    
    jbyteArray result = env->NewByteArray(12 + ciphertext_len + 16);
    jbyte* resultData = env->GetByteArrayElements(result, nullptr);
    
    memcpy(resultData, iv, 12);
    memcpy(resultData + 12, ciphertext, ciphertext_len);
    memcpy(resultData + 12 + ciphertext_len, tag, 16);
    
    env->ReleaseByteArrayElements(result, resultData, 0);
    memset(key, 0, 32);
    
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_salliptv_player_security_NativeSec_hmacSha256_OpenSSL(JNIEnv* env, jobject, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    
    unsigned char key[16];
    reconstructKey(key);
    
    unsigned char result[32];
    unsigned int result_len;
    
    HMAC(EVP_sha256(), key, 16, (unsigned char*)bytes, len, result, &result_len);
    
    env->ReleaseByteArrayElements(data, bytes, 0);
    memset(key, 0, 16);
    
    jbyteArray hmac = env->NewByteArray(result_len);
    env->SetByteArrayRegion(hmac, 0, result_len, (jbyte*)result);
    
    return hmac;
}
*/