# ============================================================================
# PROGUARD RULES - OBFUSCATION MAXIMALE (Anti-Crack)
# ============================================================================

# ============================================
# RÈGLES DE BASE (Gardez ces classes)
# ============================================

# Android Framework
-keep class android.** { *; }
-keep class androidx.** { *; }
-keep class com.google.android.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keepclassmembers class **$WhenMappings { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { @androidx.room.PrimaryKey <fields>; }
-keep class * implements androidx.room.RoomDatabase { *; }

# Gson (pour parsing JSON)
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }

# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-keep class androidx.media3.** { *; }

# Google Play Billing
-keep class com.android.billingclient.** { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Glide
-keep class com.bumptech.glide.** { *; }

# ============================================
# OBFUSCATION AGRESSIVE
# ============================================

# Repackager TOUT dans un seul package racine
-repackageclasses 'a'
-flattenpackagehierarchy 'a'

# Renommer les classes avec des noms courts et confus
-classobfuscationdictionary obfuscation-dictionary.txt
-packageobfuscationdictionary obfuscation-dictionary.txt
-obfuscationdictionary obfuscation-dictionary.txt

# Optimisations agressives
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# Supprimer tous les logs en release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Supprimer les logs System
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# Supprimer les String.format inutilisés
-assumenosideeffects class java.lang.String {
    public static java.lang.String format(...);
}

# ============================================
# PROTECTION ANTI-TAMPERING
# ============================================

# Ne pas conserver les noms de méthodes sensibles
# TOUT sera renommé
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers

# Optimiser les enums
-optimizations !code/simplification/enum

# Supprimer les attributs de debug
-keepattributes !Signature,!Exceptions,!InnerClasses,!SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable,!Deprecated,!Synthetic,!EnclosingMethod,!RuntimeVisibleAnnotations,!RuntimeInvisibleAnnotations,!RuntimeVisibleParameterAnnotations,!RuntimeInvisibleParameterAnnotations,!AnnotationDefault

# ============================================
# PROTECTION CODE SPECIFIQUE
# ============================================

# Garder les noms des méthodes JNI (obligatoire pour C++)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Garder les méthodes utilisées par réflexion pour Billing
-keepclassmembers class * {
    @com.android.billingclient.api.BillingFlowParams$Builder <methods>;
}

# ============================================
# SUPPRESSION DONNÉES SENSIBLES
# ============================================

# Supprimer les fichiers source des stack traces
-renamesourcefileattribute 'SallIPTV'

# Masquer les noms de classes internes
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ============================================
# RÈGLES POUR MODELS (conservées mais obfusquées)
# ============================================

# Les entités Room doivent garder leurs champs mais pas leurs noms de classe
-keepclassmembers class com.salliptv.player.model.** {
    <fields>;
    <init>(...);
}

# Supprimer les noms de classes des models
-keepnames class com.salliptv.player.model.** { *; }

# ============================================
# PROTECTION CONTRE REVERSE ENGINEERING
# ============================================

# Désactiver la rétro-analyse des strings
-optimizations !code/allocation/variable

# Empêcher l'extraction des constant strings
-assumenosideeffects class java.lang.StringBuilder {
    public java.lang.StringBuilder append(...);
}

# Optimiser les chaînes constantes
-optimizations code/removal/advanced

# ============================================
# NETTOYAGE FINAL
# ============================================

# Supprimer tous les fichiers inutilisés
-dontwarn **
-dontnote **

# Forcer l'optimisation des méthodes privées
-allowaccessmodification
