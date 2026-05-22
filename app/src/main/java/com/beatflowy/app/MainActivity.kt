package com.beatflowy.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName

import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity

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
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
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
                    onRequestPermissions = { requestPermissions() }
                )
            }
        }
        
        // Immediate check to start loading if possible, but skip on first run to avoid nagging the user
        // before they click the "Enter the Flow" button.
        window.decorView.post {
            if (!viewModel.uiState.value.isFirstRun) {
                checkAndRequestPermissions()
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

    fun requestPermissions(onPermissionGranted: () -> Unit = {}) {
        val essentialPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        // If already granted, just trigger library load and return
        if (ContextCompat.checkSelfPermission(this, essentialPermission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadLibrary()
            onPermissionGranted()
            return
        }

        val permissions = mutableListOf(essentialPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
sealed class Screen(val route: String) {
    object Main      : Screen("main")
    object Settings  : Screen("settings")
    object Dsp       : Screen("dsp")
    object Download  : Screen("download")
}

@Composable
fun BeatraxusApp(
    viewModel: PlayerViewModel,
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
        viewModel.consumeFolderPickerTrigger()
    }

    val driveSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
        .build()

    val googleSignInClient = GoogleSignIn.getClient(context as Activity, driveSignInOptions)

    val driveAccountLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                account?.email?.let { email ->
                    viewModel.addDriveAccount(DriveAccount(email, account.displayName ?: "Google Drive", account.photoUrl?.toString(), true))
                }
            } catch (e: ApiException) {
                Log.e("MainActivity", "Google sign in failed", e)
            }
        }
    }

    LaunchedEffect(uiState.authRecoveryIntent) {
        uiState.authRecoveryIntent?.let { intent ->
            driveAccountLauncher.launch(intent)
            viewModel.consumeAuthRecoveryIntent()
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

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = if (uiState.isFirstRun) "welcome" else Screen.Main.route
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
                    onBack    = { navController.popBackStack() },
                    onNavigateToDsp = { navController.navigate(Screen.Dsp.route) },
                    onRequestGDriveAccount = {
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
                        towards = AnimatedContentTransitionScope.SlideDirection.Up,
                        animationSpec = tween(400, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(400))
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Down,
                        animationSpec = tween(400, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(400))
                }
            ) {
                DownloadScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }
        }
    }
}
