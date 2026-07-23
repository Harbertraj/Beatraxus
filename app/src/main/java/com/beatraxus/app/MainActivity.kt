package com.beatraxus.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.security.MessageDigest
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

import com.beatraxus.app.R
import com.beatraxus.app.repository.DriveAccount
import com.beatraxus.app.service.AudioPlaybackService
import com.beatraxus.app.ui.screens.MainScreen
import com.beatraxus.app.ui.screens.SettingsScreen
import com.beatraxus.app.ui.screens.WelcomeScreen
import com.beatraxus.app.ui.screens.LoadingScreen

import com.beatraxus.app.ui.components.dsp.DspScreen
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatraxus.app.perf.FrameJankMonitor
import com.beatraxus.app.ui.theme.BeatraxusTheme
import com.beatraxus.app.viewmodel.PlayerViewModel
import com.beatraxus.app.viewmodel.PlayerViewModelFactory

class MainActivity : FragmentActivity() {

    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModelFactory(application)
    }

    private var serviceBound = false
    private lateinit var frameJankMonitor: FrameJankMonitor
    private var pendingPermissionAction: (() -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AudioPlaybackService.LocalBinder
            localBinder?.getService()?.let { service ->
                viewModel.attachService(service)
                serviceBound = true
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val essentialPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (results[essentialPermission] == true) {
            Log.d("MainActivity", "Essential permission granted, loading library")
            if (pendingPermissionAction == null) {
                viewModel.loadLibrary()
            } else {
                pendingPermissionAction?.invoke()
            }
        } else {
            viewModel.onPermissionDenied()
        }
        pendingPermissionAction = null
    }

    override fun onStart() {
        super.onStart()
        frameJankMonitor.start()
        startAudioService()
        bindAudioService()
    }

    override fun onResume() {
        super.onResume()
        viewModel.handleDropboxAuth()
    }

    /**
     * Logs the SHA-1 / SHA-256 fingerprint of the certificate that actually signed
     * THIS running build (whatever that is — local debug key, local release key, a
     * CI-built key, or a key Google Play re-signed the app with). Google Sign-In
     * (used for Drive) fails with ApiException status 10 (DEVELOPER_ERROR) unless
     * this exact fingerprint is registered as an "Android" OAuth 2.0 Client ID for
     * package `com.beatraxus.app` in Google Cloud Console. Telegram (API ID/hash)
     * and Last.fm (custom URI scheme callback) do NOT check this at all, which is
     * exactly why they can work while Drive alone fails.
     *
     * Filter Logcat for tag "SigningCert" (works in release builds too — logcat
     * isn't gated by isDebuggable).
     */
    private fun logSigningCertFingerprints() {
        try {
            @Suppress("DEPRECATION")
            val signatures: Array<android.content.pm.Signature>? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = packageManager.getPackageInfo(
                        packageName, PackageManager.GET_SIGNING_CERTIFICATES
                    ).signingInfo
                    if (info?.hasMultipleSigners() == true) info.apkContentsSigners else info?.signingCertificateHistory
                } else {
                    packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                }

            signatures?.forEachIndexed { index, signature ->
                val certBytes = signature.toByteArray()
                val sha1 = MessageDigest.getInstance("SHA-1").digest(certBytes)
                    .joinToString(":") { "%02X".format(it) }
                val sha256 = MessageDigest.getInstance("SHA-256").digest(certBytes)
                    .joinToString(":") { "%02X".format(it) }
                Log.i("SigningCert", "── signer #$index for $packageName ──")
                Log.i("SigningCert", "SHA-1:   $sha1")
                Log.i("SigningCert", "SHA-256: $sha256")
            }
            if (signatures.isNullOrEmpty()) {
                Log.w("SigningCert", "No signing certificates found for $packageName")
            }
        } catch (e: Exception) {
            Log.e("SigningCert", "Failed to read signing certificate", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logSigningCertFingerprints() // See Logcat tag "SigningCert" — compare against Google Cloud Console
        frameJankMonitor = FrameJankMonitor("BeatraxusFrameMonitor")

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        handleIntent(intent)

        setContent {
            BeatraxusTheme {
                BeatraxusApp(
                    viewModel = viewModel,
                    onRequestPermissions = { action -> requestPermissions(action) }
                )
            }
        }

        // Start library loading after the first frame is drawn to avoid UI jank on startup
        window.decorView.post {
            com.beatraxus.app.cast.CastManager.initialize(this@MainActivity) { error ->
                viewModel.setCastErrorMessage(error)
            }
            if (!viewModel.uiState.value.isFirstRun) {
                // Post again to ensure the activity is fully settled before starting intensive I/O
                window.decorView.postDelayed({
                    checkAndRequestPermissions()
                }, 500)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_now_playing", false) == true) {
            viewModel.setShowFullPlayer(true)
        }

        // Handle Last.fm auth callback and external audio VIEW/SEND intents
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND) {
            val uri = if (intent.action == Intent.ACTION_SEND) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            } else {
                intent.data
            }

            if (uri != null) {
                if (uri.scheme == "beatraxus" && uri.host == "lastfm") {
                    val token = uri.getQueryParameter("token")
                    if (token != null && viewModel.isPendingLastFmAuthRequest()) {
                        viewModel.fetchLastFmSession(token)
                    } else {
                        Log.w("MainActivity", "Ignoring unsolicited lastfm callback intent")
                    }
                } else {
                    // Check if it's an audio file/content
                    val type = intent.type ?: contentResolver.getType(uri)
                    if (type?.startsWith("audio/") == true ||
                        uri.toString().endsWith(".mp3", true) ||
                        uri.toString().endsWith(".flac", true) ||
                        uri.toString().endsWith(".wav", true) ||
                        uri.toString().endsWith(".m4a", true)) {
                        viewModel.playExternalUri(uri)
                    } else if (type == "application/json" || uri.toString().endsWith(".json", true)) {
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                val json = input.bufferedReader().readText()
                                val presets = com.beatraxus.app.utils.PresetExporter.parseJson(json)
                                if (presets != null && presets.isNotEmpty()) {
                                    viewModel.importEqPresets(presets)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "JSON import failed", e)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        frameJankMonitor.stop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun startAudioService() {
        val intent = Intent(this, AudioPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindAudioService() {
        val intent = Intent(this, AudioPlaybackService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    fun requestPermissions(onPermissionGranted: (() -> Unit)? = null) {
        val essentialPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        // If already granted, just trigger library load and return
        if (ContextCompat.checkSelfPermission(this, essentialPermission) == PackageManager.PERMISSION_GRANTED) {
            if (onPermissionGranted == null) {
                viewModel.loadLibrary()
            } else {
                onPermissionGranted()
            }
            return
        }

        pendingPermissionAction = onPermissionGranted

        val permissions = mutableListOf(essentialPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkAndRequestPermissions() {
        val essentialPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, essentialPermission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadLibrary()
        } else {
            requestPermissions()
        }
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

sealed class Screen(val route: String) {
    object Main      : Screen("main")
    object Settings  : Screen("settings")
    object Dsp       : Screen("dsp")
    object Inspector : Screen("inspector")
}

@Composable
fun BeatraxusApp(
    viewModel: PlayerViewModel,
    onRequestPermissions: (onGranted: () -> Unit) -> Unit
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.addMusicFolder(it.toString())
        }
        viewModel.consumeFolderPickerTrigger()
    }

    val driveSignInOptions = remember {
        // NOTE: requestIdToken() intentionally removed. It requires a separate
        // "Web application" type OAuth client ID (GOOGLE_WEB_CLIENT_ID) that is
        // easy to misconfigure or leave inconsistent across machines/CI (it lives
        // in the gitignored local.properties), and it was causing DEVELOPER_ERROR
        // (status 10) failures independent of the SHA-1/package-based Android
        // OAuth client check. Drive access here only needs the account email and
        // the Drive scopes below — no ID token is required for that.
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_READONLY), Scope(DriveScopes.DRIVE_METADATA_READONLY))
            .build()
    }

    val googleSignInClient = remember(context, driveSignInOptions) {
        val activity = context.findActivity()
        if (activity != null) {
            GoogleSignIn.getClient(activity, driveSignInOptions)
        } else {
            // Fallback for non-activity context, though it shouldn't happen in MainActivity
            GoogleSignIn.getClient(context, driveSignInOptions)
        }
    }

    val driveAccountLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.i("MainActivity", "Drive account launcher result: ${result.resultCode}")

        // Even if not RESULT_OK, try to get the account/task to see if there's an error status
        val task = result.data?.let { GoogleSignIn.getSignedInAccountFromIntent(it) }

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val account = task?.getResult(ApiException::class.java)
                Log.d("MainActivity", "Google sign in success: ${account?.email}")
                if (account != null && account.email != null) {
                    viewModel.addDriveAccount(DriveAccount(
                        account.email!!,
                        account.displayName ?: "Google Drive",
                        account.photoUrl?.toString(),
                        true
                    ))
                } else {
                    Log.e("MainActivity", "Google account or email is null")
                    viewModel.setErrorMessage("Sign in failed: Account information missing")
                }
            } catch (e: ApiException) {
                Log.e("MainActivity", "Google sign in failed: status code = ${e.statusCode}", e)
                viewModel.setErrorMessage("Google sign in failed: status code ${e.statusCode}")
            } catch (e: Exception) {
                Log.e("MainActivity", "Unexpected error during Google sign in", e)
                viewModel.setErrorMessage("Sign in error: ${e.localizedMessage}")
            }
        } else {
            // Handle failure/cancel
            if (task != null) {
                try {
                    task.getResult(ApiException::class.java)
                } catch (e: ApiException) {
                    Log.e("MainActivity", "Google sign in error status: ${e.statusCode}")
                    val message = if (e.statusCode == com.google.android.gms.common.api.CommonStatusCodes.DEVELOPER_ERROR) {
                        "Sign in failed (DEVELOPER_ERROR). The SHA-1 fingerprint of the certificate signing THIS build " +
                                "isn't registered as an Android OAuth client in Google Cloud Console for com.beatraxus.app. " +
                                "If this build was installed from Play, register the SHA-1 shown under Play Console > " +
                                "Setup > App integrity (Play App Signing key) — it differs from your local upload/release key."
                    } else {
                        "Sign in failed: Status code ${e.statusCode}."
                    }
                    viewModel.setErrorMessage(message)
                    return@rememberLauncherForActivityResult
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing sign in result", e)
                }
            }

            if (result.resultCode == Activity.RESULT_CANCELED) {
                Log.d("MainActivity", "Google sign in canceled by user or failed internally")
            } else {
                Log.e("MainActivity", "Google sign in failed. Code: ${result.resultCode}")
                viewModel.setErrorMessage("Google sign in failed (Code: ${result.resultCode})")
            }
        }
    }

    val authRecoveryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("MainActivity", "Auth recovery result: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            // Retry scan for all enabled accounts
            uiState.driveAccounts.filter { it.enabled }.forEach { account ->
                viewModel.scanDriveAccount(account.email)
            }
        } else {
            Log.e("MainActivity", "Auth recovery failed or canceled. Code: ${result.resultCode}")
        }
        viewModel.consumeAuthRecoveryIntent()
    }

    LaunchedEffect(uiState.authRecoveryIntent) {
        uiState.authRecoveryIntent?.let { intent ->
            authRecoveryLauncher.launch(intent)
        }
    }

    LaunchedEffect(uiState.triggerFolderPicker) {
        if (uiState.triggerFolderPicker) {
            folderPickerLauncher.launch(null)
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = if (uiState.isFirstRun) "welcome" else "loading"
        ) {
            composable(
                "welcome",
                exitTransition = {
                    fadeOut(tween(500)) + scaleOut(targetScale = 0.9f, animationSpec = tween(500))
                }
            ) {
                WelcomeScreen(
                    viewModel = viewModel,
                    onEnterFlow = onRequestPermissions,
                    onFinish = {
                        if (navController.currentBackStackEntry?.destination?.route == "welcome") {
                            viewModel.setFirstRunComplete()
                            navController.navigate("loading") {
                                popUpTo("welcome") { inclusive = true }
                            }
                        }
                    }
                )
            }
            composable(
                "loading",
                exitTransition = { fadeOut(tween(500)) }
            ) {
                LoadingScreen(
                    viewModel = viewModel,
                    onLoadingFinished = {
                        navController.navigate(Screen.Main.route) {
                            popUpTo("loading") { inclusive = true }
                        }
                    }
                )
            }
            composable(
                Screen.Main.route,
                enterTransition = {
                    if (initialState.destination.route == "welcome") {
                        fadeIn(tween(800, easing = FastOutSlowInEasing)) +
                                scaleIn(initialScale = 1.1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
                    } else {
                        fadeIn(tween(400, easing = FastOutSlowInEasing)) +
                                slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                                )
                    }
                },
                exitTransition = {
                    fadeOut(tween(400, easing = FastOutSlowInEasing)) +
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                animationSpec = tween(400, easing = FastOutSlowInEasing)
                            )
                },
                popEnterTransition = {
                    fadeIn(tween(400, easing = FastOutSlowInEasing)) +
                            slideIntoContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = tween(400, easing = FastOutSlowInEasing)
                            )
                }
            ) {
                MainScreen(
                    viewModel            = viewModel,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToDsp      = { navController.navigate(Screen.Dsp.route) },
                    onNavigateToInspector = { songId -> navController.navigate("inspector/$songId") }
                )
            }
            composable(
                Screen.Settings.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(450, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(400))
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(450, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(400))
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(450, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(400))
                }
            ) {
                SettingsScreen(
                    playerViewModel = viewModel,
                    onBack    = { navController.popBackStack() },
                    onNavigateToDsp = { navController.navigate(Screen.Dsp.route) },
                    onRequestGDriveAccount = {
                        googleSignInClient.signOut().addOnCompleteListener {
                            try {
                                driveAccountLauncher.launch(googleSignInClient.signInIntent)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to launch sign-in intent", e)
                                viewModel.setErrorMessage("Could not start Google Sign-In")
                            }
                        }
                    }
                )
            }
            composable(
                Screen.Dsp.route,
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(400, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(400))
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(400, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(400))
                }
            ) {
                DspScreen(
                    viewModel = viewModel,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(
                "inspector/{songId}",
                arguments = listOf(navArgument("songId") { type = NavType.StringType }),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(400, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(400))
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(400, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(400))
                }
            ) { backStackEntry ->
                val songId = backStackEntry.arguments?.getString("songId") ?: return@composable
                com.beatraxus.app.ui.screens.MusicDetailInspectorScreen(
                    songId = songId,
                    viewModel = viewModel,
                    onBack = {
                        val uiState = viewModel.uiState.value
                        if (uiState.pendingInspectorReturnSong == null) {
                            // Opened from the Now Playing screen's own info icon: return there.
                            // We set showFullPlayer to ensure we land on the player screen,
                            // but we no longer call setShowSongInfo(true) so the dialog
                            // doesn't immediately reopen and cover the player.
                            viewModel.setShowFullPlayer(true)
                        } else {
                            // Opened from a song's options sheet: clear the pending return
                            // flag so MainScreen doesn't re-trigger the sheet on back.
                            viewModel.setPendingInspectorReturn(null)
                        }
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}