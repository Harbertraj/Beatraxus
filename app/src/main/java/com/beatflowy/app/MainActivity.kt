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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import com.beatflowy.app.ui.theme.BeatraxusTheme
import com.beatflowy.app.viewmodel.PlayerViewModel
import com.beatflowy.app.viewmodel.PlayerViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModelFactory(application)
    }

    private var serviceBound = false
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
        if (results.values.all { it }) {
            Log.d("MainActivity", "Permissions granted, loading library")
            viewModel.loadLibrary()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    override fun onStart() {
        super.onStart()
        startAudioService()
        bindAudioService()
    }

    private var isFirstCreate = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
            checkAndRequestPermissions()
            isFirstCreate = false
        }
    }

    override fun onStop() {
        super.onStop()
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
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
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

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.loadLibrary()
        }
        else permissionLauncher.launch(permissions.toTypedArray())
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
        enterTransition  = {
            fadeIn(tween(500))
        },
        exitTransition   = {
            fadeOut(tween(500))
        }
    ) {
        composable(
            "welcome",
            exitTransition = {
                fadeOut(tween(700)) + scaleOut(targetScale = 0.8f, animationSpec = tween(700))
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
                fadeIn(tween(700)) + scaleIn(initialScale = 1.2f, animationSpec = tween(700))
            }
        ) {
            MainScreen(
                viewModel            = viewModel,
                onNavigateToSettings  = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Equalizer.route) {
            EqualizerScreen(
                viewModel = viewModel,
                onBack    = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = viewModel,
                onBack    = { navController.popBackStack() }
            )
        }
    }
}
