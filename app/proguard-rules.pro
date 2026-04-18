# ─── Beatraxus ProGuard Rules ────────────────────────────────────────────────

# Keep all Beatraxus engine classes (DSP math must not be renamed/removed)
-keep class com.beatflowy.app.engine.** { *; }
-keep class com.beatflowy.app.model.** { *; }
-keep class com.beatflowy.app.service.** { *; }

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# MediaSession compat
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Compose — keep all @Composable functions reachable from runtime
-keep @androidx.compose.runtime.Composable class * { *; }

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
