package com.ncthanhngo.pollmaster.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** True when the active network can reach the internet (not a guarantee that the ERP answers). */
fun isOnline(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
