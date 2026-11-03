package com.chester65r.chestervpncompanion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class DeviceDiagnostics(
    val androidRelease: String,
    val apiLevel: Int,
    val appVersion: String,
)

@Suppress("DEPRECATION")
fun collectDeviceDiagnostics(context: Context): DeviceDiagnostics {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0L),
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    return DeviceDiagnostics(
        androidRelease = Build.VERSION.RELEASE ?: "Unknown",
        apiLevel = Build.VERSION.SDK_INT,
        appVersion = packageInfo.versionName ?: "Unknown",
    )
}

fun formatDeviceDiagnostics(info: DeviceDiagnostics): String = buildString {
    appendLine("ChesterVPN Companion — local diagnostics")
    appendLine("Android release: ${info.androidRelease}")
    appendLine("Android API level: ${info.apiLevel}")
    appendLine("App version: ${info.appVersion}")
    appendLine()
    appendLine("Privacy: generated locally on this device.")
    appendLine("No device identifier, SIM data, carrier details, or network traffic is collected.")
}
