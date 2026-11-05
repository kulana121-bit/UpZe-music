# MusicSpot R8 rules. AndroidX, Compose, Media3, DataStore and coroutines ship consumer rules;
# the rules below cover the app's own reflection points and keep crash traces mappable.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# The playback service is instantiated by the framework from the manifest, and Media3 binds it
# through its MediaSessionService/MediaLibraryService intent filters.
-keep class com.ravi.musicspot.playback.PlaybackService { *; }
-keep class * extends androidx.media3.session.MediaSessionService { <init>(); }

# Media3: decoders/extractors and renderers are created reflectively by DefaultRenderersFactory and
# DefaultExtractorsFactory (optional extension modules). Keep their constructors when present.
-keepclassmembers class androidx.media3.decoder.** { <init>(...); }
-keepclassmembers class androidx.media3.exoplayer.ext.** { <init>(...); }
-keep class androidx.media3.session.** implements android.os.Parcelable { *; }
-keep class androidx.media3.common.** implements android.os.Parcelable { *; }
-dontwarn androidx.media3.**

# Enums restored by name from DataStore.
-keepclassmembers enum com.ravi.musicspot.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Guava (pulled in by Media3 session's ListenableFuture APIs) references optional annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn afu.org.checkerframework.**
-dontwarn java.lang.reflect.AnnotatedType

# ---------------------------------------------------------------------------------------------
# v2.0 additions
# ---------------------------------------------------------------------------------------------

# kotlinx.serialization: keep generated serializers + Companion/serializer() of every @Serializable
# class (stored as JSON in files/DataStore/encrypted AccountStore and in MediaItem extras).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.ravi.musicspot.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ravi.musicspot.**$$serializer { *; }
# Our persisted/serialized models: names must stay stable (enum names and sealed class
# discriminators are written to disk).
-keep @kotlinx.serialization.Serializable class com.ravi.musicspot.** { *; }
-keepnames class com.ravi.musicspot.model.** { *; }
-dontwarn kotlinx.serialization.**

# NewPipe Extractor: services, extractors and link handlers are looked up by class and use
# reflection in places; nanojson parses into its own types. Keep the whole extractor.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino (used by NewPipe to run YouTube's signature/n-param JS): heavy reflection on its own
# classes (ScriptRuntime, NativeJavaObject, generated classes, javax.script engine).
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**

# jsoup: optional re2j regex engine and java.awt references.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**

# protobuf-javalite: generated message fields are accessed reflectively by name.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.protobuf.**

# OkHttp / Okio (consumer rules ship with OkHttp 4; these silence optional platform probes).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**

# Media3 additional: keep the session/notification provider & default extension lookups.
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.extractor.DefaultExtractorsFactory { *; }

# WorkManager instantiates workers reflectively by class name with (Context, WorkerParameters).
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.ravi.musicspot.data.DownloadWorker { *; }

# Kyant Backdrop and Haze: runtime shaders/RenderEffects built via platform APIs; keep the
# libraries intact (small) so API-level gated paths are not stripped inconsistently.
-keep class com.kyant.backdrop.** { *; }
-dontwarn com.kyant.**
-keep class dev.chrisbanes.haze.** { *; }
-dontwarn dev.chrisbanes.haze.**

# Coil 3 service loaders (network fetcher) are discovered via ServiceLoader.
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-dontwarn coil3.**

# ---------------------------------------------------------------------------------------------
# v2.0.2: release must open on Android 10 (API 29). v2.0.1's fully optimised R8 build did not.
# Safer, slightly bigger release: shrink + obfuscate, but no R8 code optimisation (no inlining,
# class merging, enum unboxing, constructor inlining, outlining of app code), R8 compat mode
# (gradle.properties android.enableR8.fullMode=false), and every app class kept as written.
# ---------------------------------------------------------------------------------------------
-dontoptimize
-keep class com.ravi.musicspot.** { *; }
-keepnames class com.ravi.musicspot.** { *; }
# Crash safety net: kept intact, never merged into other classes (it must work when everything else fails).
-keep class com.ravi.musicspot.core.CrashGuard { *; }
-keep class com.ravi.musicspot.core.CrashGuard$* { *; }
-keep class com.ravi.musicspot.CrashActivity { *; }
# API-gated glass code stays in its own @RequiresApi holders (never inlined/merged into API-29 callers).
-keep class com.ravi.musicspot.ui.glass.GlassApi31 { *; }
-keep class com.ravi.musicspot.ui.glass.GlassApi33 { *; }
-keepattributes Exceptions,Deprecated,MethodParameters,RuntimeInvisibleAnnotations
# Compose runtime / material3 / animation: keep enum/token classes readable for crash traces.
-keepnames class androidx.compose.material3.tokens.** { *; }
