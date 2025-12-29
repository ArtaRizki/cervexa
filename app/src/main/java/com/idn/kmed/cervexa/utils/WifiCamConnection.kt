package com.idn.kmed.cervexa.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

object WifiCamConnection {
    @Volatile var network: Network? = null
        private set
    @Volatile var callback: ConnectivityManager.NetworkCallback? = null
        private set

    fun hold(network: Network, callback: ConnectivityManager.NetworkCallback) {
        this.network = network
        this.callback = callback
    }

    fun release(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }
}
