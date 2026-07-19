import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

configure<ApplicationExtension> {
    namespace = "com.beatraxus.app"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.beatraxus.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "3.0.0-stable"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
        }

        val lastFmKey = localProperties.getProperty("LASTFM_API_KEY")
            ?: error("Missing LASTFM_API_KEY in local.properties")
        val lastFmSecret = localProperties.getProperty("LASTFM_SECRET")
            ?: error("Missing LASTFM_SECRET in local.properties")
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmKey\"")
        buildConfigField("String", "LASTFM_SECRET", "\"$lastFmSecret\"")

        val telegramApiId = localProperties.getProperty("TELEGRAM_API_ID", "0")
        val telegramApiHash = localProperties.getProperty("TELEGRAM_API_HASH", "")
        buildConfigField("String", "TELEGRAM_API_ID", "\"$telegramApiId\"")
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

        val dropboxAppKey = localProperties.getProperty("DROPBOX_APP_KEY", "")
        buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
        manifestPlaceholders["DROPBOX_APP_KEY"] = dropboxAppKey

        val onedriveClientId = localProperties.getProperty("ONEDRIVE_CLIENT_ID", "")
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"$onedriveClientId\"")

        val boxClientId = localProperties.getProperty("BOX_CLIENT_ID", "")
        val boxClientSecret = localProperties.getProperty("BOX_CLIENT_SECRET", "")
        buildConfigField("String", "BOX_CLIENT_ID", "\"$boxClientId\"")
        buildConfigField("String", "BOX_CLIENT_SECRET", "\"$boxClientSecret\"")

        val googleClientId = localProperties.getProperty("GOOGLE_CLIENT_ID", "")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")

    }

    signingConfigs {
        create("release") {
            storeFile = localProperties.getProperty("RELEASE_STORE_FILE")?.let { file(it) }
                ?: error("Missing RELEASE_STORE_FILE in local.properties")
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            matchingFallbacks += listOf("debug", "release")
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "tflite"
    }

    externalNativeBuild {
        cmake {
            version = "3.31.0"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val appName = "Beatraxus"
            val variantName = variant.name
            val versionName = output.versionName.get()
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier ?: "universal"
            
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.let { impl ->
                impl.outputFileName.set("${appName}-v${versionName}-${abi}-${variantName}.apk")
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("com.google.android.material:material:1.12.0")

    val lifecycleVersion = "2.8.7"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycleVersion")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.media:media:1.7.0")
    
    val media3Version = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    val retrofitVersion = "2.9.0"
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("com.airbnb.android:lottie-compose:6.3.0")
    implementation("sh.calvin.reorderable:reorderable:2.3.2")

    val roomVersion = "2.7.0-alpha11"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation(files("libs/ffmpeg-kit-full-gpl-6.0-2.aar"))
    implementation("com.arthenica:smart-exception-java:0.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    implementation("com.google.android.gms:play-services-cast-framework:21.4.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    
    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1")

    implementation("com.google.apis:google-api-services-drive:v3-rev20240903-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.api-client:google-api-client-android:2.5.0")
    implementation("com.google.http-client:google-http-client-android:1.45.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("com.github.tdlibx:td:1.8.56")

    // Dropbox
    implementation("com.dropbox.core:dropbox-core-sdk:8.0.1")
    implementation("com.dropbox.core:dropbox-android-sdk:8.0.1")

    // OneDrive / Microsoft Graph
    implementation("com.microsoft.identity.client:msal:5.5.0")
    implementation("com.microsoft.graph:microsoft-graph:5.80.0")

    // Box
    implementation("com.box:box-android-sdk:5.0.0")

    // Nextcloud (WebDAV) — no vendor SDK needed, use Sardine (pure WebDAV client)
    implementation("com.github.thegrizzlylabs:sardine-android:0.8")

    // SMB/CIFS
    implementation("com.hierynomus:smbj:0.13.0")

    // FTP/SFTP
    implementation("commons-net:commons-net:3.11.1")       // FTP
    implementation("com.hierynomus:sshj:0.38.0")           // SFTP (SSH-based)

    configurations.all {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xmlpull", module = "xmlpull")
    }
}
