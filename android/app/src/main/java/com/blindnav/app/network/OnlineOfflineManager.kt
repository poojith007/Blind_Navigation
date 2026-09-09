package com.blindnav.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

enum class ConnectionStatus(val label: String, val displayIcon: String) {
    ONLINE("ONLINE", "🟢"),
    OFFLINE("OFFLINE", "🔴"),
    SYNCING("SYNCING", "🔄"),
    DOWNLOADING("DOWNLOADING", "⬇️")
}

class OnlineOfflineManager(
    private val context: Context,
    private val onConnectionStatusChanged: (status: ConnectionStatus, announcedMessage: String?) -> Unit
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentStatus: ConnectionStatus = ConnectionStatus.OFFLINE
        private set

    var isNavigatingActive: Boolean = false

    init {
        currentStatus = if (checkIsOnline()) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE
    }

    fun startMonitoring() {
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    val wasOffline = currentStatus == ConnectionStatus.OFFLINE
                    currentStatus = ConnectionStatus.ONLINE
                    val announcement = if (wasOffline && isNavigatingActive) {
                        "Internet connection restored."
                    } else null
                    onConnectionStatusChanged(currentStatus, announcement)
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    currentStatus = ConnectionStatus.OFFLINE
                    val announcement = if (isNavigatingActive) {
                        "Internet connection lost. Continuing with offline navigation."
                    } else null
                    onConnectionStatusChanged(currentStatus, announcement)
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopMonitoring() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        networkCallback = null
    }

    fun setTemporaryStatus(status: ConnectionStatus) {
        currentStatus = status
        onConnectionStatusChanged(status, null)
    }

    fun checkIsOnline(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
