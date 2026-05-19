# ─── Beatraxus ProGuard Rules ────────────────────────────────────────────────

# ══════════════════════════════════════════════════════════════════════════════
# BEATRAXUS APP PACKAGES
# ══════════════════════════════════════════════════════════════════════════════

# DSP engine — must not be renamed (JNI + math-critical)
-keep class com.beatflowy.app.engine.** { *; }

# Data models — used by Room, Gson, Retrofit; must survive reflection
-keep class com.beatflowy.app.model.** { *; }

# Services — bound by Android OS via intent/component name
-keep class com.beatflowy.app.service.** { *; }

# ── FIX #1: Repository package ──────────────────────────────────────────────
-keep class com.beatflowy.app.repository.** { *; }
-keepclassmembers class com.beatflowy.app.repository.** { *; }

# ── FIX #2: Drive package ───────────────────────────────────────────────────
-keep class com.beatflowy.app.drive.** { *; }
-keepclassmembers class com.beatflowy.app.drive.** { *; }

# Application / ViewModel / top-level classes
-keep class com.beatflowy.app.BeatraxusApplication { *; }
-keep class com.beatflowy.app.viewmodel.** { *; }


# ══════════════════════════════════════════════════════════════════════════════
# RETROFIT + OKHTTP + GSON
# ══════════════════════════════════════════════════════════════════════════════

-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault, EnclosingMethod, InnerClasses

-keep class retrofit2.** { *; }
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}


# ══════════════════════════════════════════════════════════════════════════════
# GOOGLE CAST
# ══════════════════════════════════════════════════════════════════════════════

-keep class com.google.android.gms.cast.** { *; }
-keep class com.google.android.gms.cast.framework.** { *; }
-keep class com.google.android.gms.cast.framework.media.** { *; }

# Keep app-side Cast entry points
-keep public class * extends com.google.android.gms.cast.framework.OptionsProvider { *; }
-keep public class * extends com.google.android.gms.cast.framework.media.NotificationActionsProvider { *; }

# Mediarouter (used by Cast)
-keep class androidx.mediarouter.app.** { *; }
-keep class androidx.mediarouter.media.** { *; }
-keep class androidx.mediarouter.** { *; }

-dontwarn com.google.android.gms.cast.**


# ══════════════════════════════════════════════════════════════════════════════
# GOOGLE PLAY SERVICES — AUTH + SIGN-IN + TASKS
# ══════════════════════════════════════════════════════════════════════════════

-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.android.gms.auth.api.signin.internal.** { *; }
-keep class com.google.android.gms.auth.api.credentials.** { *; }
-dontwarn com.google.android.gms.**


# ══════════════════════════════════════════════════════════════════════════════
# GOOGLE API CLIENT + DRIVE
# ══════════════════════════════════════════════════════════════════════════════

-keep class com.google.api.** { *; }
-dontwarn com.google.api.**

-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**

# Google Drive / API Client reflection
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.services.drive.model.** { *; }
-keep class com.google.api.client.json.gson.** { *; }
-keep class com.google.api.client.extensions.android.** { *; }
-keep class * extends com.google.api.client.json.GenericJson { *; }
-keep class com.google.api.client.util.GenericData { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn com.google.api.client.extensions.android.util.store.FileDataStoreFactory
-dontwarn com.google.api.client.extensions.java6.auth.oauth2.FileCredentialStore

# @Key fields used by Google API client for JSON serialization
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}

# @Value enum fields
-keepclassmembers class * {
    @com.google.api.client.util.Value <fields>;
}

# Google OAuth client
-keep class com.google.auth.** { *; }
-dontwarn com.google.auth.**
-keep class com.google.oauth.** { *; }
-dontwarn com.google.oauth.**

# Suppress noisy warnings
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**
-dontwarn com.sun.net.httpserver.**
-dontwarn sun.misc.**
-dontwarn java.awt.**
-dontwarn com.google.common.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**


# ══════════════════════════════════════════════════════════════════════════════
# ANDROIDX LIBRARIES
# ══════════════════════════════════════════════════════════════════════════════

# Media
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# DataStore
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# DocumentFile / Downloads
-keep class androidx.documentfile.provider.** { *; }
-keep class androidx.documentfile.** { *; }

# Lifecycle
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# Compose
-keep @androidx.compose.runtime.Composable class * { *; }


# ══════════════════════════════════════════════════════════════════════════════
# KOTLIN COROUTINES
# ══════════════════════════════════════════════════════════════════════════════

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**


# ══════════════════════════════════════════════════════════════════════════════
# LIBRARIES (Coil, Lottie, FFmpeg, etc.)
# ══════════════════════════════════════════════════════════════════════════════

-keep class coil.** { *; }
-dontwarn coil.**

-keep class com.airbnb.android.lottie.** { *; }

-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.smartexception.**

-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**


# ══════════════════════════════════════════════════════════════════════════════
# GENERAL ANDROID KEEP RULES
# ══════════════════════════════════════════════════════════════════════════════

-keepattributes *Annotation*, Signature, InnerClasses
-keepattributes SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Enum values() / valueOf()
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ══════════════════════════════════════════════════════════════════════════════
# RELEASE BUILD — STRIP DEBUG LOGGING (Selective)
# ══════════════════════════════════════════════════════════════════════════════

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# ══════════════════════════════════════════════════════════════════════════════
# LYRICS & REPOSITORY SPECIFICS
# ══════════════════════════════════════════════════════════════════════════════
-keepclassmembers enum com.beatflowy.app.repository.LyricsType { *; }
-keep class com.beatflowy.app.repository.LyricsState { *; }
-keep class com.beatflowy.app.repository.LyricsState$* { *; }
-keep class com.beatflowy.app.repository.LyricsLoadResult { *; }
-keep class com.beatflowy.app.repository.DownloadProgress { *; }
-keep class com.beatflowy.app.repository.DownloadProgress$* { *; }
-keepclassmembers class com.beatflowy.app.repository.LrcLibResponse {
    @com.google.gson.annotations.SerializedName <fields>;
}
