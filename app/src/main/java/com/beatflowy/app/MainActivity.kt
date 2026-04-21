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
                BeatraxusApp(viewModel = viewModel)
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

    private fun checkAndRequestPermissions() {
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
fun BeatraxusApp(viewModel: PlayerViewModel) {
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
        startDestination = Screen.Main.route,
        enterTransition  = {
            if (targetState.destination.route == Screen.Equalizer.route) {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Up,
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            } else {
                fadeIn(tween(320))
            }
        },
        exitTransition   = {
            fadeOut(tween(320))
        },
        popEnterTransition = {
            fadeIn(tween(320))
        },
        popExitTransition  = {
            if (initialState.destination.route == Screen.Equalizer.route) {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Down,
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            } else {
                fadeOut(tween(320))
            }
        }
    ) {
        composable(Screen.Main.route) {
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
