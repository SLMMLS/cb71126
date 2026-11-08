package com.clipsync.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log

private const val TAG = "ClipSync"

/**
 * Calls [onChange] when the phone's default network comes up or its addresses change - the moments
 * at which the PC's idea of this phone's address goes stale (Wi-Fi reconnect, new DHCP lease,
 * switching between networks). The callback runs on a system thread, so [onChange] must be
 * thread-safe; it is only ever used to ask [HelloScheduler] for a hello, which is.
 *
 * Android also calls [onChange] right after [start] when a network is already up. That is wanted
 * (it is the "receiver started" announcement) and harmless: the scheduler coalesces it.
 */
class NetworkWatcher(context: Context, private val onChange: () -> Unit) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onChange()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            onChange()
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: Exception) {
            // Too many registered callbacks, or the permission was revoked: the hello at start-up
            // and when the app opens still work, so degrade instead of failing the service.
            Log.w(TAG, "network watcher not started: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // already unregistered by the system
        }
    }
}
