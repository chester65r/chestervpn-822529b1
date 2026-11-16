package com.example.advancedtunnel.vpn

import android.content.Context
import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import java.io.IOException

/**
 * Lifecycle wrapper for the real native Go `sing-box` core (`io.nekohasekai.libbox`).
 *
 * Replaces all manual Layer-3 TUN stream loops with `Libbox.newService(configJson, platformBridge)`.
 * `libbox` creates the TUN via [LibboxPlatformBridge.openTun], terminates TCP/UDP packets in
 * userspace gVisor, protects outbound sockets via `VpnService.protect(fd)`, and multiplexes traffic
 * over VLESS+Reality (`xtls-rprx-vision`) or Trojan over TLS 443 with uTLS (Chrome/iOS) and
 * ClientHello record fragmentation.
 */
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
        onCoreLog: (String) -> Unit = { Log.i(TAG, it) },
        onNotification: (title: String, body: String) -> Unit = { _, _ -> }
    ): RunningSession {
        try {
            LibboxPlatformBridge.ensureCoreInitialized(context)
            val configJson = SingBoxConfigBuilder.buildClientConfigJson(clientConfig)
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
