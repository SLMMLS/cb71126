package com.clipsync.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clipsync.app.data.PairedPc
import com.clipsync.app.data.Repository
import com.clipsync.app.service.ReceiverControl
import com.clipsync.app.service.ReceiverService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "The database hasn't answered yet" must not look like "no PC is paired".
 * The old code used `null` for both, so on every cold start the NavHost was
 * built with startDestination = "pairing" a few milliseconds before Room
 * delivered the real value - and NavHost ignores later changes to its start
 * destination, so a paired phone landed on the QR scanner every launch.
 */
private sealed interface PairedState {
    data object Loading : PairedState
    data class Known(val pc: PairedPc?) : PairedState
}

class MainActivity : ComponentActivity() {
    companion object {
        /** Set by the file notifications: which screen to open on top of the home screen. */
        const val EXTRA_ROUTE = "route"
        const val ROUTE_FILES = "files"
    }

    private val repository by lazy { Repository(applicationContext) }

    /**
     * The phone's clipboard cannot be watched in the background (Android 10+), but it can be read
     * while this window has focus - so every time ClipSync comes to the front, whatever is on the
     * clipboard is added to the history. It is the only way to build a history of what was copied
     * on the phone, and it has to happen here, not in onResume: the focus check is on the window,
     * and the window only has focus after onResume.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && repository.autoCapture) {
            lifecycleScope.launch { repository.captureClipboard() }
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fine either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ClipSyncTheme {
                val pairedFlow = remember { repository.pairedPc.map<PairedPc?, PairedState> { PairedState.Known(it) } }
                val state by pairedFlow.collectAsState(initial = PairedState.Loading)

                when (val current = state) {
                    PairedState.Loading -> Unit // a blank frame or two while Room answers
                    is PairedState.Known -> {
                        val pc = current.pc

                        // Keep the receiver alive whenever a PC is paired, not just
                        // while this screen happens to be open - and stop it once
                        // the PC is unpaired, so no listener outlives its purpose.
                        LaunchedEffect(pc?.deviceId) {
                            val serviceIntent = Intent(this@MainActivity, ReceiverService::class.java)
                            if (pc != null) {
                                // The switch "Приём" can be off: then the receiver stays off until
                                // the user flips it back (see ReceiverControl).
                                if (ReceiverControl.shouldAutoStart(this@MainActivity)) {
                                    try {
                                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                    } catch (e: Exception) {
                                        Log.w("ClipSync", "could not start ReceiverService", e)
                                    }
                                }
                            } else {
                                stopService(serviceIntent)
                            }
                        }

                        val navController = rememberNavController()

                        // A tap on a file notification opens the Files screen straight away.
                        LaunchedEffect(pc?.deviceId) {
                            if (pc != null && intent?.getStringExtra(EXTRA_ROUTE) == ROUTE_FILES) {
                                navController.navigate("files")
                                // Used once: turning the phone must not open the screen again after the person left it.
                                intent?.removeExtra(EXTRA_ROUTE)
                            }
                        }

                        NavHost(navController, startDestination = if (pc == null) "pairing" else "home") {
                            composable("home") {
                                HomeScreen(
                                    repository = repository,
                                    onSettings = { navController.navigate("settings") },
                                    onOpenEntry = { entryId -> navController.navigate("entry/$entryId") },
                                    onFiles = { navController.navigate("files") },
                                )
                            }
                            composable("files") {
                                FilesScreen(onBack = { navController.popBackStack() })
                            }
                            composable("entry/{id}") { backStackEntry ->
                                EntryScreen(
                                    repository = repository,
                                    id = backStackEntry.arguments?.getString("id").orEmpty(),
                                    onBack = { navController.popBackStack() },
                                )
                            }
                            composable("pairing") {
                                PairingScreen(
                                    repository = repository,
                                    onPaired = {
                                        navController.navigate("home") { popUpTo("pairing") { inclusive = true } }
                                    },
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    repository = repository,
                                    onBack = { navController.popBackStack() },
                                    onUnpaired = {
                                        navController.navigate("pairing") { popUpTo("home") { inclusive = true } }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
