# ─── Beatraxus ProGuard Rules ────────────────────────────────────────────────

# ══════════════════════════════════════════════════════════════════════════════
# BEATRAXUS APP PACKAGES
# ══════════════════════════════════════════════════════════════════════════════

# DSP engine — must not be renamed (JNI + math-critical)
-keep class com.beatraxus.app.engine.** { *; }

# Data models — used by Room, Gson, Retrofit; must survive reflection
-keep class com.beatraxus.app.model.** { *; }

# Services — bound by Android OS via intent/component name
-keep class com.beatraxus.app.service.** { *; }

# Repository package (Keep all for Lyrics/Cloud logic and reflection)
-keep class com.beatraxus.app.repository.** { *; }
-keepclassmembers class com.beatraxus.app.repository.** { *; }

# Drive package
-keep class com.beatraxus.app.drive.** { *; }
-keepclassmembers class com.beatraxus.app.drive.** { *; }

# Application / ViewModel / top-level classes
-keep class com.beatraxus.app.BeatraxusApplication { *; }
-keep class com.beatraxus.app.viewmodel.** { *; }
-keep class com.beatraxus.app.utils.** { *; }

# Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ══════════════════════════════════════════════════════════════════════════════
# RETROFIT + OKHTTP + GSON
# ══════════════════════════════════════════════════════════════════════════════

-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault, EnclosingMethod, InnerClasses

-keep class retrofit2.** { *; }
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}
-keep class retrofit2.KotlinExtensions* { *; }

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit Kotlin Coroutine Support
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
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
# JAudioTagger (Embedded Lyrics)
# ══════════════════════════════════════════════════════════════════════════════

-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**


# ══════════════════════════════════════════════════════════════════════════════
# GOOGLE PLAY SERVICES & AUTH
# ══════════════════════════════════════════════════════════════════════════════

-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.android.gms.auth.api.signin.internal.** { *; }
-keep class com.google.android.gms.common.api.** { *; }
-keep class com.google.android.gms.common.api.Scope { *; }
-keep class com.google.android.gms.common.api.Status { *; }
-keep class com.google.android.gms.auth.api.signin.GoogleSignInOptions { *; }
-keep class com.google.android.gms.auth.api.signin.GoogleSignInAccount { *; }
-dontwarn com.google.android.gms.**

# Crucial: Keep Parcelable CREATORs for GMS IPC
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keep class com.google.android.gms.common.internal.safeparcel.SafeParcelable {
    public static final *** CREATOR;
}

# Keep specific resources that Google Play Services might need
-keep class com.google.android.gms.common.R$* { *; }


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

# Ensure reflection-based service loading works
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,EnclosingMethod,InnerClasses
-keep class com.google.api.client.json.gson.GsonFactory { *; }
-keep class com.google.api.client.http.javanet.NetHttpTransport { *; }
-keepnames class com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
-keepnames class com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException

# Resolve missing classes from Apache HttpClient used by Google API Client
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**


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
-keep class com.beatraxus.app.repository.LrcLibResponse { *; }
-keep interface com.beatraxus.app.repository.LrcLibService { *; }
-keep class com.beatraxus.app.repository.LyricsResult { *; }
-keep class com.beatraxus.app.repository.LyricsType { *; }
-keepclassmembers enum com.beatraxus.app.repository.LyricsType { *; }
-keep class com.beatraxus.app.model.LrcLine { *; }

# TDLIB (Telegram Library)
-keep class org.drinkless.tdlib.** { *; }

# ══════════════════════════════════════════════════════════════════════════════
# ADDITIONAL GOOGLE / FIREBASE / CREDENTIALS
# ══════════════════════════════════════════════════════════════════════════════

-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Google Sign-In / Play Services Auth / Sign-in
-keep class com.google.android.gms.signin.** { *; }

# Credential Manager / Identity Services
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Google API Client JSON models
-keep class * extends com.google.api.client.json.GenericJson { *; }
-keep class com.google.api.services.** { *; }
