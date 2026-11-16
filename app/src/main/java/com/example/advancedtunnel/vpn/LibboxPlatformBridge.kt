package com.example.advancedtunnel.vpn

import android.content.Context
import android.content.pm.PackageManager
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
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * Production Android PlatformInterface implementation bridging the native Go `sing-box`
 * (`io.nekohasekai.libbox`) core with Android's [VpnService], [ConnectivityManager], and [PackageManager].
 *
 * All packet framing, gVisor TCP/IP termination, DNS hijacking, VLESS-Reality / Trojan-TLS
 * encryption, uTLS Chrome/iOS fingerprinting, and ClientHello record fragmentation run inside
 * compiled Go/libbox native code.
 */
class LibboxPlatformBridge(
    private val vpnService: VpnService,
    private val context: Context,
    private val onCoreLog: (String) -> Unit = { Log.i(TAG, it) },
    private val onNotification: (title: String, body: String) -> Unit = { _, _ -> }
) : PlatformInterface {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val packageManager = context.packageManager

    @Volatile
    var activeTunDescriptor: ParcelFileDescriptor? = null
        private set

    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "LibboxPlatformBridge"

        @Volatile
        private var coreInitialized = false

        fun ensureCoreInitialized(context: Context) {
            if (coreInitialized) return
            synchronized(this) {
                if (coreInitialized) return
                val baseDir = context.filesDir
                val workingDir = context.getExternalFilesDir(null) ?: File(baseDir, "singbox-work").apply { mkdirs() }
                val tempDir = File(context.cacheDir, "singbox-tmp").apply { mkdirs() }
                val options = SetupOptions().apply {
                    basePath = baseDir.path
                    workingPath = workingDir.path
                    tempPath = tempDir.path
                    fixAndroidStack = true
                }
                Libbox.setup(options)
                coreInitialized = true
            }
        }
    }

    fun closeTunDescriptor() {
        try {
            activeTunDescriptor?.close()
        } catch (_: Exception) {
        } finally {
            activeTunDescriptor = null
        }
    }

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(context) != null) {
            error("Missing Android VPN consent")
        }

        val builder = vpnService.Builder()
            .setSession("Tunnel Console (sing-box)")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Addr = options.inet4Address
        while (inet4Addr.hasNext()) {
            val prefix = inet4Addr.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        val inet6Addr = options.inet6Address
        while (inet6Addr.hasNext()) {
            val prefix = inet6Addr.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.autoRoute) {
            builder.addDnsServer(options.dnsServerAddress.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val routes4 = options.inet4RouteAddress
                if (routes4.hasNext()) {
                    while (routes4.hasNext()) {
                        val route = routes4.next()
                        builder.addRoute(route.address(), route.prefix())
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }
                val excludes4 = options.inet4RouteExcludeAddress
                while (excludes4.hasNext()) {
                    val exclude = excludes4.next()
                    builder.excludeRoute(
                        android.net.IpPrefix(java.net.InetAddress.getByName(exclude.address()), exclude.prefix())
                    )
                }
            } else {
                val routes4 = options.inet4RouteRange
                if (routes4.hasNext()) {
                    while (routes4.hasNext()) {
                        val route = routes4.next()
                        builder.addRoute(route.address(), route.prefix())
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }
            }

            val includePackages = options.includePackage
            while (includePackages.hasNext()) {
                try {
                    builder.addAllowedApplication(includePackages.next())
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            val excludePackages = options.excludePackage
            while (excludePackages.hasNext()) {
                try {
                    builder.addDisallowedApplication(excludePackages.next())
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        }

        try {
            builder.addDisallowedApplication(context.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }

        val pfd = builder.establish()
            ?: error("VpnService.Builder.establish() returned null")
        activeTunDescriptor = pfd
        return pfd.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        vpnService.protect(fd)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ConnectionOwner()
        }
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        val owner = ConnectionOwner()
        owner.userId = uid
        if (uid != Process.INVALID_UID) {
            val pkgs = packageManager.getPackagesForUid(uid)
            if (!pkgs.isNullOrEmpty()) {
                owner.androidPackageName = pkgs[0]
            }
        }
        return owner
    }

    override fun packageNameByUid(uid: Int): String {
        val packages = packageManager.getPackagesForUid(uid)
        if (packages.isNullOrEmpty()) {
            error("Package not found for uid $uid")
        }
        return packages[0]
    }

    override fun uidByPackageName(packageName: String): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageUid(packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            error("UID not found for package $packageName")
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dispatchDefaultInterface(network, connectivity.getLinkProperties(network), listener)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                dispatchDefaultInterface(network, linkProperties, listener)
            }

            override fun onLost(network: Network) {
                listener.updateDefaultInterface("", -1, false, false)
            }
        }
        defaultNetworkCallback = callback
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun dispatchDefaultInterface(
        network: Network,
        linkProperties: LinkProperties?,
        listener: InterfaceUpdateListener
    ) {
        val ifName = linkProperties?.interfaceName ?: return
        val caps = connectivity.getNetworkCapabilities(network)
        val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val isConstrained = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == false
        val index = try {
            java.net.NetworkInterface.getByName(ifName)?.index ?: 0
        } catch (_: Exception) {
            0
        }
        listener.updateDefaultInterface(ifName, index, isExpensive, isConstrained)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cb = defaultNetworkCallback ?: return
        defaultNetworkCallback = null
        try {
            connectivity.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<NetworkInterface>()
        val all = java.net.NetworkInterface.getNetworkInterfaces()
        while (all != null && all.hasMoreElements()) {
            val sysIf = all.nextElement()
            val libIf = NetworkInterface().apply {
                name = sysIf.name
                index = sysIf.index
                mtu = runCatching { sysIf.mtu }.getOrDefault(1500)
                val addrs = sysIf.interfaceAddresses.map { addr ->
                    val hostAddr = addr.address.hostAddress ?: ""
                    val clean = if (addr.address is Inet6Address) hostAddr.substringBefore('%') else hostAddr
                    "$clean/${addr.networkPrefixLength}"
                }
                addresses = KotlinStringIterator(addrs)
                dnsServer = KotlinStringIterator(emptyList())
                flags = 0
                if (sysIf.isUp) flags = flags or OsConstants.IFF_UP
                if (sysIf.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (sysIf.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            }
            list.add(libIf)
        }
        return object : NetworkInterfaceIterator {
            private var cursor = 0
            override fun hasNext(): Boolean = cursor < list.size
            override fun next(): NetworkInterface = list[cursor++]
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {}

    override fun readWIFIState(): WIFIState? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        val ssid = info.ssid?.removeSurrounding("\"") ?: ""
        val bssid = info.bssid ?: ""
        return WIFIState(ssid, bssid)
    }

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun systemCertificates(): StringIterator = KotlinStringIterator(emptyList())

    override fun writeLog(message: String) {
        onCoreLog(message)
    }

    override fun sendNotification(notification: Notification) {
        onNotification(notification.title, notification.body)
    }

    private class KotlinStringIterator(private val items: List<String>) : StringIterator {
        private var index = 0
        override fun len(): Int = items.size
        override fun hasNext(): Boolean = index < items.size
        override fun next(): String = items[index++]
    }
}
