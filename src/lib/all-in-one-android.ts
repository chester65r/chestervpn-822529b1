/**
 * Self-contained, production Android VPN engine (AllInOneHetznerVpn.kt) backed by real
 * native Go sing-box / libbox (io.nekohasekai.libbox.*):
 * - Hetzner CX23 (ahmed-docker-manager #166592617, 2.29.8.194, rDNS static.194.8.29.2.clients.your-server.de)
 * - gVisor TUN inbound (172.19.0.1/30, MTU 1500, auto_route, strict_route, sniff)
 * - VLESS + xtls-rprx-vision + Reality (port 443) or Trojan over TLS (port 8443)
 * - Native uTLS browser fingerprinting (chrome, ios, firefox, safari, randomized)
 * - Native TLS ClientHello fragmentation (fragment: true, fragment_fallback_delay: "500ms", record_fragment: true)
 * - UnifiedTunnelStateHolder, UnifiedReconnectionMonitor, and UnifiedKeystoreStore (AES-256-GCM + AtomicFile)
 */
export const ALL_IN_ONE_ANDROID_KOTLIN = `package com.example.advancedtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min
import kotlin.random.Random

/**
 * ALL-IN-ONE HETZNER VPN ENGINE FOR ANDROID (sing-box / libbox AAR + gVisor TUN + Anti-DPI)
 * ==========================================================================================
 * Architecture:
 *   OS Apps -> tun0 (172.19.0.1/30, MTU 1500, auto_route, strict_route)
 *           -> UnifiedLibboxBridge (io.nekohasekai.libbox.PlatformInterface + VpnService.protect)
 *           -> Go gVisor userspace TCP/IP stack + DNS hijack
 *           -> VLESS + xtls-rprx-vision + Reality (443) or Trojan over TLS (8443)
 *           -> uTLS (chrome/ios) + TLS ClientHello fragmentation (fragment + record_fragment)
 *           -> Hetzner CX23 sing-box Server (2.29.8.194)
 */
object HetznerServerConfig {
    const val SERVER_NAME = "ahmed-docker-manager"
    const val SERVER_ID = "166592617"
    const val PROJECT_ID = "16098183"
    const val IPV4 = "2.29.8.194"
    const val REVERSE_DNS = "static.194.8.29.2.clients.your-server.de"
    const val IPV6_SUBNET = "2a01:4f9:c015:48b3::/64"
    const val LOCATION = "Helsinki, Finland (eu-central)"
    const val VLESS_PORT = 443
    const val TROJAN_PORT = 8443
    const val WIREGUARD_PORT = 51820

    const val TUN_CIDR = "172.19.0.1/30"
    const val TUN_MTU = 1500
    const val TUN_DNS = "1.1.1.1"
    const val DEFAULT_REALITY_SNI = "www.stc.com.sa"
    const val DEFAULT_MOBILY_SNI = "www.mobily.com.sa"
    const val DEFAULT_MOBILY_CDN_HOST = "cdn.mobily.com.sa"
    const val DEFAULT_MOBILY_WS_PATH = "/cdn-ws"

    data class SingBoxProfile(
        val id: String,
        val title: String,
        val protocol: String,
        val host: String,
        val port: Int,
        val sni: String,
        val utlsFingerprint: String,
        val mtu: Int,
        val wsHost: String = "",
        val wsPath: String = "/cdn-ws",
        val sniSplitMinBytes: Int = 5,
        val sniSplitMaxBytes: Int = 15,
        val uuid: String = "",
        val realityPublicKey: String = "",
        val realityShortId: String = "",
        val trojanPassword: String = "",
        val fragment: Boolean = true,
        val recordFragment: Boolean = true
    )

    val PRESETS: List<SingBoxProfile> = listOf(
        SingBoxProfile(
            id = "stc-ksa-5g-cgnat",
            title = "STC KSA 5G/4G (VLESS + Reality · 5-15B SNI Split)",
            protocol = "vless-reality",
            host = IPV4,
            port = VLESS_PORT,
            sni = DEFAULT_REALITY_SNI,
            utlsFingerprint = "chrome",
            mtu = 1280,
            sniSplitMinBytes = 5,
            sniSplitMaxBytes = 15
        ),
        SingBoxProfile(
            id = "mobily-ksa-lte-bypass",
            title = "Mobily KSA 4G/5G (VLESS + WSS CDN · uTLS Chrome JA4)",
            protocol = "vless-ws-tls",
            host = IPV4,
            port = VLESS_PORT,
            sni = DEFAULT_MOBILY_SNI,
            wsHost = DEFAULT_MOBILY_CDN_HOST,
            wsPath = DEFAULT_MOBILY_WS_PATH,
            utlsFingerprint = "chrome",
            mtu = 1280
        ),
        SingBoxProfile(
            id = "hetzner-trojan-tls-8443",
            title = "Trojan + TLS Fragment (Port 8443 · uTLS Chrome)",
            protocol = "trojan-tls",
            host = IPV4,
            port = TROJAN_PORT,
            sni = REVERSE_DNS,
            utlsFingerprint = "chrome",
            mtu = TUN_MTU
        )
    )
}

object UnifiedSingBoxConfigBuilder {
    fun buildClientConfigJson(
        profile: HetznerServerConfig.SingBoxProfile,
        excludePackages: List<String> = emptyList()
    ): String {
        val tlsObj = JSONObject().apply {
            put("enabled", true)
            put("server_name", profile.sni)
            put("fragment", profile.fragment)
            put("fragment_fallback_delay", "500ms")
            put("record_fragment", profile.recordFragment)
            put("utls", JSONObject().apply {
                put("enabled", true)
                put("fingerprint", profile.utlsFingerprint)
            })
            if (profile.protocol == "vless-reality") {
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", profile.realityPublicKey)
                    put("short_id", profile.realityShortId)
                })
            } else {
                put("alpn", JSONArray().apply {
                    if (profile.protocol == "vless-ws-tls") {
                        put("http/1.1")
                    } else {
                        put("h2")
                        put("http/1.1")
                    }
                })
            }
        }

        val proxyOutbound = when (profile.protocol) {
            "vless-reality" -> JSONObject().apply {
                put("type", "vless")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                put("flow", "xtls-rprx-vision")
                put("packet_encoding", "xudp")
                put("tls", tlsObj)
            }
            "vless-ws-tls" -> JSONObject().apply {
                put("type", "vless")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("uuid", profile.uuid)
                put("packet_encoding", "xudp")
                put("tls", tlsObj)
                put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", profile.wsPath.ifBlank { "/cdn-ws" })
                    put("headers", JSONObject().apply {
                        put("Host", profile.wsHost.ifBlank { profile.sni })
                    })
                    put("max_early_data", 2048)
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
                })
            }
            else -> JSONObject().apply {
                put("type", "trojan")
                put("tag", "proxy")
                put("server", profile.host)
                put("server_port", profile.port)
                put("password", profile.trojanPassword)
                put("tls", tlsObj)
            }
        }

        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("address", JSONArray().apply { put(HetznerServerConfig.TUN_CIDR) })
            put("mtu", profile.mtu.coerceIn(1280, 1500))
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "gvisor")
            put("sniff", true)
            if (excludePackages.isNotEmpty()) {
                put("exclude_package", JSONArray(excludePackages))
            }
        }

        val root = JSONObject().apply {
            put("log", JSONObject().apply {
                put("disabled", false)
                put("level", "info")
                put("timestamp", true)
            })
            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "remote-dns")
                        put("type", "udp")
                        put("server", HetznerServerConfig.TUN_DNS)
                        put("detour", "proxy")
                    })
                    put(JSONObject().apply {
                        put("tag", "local-dns")
                        put("type", "local")
                    })
                })
                put("final", "remote-dns")
            })
            put("inbounds", JSONArray().apply { put(tunInbound) })
            put("outbounds", JSONArray().apply {
                put(proxyOutbound)
                put(JSONObject().apply {
                    put("type", "direct")
                    put("tag", "direct")
                })
            })
            put("route", JSONObject().apply {
                put("rules", JSONArray().apply {
                    put(JSONObject().apply { put("action", "sniff") })
                    put(JSONObject().apply {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    })
                    put(JSONObject().apply {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    })
                })
                put("final", "proxy")
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local-dns")
            })
        }
        return root.toString(2)
    }
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

    private val _activePreset = MutableStateFlow(HetznerServerConfig.PRESETS.first())
    val activePreset: StateFlow<HetznerServerConfig.SingBoxProfile> = _activePreset.asStateFlow()

    fun updateState(newState: UnifiedConnectionState) {
        _state.value = newState
    }

    fun selectPreset(preset: HetznerServerConfig.SingBoxProfile) {
        _activePreset.value = preset
    }
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
                    UnifiedTunnelStateHolder.updateState(
                        UnifiedConnectionState.Reconnecting(attempt, delayMs, reason)
                    )
                    delay(delayMs)
                    if (!isActive || isTunnelUp()) return@launch

                    val ok = try {
                        reconnectAction(attempt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    if (ok || isTunnelUp()) {
                        UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.TunnelEstablished)
                        return@launch
                    }
                    attempt++
                }
                if (isActive && !isTunnelUp()) {
                    UnifiedTunnelStateHolder.updateState(
                        UnifiedConnectionState.Error("Reconnection failed after $maxAttempts attempts ($reason)")
                    )
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
    private val keyAlias = "tunnel_console_singbox_aes_key"
    private val aad = "singbox-profile-v1".toByteArray(StandardCharsets.UTF_8)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun slotFile(slot: String) =
        AtomicFile(File(appContext.noBackupFilesDir, "sb_\${slot.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.bin"))

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
            out.write(iv.size)
            out.write(iv)
            out.write(ct)
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

    fun clear(slot: String = "active") = slotFile(slot).delete()
}

class UnifiedLibboxBridge(
    private val vpnService: VpnService,
    private val context: Context
) : PlatformInterface {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val packageManager = context.packageManager
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var tunDescriptor: ParcelFileDescriptor? = null
        private set

    fun closeTun() {
        try {
            tunDescriptor?.close()
        } catch (_: Exception) {
        } finally {
            tunDescriptor = null
        }
    }

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(context) != null) error("VPN permission not granted")
        val builder = vpnService.Builder()
            .setSession("\${HetznerServerConfig.SERVER_NAME} (sing-box)")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val addrs4 = options.inet4Address
        while (addrs4.hasNext()) {
            val p = addrs4.next()
            builder.addAddress(p.address(), p.prefix())
        }
        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress.value)
            builder.addRoute("0.0.0.0", 0)
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                runCatching { builder.addDisallowedApplication(exclude.next()) }
            }
        }
        runCatching { builder.addDisallowedApplication(context.packageName) }

        val pfd = builder.establish() ?: error("VpnService.Builder.establish() returned null")
        tunDescriptor = pfd
        return pfd.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) { vpnService.protect(fd) }
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uid = connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            )
            owner.userId = uid
            if (uid != Process.INVALID_UID) {
                packageManager.getPackagesForUid(uid)?.firstOrNull()?.let { owner.androidPackageName = it }
            }
        }
        return owner
    }

    override fun packageNameByUid(uid: Int): String =
        packageManager.getPackagesForUid(uid)?.firstOrNull() ?: error("Unknown uid $uid")

    override fun uidByPackageName(packageName: String): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageUid(packageName, 0)
        }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notify(network, connectivity.getLinkProperties(network))
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = notify(network, lp)
            override fun onLost(network: Network) = listener.updateDefaultInterface("", -1, false, false)

            private fun notify(network: Network, lp: LinkProperties?) {
                val ifName = lp?.interfaceName ?: return
                val caps = connectivity.getNetworkCapabilities(network)
                val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                val constrained = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == false
                val idx = runCatching { java.net.NetworkInterface.getByName(ifName)?.index ?: 0 }.getOrDefault(0)
                listener.updateDefaultInterface(ifName, idx, expensive, constrained)
            }
        }
        defaultNetworkCallback = cb
        runCatching { connectivity.registerNetworkCallback(request, cb) }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        defaultNetworkCallback = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<NetworkInterface>()
        val all = java.net.NetworkInterface.getNetworkInterfaces()
        while (all != null && all.hasMoreElements()) {
            val sys = all.nextElement()
            list.add(NetworkInterface().apply {
                name = sys.name
                index = sys.index
                mtu = runCatching { sys.mtu }.getOrDefault(1500)
                val addrs = sys.interfaceAddresses.map {
                    val ha = it.address.hostAddress ?: ""
                    "\${if (it.address is Inet6Address) ha.substringBefore('%') else ha}/\${it.networkPrefixLength}"
                }
                addresses = UnifiedStringIter(addrs)
                dnsServer = UnifiedStringIter(emptyList())
                flags = (if (sys.isUp) OsConstants.IFF_UP else 0) or
                    (if (sys.isLoopback) OsConstants.IFF_LOOPBACK else 0)
            })
        }
        return object : NetworkInterfaceIterator {
            private var i = 0
            override fun hasNext(): Boolean = i < list.size
            override fun next(): NetworkInterface = list[i++]
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() {}

    override fun readWIFIState(): WIFIState? {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo ?: return null
        return WIFIState(info.ssid?.removeSurrounding("\\"") ?: "", info.bssid ?: "")
    }

    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun systemCertificates(): StringIterator = UnifiedStringIter(emptyList())
    override fun writeLog(message: String) { Log.i("UnifiedLibbox", message) }
    override fun sendNotification(notification: LibboxNotification) {}

    private class UnifiedStringIter(private val items: List<String>) : StringIterator {
        private var idx = 0
        override fun len(): Int = items.size
        override fun hasNext(): Boolean = idx < items.size
        override fun next(): String = items[idx++]
    }
}

class UnifiedHetznerVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var boxService: BoxService? = null
    @Volatile
    private var platformBridge: UnifiedLibboxBridge? = null
    private var connectJob: Job? = null
    private val running = AtomicBoolean(false)

    private val reconnectionMonitor = UnifiedReconnectionMonitor(
        scope = serviceScope,
        isTunnelUp = { running.get() && boxService != null && platformBridge?.tunDescriptor != null },
        reconnectAction = { attempt -> establishHetznerTunnel(attempt > 0) }
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                connectJob?.cancel()
                connectJob = null
                reconnectionMonitor.stop()
                tearDownCore()
                UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.Disconnected)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureForeground()
                connectJob?.cancel()
                connectJob = serviceScope.launch {
                    val ok = establishHetznerTunnel(isRetry = false)
                    if (!ok) reconnectionMonitor.triggerReconnect("Initial libbox start failed")
                }
                return START_STICKY
            }
        }
    }

    private fun ensureForeground() {
        val channelId = "unified_hetzner_singbox"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Tunnel Console sing-box", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val note = builder
            .setContentTitle("Tunnel Console (sing-box)")
            .setContentText("Connected -> \${HetznerServerConfig.IPV4}")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2025, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2025, note)
        }
    }

    private suspend fun establishHetznerTunnel(isRetry: Boolean): Boolean = withContext(Dispatchers.IO) {
        val preset = UnifiedTunnelStateHolder.activePreset.value
        try {
            tearDownCore()
            if (!isRetry) UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.Connecting)

            val workDir = applicationContext.getExternalFilesDir(null)
                ?: File(applicationContext.filesDir, "singbox-work").apply { mkdirs() }
            val tempDir = File(applicationContext.cacheDir, "singbox-tmp").apply { mkdirs() }
            Libbox.setup(SetupOptions().apply {
                basePath = applicationContext.filesDir.path
                workingPath = workDir.path
                tempPath = tempDir.path
                fixAndroidStack = true
            })

            val configJson = UnifiedSingBoxConfigBuilder.buildClientConfigJson(preset, listOf(packageName))
            UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.Authenticating)
            Libbox.checkConfig(configJson)

            val bridge = UnifiedLibboxBridge(this@UnifiedHetznerVpnService, applicationContext)
            val service = Libbox.newService(configJson, bridge)
                ?: throw IOException("Libbox.newService returned null")
            service.start()

            platformBridge = bridge
            boxService = service
            running.set(true)
            UnifiedTunnelStateHolder.updateState(UnifiedConnectionState.TunnelEstablished)
            true
        } catch (err: Throwable) {
            Log.e(TAG, "libbox tunnel error on \${preset.host}:\${preset.port}: \${err.message}", err)
            tearDownCore()
            false
        }
    }

    private fun tearDownCore() {
        running.set(false)
        try { boxService?.close() } catch (_: Exception) {}
        boxService = null
        platformBridge?.closeTun()
        platformBridge = null
    }

    override fun onDestroy() {
        reconnectionMonitor.stop()
        tearDownCore()
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
