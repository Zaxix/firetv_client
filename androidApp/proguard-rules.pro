# Aurora TV release shrinking rules.

# Our own code is small; keep it whole (serialized DTOs, settings, expect/actual platform classes).
# R8 still strips and optimises the large libraries (Compose, Material icons, Ktor, Media3).
-keep class com.auroratv.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod, SourceFile, LineNumberTable

# kotlinx.serialization (generated serializers are looked up via Companion.serializer()).
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }

# libass (ass-media): JNI calls back into these classes by name.
-keep class io.github.peerless2012.** { *; }

# Jellyfin FFmpeg decoder: loaded by DefaultRenderersFactory via reflection, and JNI-backed.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.** { native <methods>; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Ktor: the default HttpClient() (used for artwork) finds its engine through ServiceLoader.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# Optional/JVM-only classes referenced by Ktor, OkHttp and SLF4J that don't exist on Android.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**
-dontwarn reactor.blockhound.**
-dontwarn io.ktor.utils.io.jvm.nio.**
