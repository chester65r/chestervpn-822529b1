/**
 * Self-contained, all-in-one Android VPN module (`AllInOneHetznerVpn.kt`)
 * merging Hetzner CX23 (`ahmed-docker-manager` #166592617, `2.29.8.194`,
 * `static.194.8.29.2.clients.your-server.de`) presets, `UnifiedTunnelStateHolder`,
 * `UnifiedReconnectionMonitor` (exponential backoff), `UnifiedKeystoreStore`
 * (Android Keystore AES-256-GCM), and `UnifiedHetznerVpnService` into a single file.
 */
export const ALL_IN_ONE_ANDROID_KOTLIN = `package com.example.advancedtunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min
import kotlin.random.Random

/**
 * ALL-IN-ONE HETZNER VPN ENGINE FOR ANDROID
 * =========================================
 * Pre-configured for Hetzner Cloud CX23:
 * - Server Name: ahmed-docker-manager (#166592617)
 * - Location:    Helsinki, Finland (eu-central)
 * - IPv4:        2.29.8.194
 * - Reverse DNS: static.194.8.29.2.clients.your-server.de
 * - IPv6 Subnet: 2a01:4f9:c015:48b3::/64
 */
object HetznerServerConfig {
    const val SERVER_NAME = "ahmed-docker-manager"
    const val SERVER_ID = "166592617"
    const val PROJECT_ID = "16098183"
    const val IPV4 = "2.29.8.194"
    const val REVERSE_DNS = "static.194.8.29.2.clients.your-server.de"
    const val IPV6_SUBNET = "2a01:4f9:c015:48b3::/64"
    const val LOCATION = "Helsinki, Finland (eu-central)"
    const val TLS_PORT = 443
    const val WIREGUARD_PORT = 51820

    data class CarrierPreset(
        val id: String,
        val title: String,
        val host: String,
        val port: Int,
        val sni: String,
        val mtu: Int,
        val keepAliveSec: Long,
        val payloadTemplate: String
    ) {
        fun buildHttpUpgradePayload(): String {
            return payloadTemplate
                .replace("[host]", sni)
                .replace("[crlf]", "\\r\\n")
                .trimEnd() + "\\r\\n\\r\\n"
        }
    }

    val PRESETS: List<CarrierPreset> = listOf(
        CarrierPreset(
            id = "hetzner-tls-direct",
            title = "Direct TLS 1.3 (Port 443)",
            host = IPV4,
            port = TLS_PORT,
            sni = REVERSE_DNS,
            mtu = 1400,
            keepAliveSec = 20L,
            payloadTemplate = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]User-Agent: TunnelConsole/2.0[crlf]X-Tunnel-Profile: hetzner-tls-direct[crlf]"
        ),
        CarrierPreset(
            id = "telecom-mobile-cgnat",
            title = "Mobile 4G/5G CGNAT (MTU 1280)",
            host = IPV4,
            port = TLS_PORT,
            sni = IPV4,
            mtu = 1280,
            keepAliveSec = 15L,
            payloadTemplate = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade, Keep-Alive[crlf]Upgrade: websocket[crlf]X-Tunnel-MTU: 1280[crlf]X-Tunnel-Profile: telecom-mobile-cgnat[crlf]"
        ),
        CarrierPreset(
            id = "enterprise-firewall-443",
            title = "Enterprise Firewall (rDNS SNI)",
            host = IPV4,
            port = TLS_PORT,
            sni = REVERSE_DNS,
            mtu = 1360,
            keepAliveSec = 20L,
            payloadTemplate = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]X-Tunnel-Profile: enterprise-firewall-443[crlf]"
        ),
        CarrierPreset(
            id = "high-throughput-wg",
            title = "WireGuard Native UDP (51820)",
            host = IPV4,
            port = WIREGUARD_PORT,
            sni = IPV4,
            mtu = 1420,
            keepAliveSec = 25L,
            payloadTemplate = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]X-Tunnel-Mode: wireguard-udp-51820[crlf]X-Tunnel-Profile: high-throughput-wg[crlf]"
        )
    )
}

sealed class UnifiedConnectionState {
    object Disconnected : UnifiedConnectionState()
    object Connecting : UnifiedConnectionState()
    object Authenticating : UnifiedConnectionState()
    object TunnelEstablished : UnifiedConnectionState()
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long, val reason: String) : UnifiedConnectionState()
    data class Error(val message: String) : UnifiedConnectionState()
}

object UnifiedTunnelStateHolder {
    private val _state = MutableStateFlow<UnifiedConnectionState>(UnifiedConnectionState.Disconnected)
    val state: StateFlow<UnifiedConnectionState> = _state.asStateFlow()

    private val _bytesTransferred = MutableStateFlow(0L)
    val bytesTransferred: StateFlow<Long> = _bytesTransferred.asStateFlow()

    private val _activePreset = MutableStateFlow(HetznerServerConfig.PRESETS.first())
    val activePreset: StateFlow<HetznerServerConfig.CarrierPreset> = _activePreset.asStateFlow()

    fun updateState(newState: UnifiedConnectionState) { _state.value = newState }
    fun selectPreset(preset: HetznerServerConfig.CarrierPreset) { _activePreset.value = preset }
    fun addBytes(bytes: Long) { if (bytes > 0) _bytesTransferred.value += bytes }
    fun resetTraffic() { _bytesTransferred.value = 0L }
}

class UnifiedReconnectionMonitor(
    private val scope: CoroutineScope,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val maxAttempts: Int = 8,
    private val isTunnelUp: () -> Boolean,
    private val reconnectAction: suspend (attempt: Int) -> Boolean
) {
    private var monitorJob: Job? = null
    private val active = AtomicBoolean(false)

    fun triggerReconnect(reason: String) {
        if (!active.compareAndSet(false, true)) return
        monitorJob?.cancel()
        monitorJob = scope.launch {
            try {
                var attempt = 1
                while (isActive && attempt <= maxAttempts) {
                    if (isTunnelUp()) {
                        UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.TunnelEstablished)
                        return@launch
                    }
                    val delayMs = computeBackoffMs(attempt)
                    UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.Reconnecting(attempt, delayMs, reason))
                    delay(delayMs)
                    if (!isActive || isTunnelUp()) return@launch
                    val ok = try { reconnectAction(attempt) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (ok || isTunnelUp()) {
                        UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.TunnelEstablished)
                        return@launch
                    }
                    attempt++
                }
                if (isActive && !isTunnelUp()) {
                    UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.Error("Failed after $maxAttempts attempts ($reason)"))
                }
            } finally {
                active.set(false)
            }
        }
    }

    fun computeBackoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 10)
        val exp = min(maxDelayMs, baseDelayMs * (1L shl shift))
        val jitter = Random.nextLong(0L, (exp / 5L).coerceAtLeast(1L))
        return min(maxDelayMs, exp + jitter)
    }

    fun stop() {
        active.set(false)
        monitorJob?.cancel()
        monitorJob = null
    }
}

class UnifiedKeystoreStore(context: Context) {
    private val appContext = context.applicationContext
    private val keyAlias = "tunnel_console_wg_aes_key"
    private val aad = "wireguard-config-v1".toByteArray(StandardCharsets.UTF_8)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun slotFile(slot: String) =
        AtomicFile(File(appContext.noBackupFilesDir, "wg_\${slot.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.bin"))

    fun write(raw: String, slot: String = "active") {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        val ct = cipher.doFinal(raw.toByteArray(StandardCharsets.UTF_8))
        val af = slotFile(slot)
        var out: FileOutputStream? = null
        try {
            out = af.startWrite()
            out.write(iv.size); out.write(iv); out.write(ct)
            af.finishWrite(out)
        } catch (e: Exception) {
            if (out != null) af.failWrite(out)
            throw e
        }
    }

    fun read(slot: String = "active"): String? {
        val af = slotFile(slot)
        if (!af.baseFile.exists()) return null
        return try {
            val bytes = af.readFully()
            val ivLen = bytes[0].toInt() and 0xff
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val ct = bytes.copyOfRange(1 + ivLen, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            af.delete()
            null
        }
    }
}

class UnifiedHetznerVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var sslSocket: SSLSocket? = null
    private val running = AtomicBoolean(false)

    private val reconnectionMonitor = UnifiedReconnectionMonitor(
        scope = serviceScope,
        isTunnelUp = { running.get() && vpnInterface != null && sslSocket?.isConnected == true && sslSocket?.isClosed == false },
        reconnectAction = { attempt -> establishHetznerTunnel(attempt > 0) }
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            reconnectionMonitor.stop()
            tearDownSocketAndTun()
            UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.Disconnected)
            stopSelf()
            return START_NOT_STICKY
        }
        serviceScope.launch {
            if (!establishHetznerTunnel(isRetry = false)) {
                reconnectionMonitor.triggerReconnect("Initial handshake failed")
            }
        }
        return START_STICKY
    }

    private suspend fun establishHetznerTunnel(isRetry: Boolean): Boolean = withContext(Dispatchers.IO) {
        val preset = UnifiedTunnelStateHolder.activePreset.value
        try {
            tearDownSocketAndTun()
            if (!isRetry) {
                UnifiedTunnelStateHolder.resetTraffic()
                UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.Connecting)
            }
            val rawSocket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            protect(rawSocket)
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            rawSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && preset.sni.isNotBlank()) {
                val params: SSLParameters = rawSocket.sslParameters
                params.serverNames = listOf(SNIHostName(preset.sni))
                rawSocket.sslParameters = params
            }
            rawSocket.connect(InetSocketAddress(preset.host, preset.port), 10_000)
            rawSocket.soTimeout = 15_000
            UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.Authenticating)
            rawSocket.startHandshake()

            val out = rawSocket.outputStream
            out.write(preset.buildHttpUpgradePayload().toByteArray(StandardCharsets.UTF_8))
            out.flush()

            val reader = BufferedReader(InputStreamReader(rawSocket.inputStream, StandardCharsets.UTF_8))
            val statusLine = reader.readLine() ?: throw IOException("Empty response")
            if (!statusLine.contains("101") && !statusLine.contains("200")) {
                throw IOException("Rejected by relay: $statusLine")
            }
            rawSocket.soTimeout = 0
            sslSocket = rawSocket

            vpnInterface = Builder()
                .setSession("\${HetznerServerConfig.SERVER_NAME} (\${preset.title})")
                .setMtu(preset.mtu)
                .addAddress("10.8.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .establish() ?: throw IOException("TUN establish failed")

            running.set(true)
            UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.TunnelEstablished)
            startPacketPump()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error: \${e.message}", e)
            tearDownSocketAndTun()
            false
        }
    }

    private fun startPacketPump() {
        val pfd = vpnInterface ?: return
        val sock = sslSocket ?: return
        serviceScope.launch {
            val tunIn = FileInputStream(pfd.fileDescriptor)
            val tunOut = FileOutputStream(pfd.fileDescriptor)
            val netIn = sock.inputStream
            val netOut = sock.outputStream
            try {
                val up = launch {
                    val buf = ByteArray(16_384)
                    while (isActive && running.get()) {
                        val n = tunIn.read(buf)
                        if (n > 0) { netOut.write(buf, 0, n); netOut.flush(); UnifiedTunnelStateHolder.addBytes(n.toLong()) }
                        else if (n < 0) throw IOException("TUN EOF")
                    }
                }
                val down = launch {
                    val buf = ByteArray(16_384)
                    while (isActive && running.get()) {
                        val n = netIn.read(buf)
                        if (n > 0) { tunOut.write(buf, 0, n); UnifiedTunnelStateHolder.addBytes(n.toLong()) }
                        else if (n < 0) throw IOException("Relay socket closed")
                    }
                }
                up.join(); down.join()
            } catch (e: IOException) {
                if (running.get()) reconnectionMonitor.triggerReconnect(e.message ?: "Socket drop")
            }
        }
    }

    private fun tearDownSocketAndTun() {
        running.set(false)
        try { sslSocket?.close() } catch (_: Exception) {}
        sslSocket = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    override fun onDestroy() {
        reconnectionMonitor.stop()
        tearDownSocketAndTun()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UnifiedHetznerVpn"
        const val ACTION_CONNECT = "com.example.advancedtunnel.UNIFIED_CONNECT"
        const val ACTION_DISCONNECT = "com.example.advancedtunnel.UNIFIED_DISCONNECT"
    }
}
`;
