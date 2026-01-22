# Nostrord ProGuard Rules

# Ignore warnings for missing optional dependencies (desktop JVM doesn't need all classes)
-ignorewarnings

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializable classes
-keep,includedescriptorclasses class org.nostr.nostrord.**$$serializer { *; }
-keepclassmembers class org.nostr.nostrord.** {
    *** Companion;
}
-keepclasseswithmembers class org.nostr.nostrord.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes used for JSON
-keepclassmembers class org.nostr.nostrord.network.** { *; }
-keepclassmembers class org.nostr.nostrord.nostr.** { *; }
-keepclassmembers class org.nostr.nostrord.model.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep Ktor
-keep class io.ktor.** { *; }

# Keep OkHttp (used by Ktor on Android)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.** { *; }

# Keep secp256k1 native library bindings
-keep class fr.acinq.secp256k1.** { *; }

# Standard Android rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Suppress warnings for missing classes (desktop JVM doesn't have all Android/iOS classes)
-dontwarn android.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn dalvik.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn sun.misc.**
-dontwarn sun.nio.**
-dontwarn sun.security.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.codehaus.**
-dontwarn reactor.blockhound.**
-dontwarn io.netty.**

# Keep Skiko (Compose Desktop rendering)
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

# Keep Coil image loading
-keep class coil3.** { *; }
-dontwarn coil3.**

# Don't warn about missing optional dependencies
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn kotlinx.atomicfu.**
