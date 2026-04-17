package com.beatflowy.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.beatflowy.app.service.AudioPlaybackService
import com.beatflowy.app.ui.screens.EqualizerScreen
import com.beatflowy.app.ui.screens.MainScreen
import com.beatflowy.app.ui.theme.BeatflowyTheme
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
        if (results.values.all { it }) viewModel.loadLibrary()
        else viewModel.onPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BeatflowyTheme {
                BeatflowyApp(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startAudioService()
        bindAudioService()
        checkAndRequestPermissions()
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
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) viewModel.loadLibrary()
        else permissionLauncher.launch(permissions)
    }
}

sealed class Screen(val route: String) {
    object Main      : Screen("main")
    object Equalizer : Screen("equalizer")
}

@Composable
fun BeatflowyApp(viewModel: PlayerViewModel) {
    val navController = rememberNavController()
    NavHost(
        navController    = navController,
        startDestination = Screen.Main.route,
        enterTransition  = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(320))
        },
        exitTransition   = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(320))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Down, tween(320))
        },
        popExitTransition  = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, tween(320))
        }
    ) {
        composable(Screen.Main.route) {
            MainScreen(
                viewModel            = viewModel,
                onNavigateToEqualizer = { navController.navigate(Screen.Equalizer.route) }
            )
        }
        composable(Screen.Equalizer.route) {
            EqualizerScreen(
                viewModel = viewModel,
                onBack    = { navController.popBackStack() }
            )
        }
    }
}
