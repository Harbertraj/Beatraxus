package com.beatflowy.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.accounts.AccountManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.beatflowy.app.repository.DriveAccount
import com.beatflowy.app.service.AudioPlaybackService
import com.beatflowy.app.ui.screens.MainScreen
import com.beatflowy.app.ui.screens.SettingsScreen
import com.beatflowy.app.ui.screens.WelcomeScreen
import com.beatflowy.app.ui.screens.DownloadScreen
import com.beatflowy.app.ui.components.dsp.DspScreen
import com.beatflowy.app.viewmodel.QobuzDownloadViewModel
import androidx.compose.ui.Modifier
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatflowy.app.perf.FrameJankMonitor
import com.beatflowy.app.ui.theme.BeatraxusTheme
import com.beatflowy.app.viewmodel.PlayerViewModel
import com.beatflowy.app.viewmodel.PlayerViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModelFactory(application)
    }

    private val downloadViewModel: QobuzDownloadViewModel by viewModels {
        QobuzDownloadViewModel.Factory
    }

    private var serviceBound = false
    private lateinit var frameJankMonitor: FrameJankMonitor
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
            viewModel.loadLibrary()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    override fun onStart() {
        super.onStart()
        frameJankMonitor.start()
        startAudioService()
        bindAudioService()
    }

    private var isFirstCreate = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.beatflowy.app.cast.CastManager.initialize(this)
        frameJankMonitor = FrameJankMonitor("BeatraxusFrameMonitor")
        
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            BeatraxusTheme {
                BeatraxusApp(
                    viewModel = viewModel,
                    downloadViewModel = downloadViewModel,
                    onRequestPermissions = { requestPermissions() }
                )
            }
        }
        
        // Immediate check to start loading if possible
        window.decorView.post { checkAndRequestPermissions() }
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

    fun requestPermissions(onPermissionGranted: () -> Unit = {}) {
        val essentialPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val permissions = mutableListOf(essentialPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val essentialGranted = ContextCompat.checkSelfPermission(this, essentialPermission) == PackageManager.PERMISSION_GRANTED
        
        if (essentialGranted) {
            viewModel.loadLibrary()
            onPermissionGranted()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkAndRequestPermissions() {
        val essentialPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val essentialGranted = ContextCompat.checkSelfPermission(this, essentialPermission) == PackageManager.PERMISSION_GRANTED
        
        val prefs = getSharedPreferences("beatraxus", MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first_run", true)

        if (isFirstRun) {
            // On first run, we let the WelcomeScreen handle the flow.
            // Do not auto-request or auto-load library yet.
            return
        }

        if (essentialGranted) {
            viewModel.loadLibrary()
        } else {
            val permissions = mutableListOf(essentialPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

sealed class Screen(val route: String) {
    object Main      : Screen("main")
    object Settings  : Screen("settings")
    object Dsp       : Screen("dsp")
    object Download  : Screen("download_screen")
}

@Composable
fun BeatraxusApp(
    viewModel: PlayerViewModel,
    downloadViewModel: QobuzDownloadViewModel,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = view.context

    val downloadFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadLocation(it.toString())
            downloadViewModel.setDownloadLocation(it.toString())
        }
        viewModel.consumeDownloadFolderPickerTrigger()
    }

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
    }

    // Note: For Google Drive API, a Web Client ID is often required in requestIdToken
    // to avoid ApiException 10 (DEVELOPER_ERROR). 
    // Ensure you have registered your SHA-1 in the Google Cloud Console.
    val driveSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestProfile()
        .requestScopes(
            Scope(DriveScopes.DRIVE_READONLY),
            Scope(DriveScopes.DRIVE_METADATA_READONLY)
        )
        .build()

    val googleSignInClient = GoogleSignIn.getClient(context, driveSignInOptions)

    val driveAccountLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null && account.email != null) {
                viewModel.addDriveAccount(
                    DriveAccount(
                        email = account.email!!,
                        accountName = account.displayName ?: account.email!!,
                        photoUrl = account.photoUrl?.toString()
                    )
                )
            }
        } catch (e: ApiException) {
            Log.e("MainActivity", "Google Sign-In failed", e)
        }
    }

    val authRecoveryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // Re-trigger whatever failed? For now, just clearing the intent
            viewModel.consumeAuthRecoveryIntent()
        }
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

    LaunchedEffect(uiState.triggerDownloadFolderPicker) {
        if (uiState.triggerDownloadFolderPicker) {
            downloadFolderPickerLauncher.launch(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController    = navController,
            startDestination = if (uiState.isFirstRun) "welcome" else Screen.Main.route,
            enterTransition = {
                fadeIn(animationSpec = tween(400, easing = FastOutSlowInEasing))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(400, easing = FastOutSlowInEasing))
            }
        ) {
            composable(
                "welcome",
                exitTransition = {
                    fadeOut(tween(600, easing = FastOutSlowInEasing)) +
                            scaleOut(targetScale = 0.9f, animationSpec = tween(600, easing = FastOutSlowInEasing))
                }
            ) {
                WelcomeScreen(
                    viewModel = viewModel,
                    onEnterFlow = onRequestPermissions,
                    onFinish = {
                        viewModel.setFirstRunComplete()
                        navController.navigate(Screen.Main.route) {
                            popUpTo("welcome") { inclusive = true }
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
                                    towards = AnimatedContentTransitionScope.SlideDirection.End, // Back from Right to Left
                                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                                )
                    }
                },
                exitTransition = {
                    fadeOut(tween(400, easing = FastOutSlowInEasing)) +
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Start, // Forward to Right
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
                    onNavigateToDownload = { navController.navigate(Screen.Download.route) }
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
                    viewModel = viewModel,
                    downloadViewModel = downloadViewModel,
                    onBack    = { navController.popBackStack() },
                    onNavigateToDsp = { navController.navigate(Screen.Dsp.route) },
                    onRequestGDriveAccount = {
                        // Sign out first to ensure the account picker is always shown,
                        // allowing the user to select a different/new account.
                        googleSignInClient.signOut().addOnCompleteListener {
                            driveAccountLauncher.launch(googleSignInClient.signInIntent)
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
                Screen.Download.route,
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
                DownloadScreen(
                    viewModel = downloadViewModel,
                    onBack = { navController.popBackStack() },
                    onPickFolder = { viewModel.openDownloadFolderPicker() }
                )
            }
        }
    }

    // Observe navigation backstack to determine the current screen
}
