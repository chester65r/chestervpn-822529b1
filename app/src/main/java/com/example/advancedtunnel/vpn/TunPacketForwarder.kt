package com.example.advancedtunnel.vpn

import android.content.Context
import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import java.io.IOException

object TunPacketForwarder {

    private const val TAG = "SingBoxCoreRunner"

    data class RunningSession(
        val boxService: BoxService,
        val platformBridge: LibboxPlatformBridge,
        val configJson: String
    )

    @Throws(IOException::class)
    fun validateConfigJson(context: Context, configJson: String) {
        try {
            LibboxPlatformBridge.ensureCoreInitialized(context)
            Libbox.checkConfig(configJson)
        } catch (err: Exception) {
            throw IOException("sing-box config validation failed: ${err.message}", err)
        }
    }

    @Throws(IOException::class)
    fun startSingBoxCore(
        vpnService: VpnService,
        context: Context,
        clientConfig: SingBoxConfigBuilder.ClientConfig,
        overrideConfigJson: String? = null,
        onCoreLog: (String) -> Unit = { Log.i(TAG, it) },
        onNotification: (title: String, body: String) -> Unit = { _, _ -> }
    ): RunningSession {
        try {
            LibboxPlatformBridge.ensureCoreInitialized(context)
            val configJson = if (!overrideConfigJson.isNullOrBlank()) {
                overrideConfigJson
            } else {
                SingBoxConfigBuilder.buildClientConfigJson(clientConfig)
            }
            Libbox.checkConfig(configJson)

            val bridge = LibboxPlatformBridge(
                vpnService = vpnService,
                context = context,
                onCoreLog = onCoreLog,
                onNotification = onNotification
            )
            val service = Libbox.newService(configJson, bridge)
                ?: throw IOException("Libbox.newService returned null")
            service.start()

            return RunningSession(
                boxService = service,
                platformBridge = bridge,
                configJson = configJson
            )
        } catch (err: Throwable) {
            if (err is IOException) throw err
            throw IOException("Failed to start libbox sing-box core: ${err.message}", err)
        }
    }

    fun stopSingBoxCore(session: RunningSession?) {
        if (session == null) return
        try {
            session.boxService.close()
        } catch (err: Exception) {
            Log.w(TAG, "Error closing BoxService: ${err.message}")
        } finally {
            session.platformBridge.closeTunDescriptor()
        }
    }
}
