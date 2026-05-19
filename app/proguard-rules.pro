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

# Repository package (Keep all for Lyrics/Cloud logic and reflection)
-keep class com.beatflowy.app.repository.** { *; }
-keepclassmembers class com.beatflowy.app.repository.** { *; }

# Drive package
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
# GOOGLE PLAY SERVICES
# ══════════════════════════════════════════════════════════════════════════════

-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.android.gms.auth.api.signin.** { *; }
-dontwarn com.google.android.gms.**


# ══════════════════════════════════════════════════════════════════════════════
# GOOGLE API CLIENT + DRIVE
# ══════════════════════════════════════════════════════════════════════════════

-keep class com.google.api.** { *; }
-dontwarn com.google.api.**
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}


# ══════════════════════════════════════════════════════════════════════════════
# ANDROIDX LIBRARIES
# ══════════════════════════════════════════════════════════════════════════════

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Media
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }

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
# RELEASE BUILD — STRIP DEBUG LOGGING
# ══════════════════════════════════════════════════════════════════════════════

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# ══════════════════════════════════════════════════════════════════════════════
# ONLINE LYRICS MODELS — MUST SURVIVE REFLECTION
# ══════════════════════════════════════════════════════════════════════════════
-keep class com.beatflowy.app.repository.LrcLibResponse { *; }
-keep interface com.beatflowy.app.repository.LrcLibService { *; }
-keep class com.beatflowy.app.repository.LyricsResult { *; }
-keep class com.beatflowy.app.repository.LyricsType { *; }
-keepclassmembers enum com.beatflowy.app.repository.LyricsType { *; }
-keep class com.beatflowy.app.model.LrcLine { *; }
