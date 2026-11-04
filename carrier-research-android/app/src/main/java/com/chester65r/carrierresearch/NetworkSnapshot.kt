package com.chester65r.carrierresearch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

data class NetworkSnapshot(
    val transport: String,
    val hasInternetCapability: Boolean,
    val validatedByAndroid: Boolean,
    val metered: Boolean?,
    val capturedAt: String,
) {
    fun asLines(): List<String> = listOf(
        "Transport: " + transport,
        "Internet capability: " + yesNo(hasInternetCapability),
        "Android validation: " + yesNo(validatedByAndroid),
        "Metered: " + (metered?.let { yesNo(it) } ?: "Unknown"),
        "Captured at (UTC): " + capturedAt,
    )

    private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"
}

fun readNetworkSnapshot(context: Context): NetworkSnapshot {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val network = manager?.activeNetwork
    val capabilities = if (manager != null && network != null) {
        manager.getNetworkCapabilities(network)
    } else {
        null
    }

    val transport = when {
        capabilities == null -> "Unavailable"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
        else -> "Other"
    }

    return NetworkSnapshot(
        transport = transport,
        hasInternetCapability = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) == true,
        validatedByAndroid = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        ) == true,
        metered = runCatching { manager?.isActiveNetworkMetered }.getOrNull(),
        capturedAt = java.time.Instant.now().toString(),
    )
}

/**
 * Returns Android's current default network only when it is cellular.
 * Turning Wi-Fi off on the test device makes the cellular SIM the default network.
 */
fun activeCellularNetwork(context: Context): Network? {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return null
    val network = manager.activeNetwork ?: return null
    val capabilities = manager.getNetworkCapabilities(network) ?: return null

    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null

    return network
}
