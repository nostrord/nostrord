# Nostrord ProGuard Rules

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
