package com.chester65r.chestervpncompanion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

data class NetworkReadiness(
    val status: String,
    val transport: String,
    val metered: Boolean?,
) {
    fun asLines(): List<String> = listOf(
        "Network status: $status",
        "Connection type: $transport",
        "Metered connection: ${metered?.let { if (it) "Yes" else "No" } ?: "Unknown"}",
    )
}

/**
 * Read-only Android connectivity state. This does not inspect traffic, SIM details,
 * carrier names, destinations, package allowances, or tunnel state.
 */
fun inspectNetworkReadiness(context: Context): NetworkReadiness {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return NetworkReadiness("Connectivity service unavailable", "Unknown", null)

    val network = manager.activeNetwork
        ?: return NetworkReadiness("No active network", "None", null)

    val capabilities = manager.getNetworkCapabilities(network)
        ?: return NetworkReadiness("Network state unavailable", "Unknown", null)

    val transport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
        else -> "Other"
    }

    val status = when {
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
            "Internet connectivity validated by Android"
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
            "Network available; Internet not validated"
        else -> "Connected network without reported Internet capability"
    }

    return NetworkReadiness(
        status = status,
        transport = transport,
        metered = runCatching { manager.isActiveNetworkMetered }.getOrNull(),
    )
}
