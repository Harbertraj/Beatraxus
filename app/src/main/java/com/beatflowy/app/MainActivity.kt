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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.beatflowy.app.service.AudioPlaybackService
import com.beatflowy.app.ui.screens.EqualizerScreen
import com.beatflowy.app.ui.screens.MainScreen
import com.beatflowy.app.ui.screens.SettingsScreen
import com.beatflowy.app.ui.screens.WelcomeScreen
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
        if (isFirstCreate) {
            window.decorView.post { checkAndRequestPermissions() }
            isFirstCreate = false
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

    fun requestPermissions(onAlreadyGranted: () -> Unit = {}) {
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
            onAlreadyGranted()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun checkAndRequestPermissions() {
        // Only auto-request if not first run, to avoid interrupting welcome screen
        val prefs = getSharedPreferences("beatraxus", MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("first_run", true)
        if (isFirstRun) return

        val essentialPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val essentialGranted = ContextCompat.checkSelfPermission(this, essentialPermission) == PackageManager.PERMISSION_GRANTED
        
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
    object Equalizer : Screen("equalizer")
    object Settings  : Screen("settings")
}

@Composable
fun BeatraxusApp(
    viewModel: PlayerViewModel,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current

    // Observe navigation backstack to determine the current screen
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

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
                onNavigateToSettings  = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(
            Screen.Equalizer.route,
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
            EqualizerScreen(
                viewModel = viewModel,
                onBack    = { navController.popBackStack() }
            )
        }
        composable(
            Screen.Settings.route,
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
            SettingsScreen(
                viewModel = viewModel,
                onBack    = { navController.popBackStack() }
            )
        }
    }
}
