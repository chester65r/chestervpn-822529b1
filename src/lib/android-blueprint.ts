import { clientConfig, serverConfig } from "./wireguard";
import { secureStoreSource, deviceEnrollmentSource, peerAdminSource, nativeCredentialTestsSource, enrollmentGuide } from "./native-auth-blueprint";

export type BlueprintFile = { name: string; path: string; lang: string; code: string };

/** Pinned engine versions; update together with the interop fixture (interop/wireguard/go.mod). */
export const WIREGUARD_ANDROID_TUNNEL = "com.wireguard.android:tunnel:1.0.20260102";

const manifest = String.raw`<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!--
      The packet engine's VPN service, com.wireguard.android.backend.GoBackend$VpnService,
      is declared by the WireGuard tunnel library manifest with BIND_VPN_SERVICE and
      exported=false, and is merged automatically. Do not declare a second VpnService.
    -->
    <application
        android:allowBackup="false"
        android:label="Tunnel Console"
        android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <receiver
            android:name=".CarrierAutoConnectReceiver"
            android:enabled="true"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.SIM_STATE_CHANGED" />
                <action android:name="android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>`;

const gradle = String.raw`plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.advancedtunnel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.advancedtunnel"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the WireGuard tunnel library (Java 8+ APIs).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }
}

dependencies {
    // Packet engine: official WireGuard userspace implementation (wireguard-go) + VpnService.
    implementation("${WIREGUARD_ANDROID_TUNNEL}")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}`;

const secureStore = secureStoreSource;

const carrierAutoConnect = String.raw`package com.example.advancedtunnel

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

enum class CarrierSimState {
    MOBILY, OTHER, NO_ACTIVE_DATA_SIM, NON_CELLULAR_NETWORK, UNKNOWN, PERMISSION_REQUIRED
}

data class CarrierSimSnapshot(
    val state: CarrierSimState,
    val simOperatorName: String = "",
    val carrierName: String = "",
    val registeredNetworkName: String = ""
)

/**
 * Opt-in SIM-aware start policy. It reads only the active data SIM's operator identity;
 * it never reads phone numbers, balance, SMS, APN settings, or carrier billing state.
 */
object CarrierAutoConnect {
    private const val PREFS = "carrier_auto_connect"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_STARTED = "auto_started"
    private const val KEY_CONSENT_REQUIRED = "vpn_consent_required"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAutoStarted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_STARTED, false)

    fun setAutoStarted(context: Context, started: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_STARTED, started).apply()
    }

    fun isConsentRequired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT_REQUIRED, false)

    fun setConsentRequired(context: Context, required: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONSENT_REQUIRED, required).apply()
    }

    private fun isMobilyName(value: String): Boolean =
        value.contains("mobily", ignoreCase = true) ||
            (value.contains("etihad", ignoreCase = true) &&
                value.contains("etisalat", ignoreCase = true)) ||
            value.contains("موبايلي")

    /**
     * Do not auto-connect over Wi-Fi or Ethernet. A missing/unvalidated cellular route
     * is still allowed to attempt the tunnel: the balance is not queried, and the tunnel
     * will remain without a handshake until Android has a usable mobile-data path.
     */
    private fun isMobilyRouteEligible(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val active = manager.activeNetwork ?: return true
        val activeCaps = manager.getNetworkCapabilities(active) ?: return true
        if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true
        if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return false

        if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            val physical = manager.allNetworks
                .filter { network ->
                    manager.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
                }
                .mapNotNull(manager::getNetworkCapabilities)
            val validatedWifi = physical.any {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            if (validatedWifi) return false
            if (physical.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }) return false
            return true
        }
        return false
    }

    /**
     * Uses the active-data subscription on API 30+, then the system default data
     * subscription. The registered network name is display-only: roaming must not
     * change the identity of the SIM that owns the subscription.
     */
    fun detect(context: Context): CarrierSimSnapshot {
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return CarrierSimSnapshot(CarrierSimState.PERMISSION_REQUIRED)
        }
        if (!isMobilyRouteEligible(app)) {
            return CarrierSimSnapshot(CarrierSimState.NON_CELLULAR_NETWORK)
        }

        return try {
            val subscriptions = app.getSystemService(SubscriptionManager::class.java)
                ?: return CarrierSimSnapshot(CarrierSimState.UNKNOWN)

            val activeDataId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SubscriptionManager.getActiveDataSubscriptionId()
            } else {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            val defaultDataId = SubscriptionManager.getDefaultDataSubscriptionId()
            val selectedId = if (activeDataId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                activeDataId
            } else {
                defaultDataId
            }

            val activeSubscriptions = subscriptions.activeSubscriptionInfoList.orEmpty()
            val selected = if (selectedId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                subscriptions.getActiveSubscriptionInfo(selectedId)
            } else {
                null
            } ?: activeSubscriptions.singleOrNull()

            if (selected == null) {
                return CarrierSimSnapshot(CarrierSimState.NO_ACTIVE_DATA_SIM)
            }

            val telephony = app.getSystemService(TelephonyManager::class.java)
                ?.createForSubscriptionId(selected.subscriptionId)
                ?: return CarrierSimSnapshot(CarrierSimState.UNKNOWN)

            val simOperator = telephony.simOperatorName.orEmpty().trim()
            val carrier = selected.carrierName?.toString().orEmpty().trim()
            val registered = telephony.networkOperatorName.orEmpty().trim()
            val state = when {
                isMobilyName(simOperator) || isMobilyName(carrier) -> CarrierSimState.MOBILY
                simOperator.isNotBlank() || carrier.isNotBlank() -> CarrierSimState.OTHER
                else -> CarrierSimState.UNKNOWN
            }
            CarrierSimSnapshot(state, simOperator, carrier, registered)
        } catch (_: SecurityException) {
            CarrierSimSnapshot(CarrierSimState.PERMISSION_REQUIRED)
        } catch (_: Exception) {
            CarrierSimSnapshot(CarrierSimState.UNKNOWN)
        }
    }

    /** Boot/SIM receiver path. It never launches a consent screen from the background. */
    fun evaluateInBackground(context: Context, finished: () -> Unit) {
        val app = context.applicationContext
        if (!isEnabled(app)) {
            finished()
            return
        }

        when (detect(app).state) {
            CarrierSimState.MOBILY -> {
                if (VpnService.prepare(app) != null) {
                    setConsentRequired(app, true)
                    finished()
                    return
                }
                setConsentRequired(app, false)
                val profile = SecureConfigStore(app).load()
                if (profile == null || !TunnelController.isIdle()) {
                    finished()
                    return
                }
                TunnelController.up(app, profile) { error ->
                    if (error == null) setAutoStarted(app, true)
                    finished()
                }
            }
            CarrierSimState.OTHER,
            CarrierSimState.NO_ACTIVE_DATA_SIM,
            CarrierSimState.NON_CELLULAR_NETWORK -> {
                if (!isAutoStarted(app)) {
                    finished()
                } else if (TunnelController.isIdle()) {
                    setAutoStarted(app, false)
                    finished()
                } else {
                    TunnelController.down(app) { stopped ->
                        if (stopped) setAutoStarted(app, false)
                        finished()
                    }
                }
            }
            CarrierSimState.UNKNOWN, CarrierSimState.PERMISSION_REQUIRED -> finished()
        }
    }
}

/** Re-evaluate only after explicit system SIM/default-data or boot events. */
class CarrierAutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!CarrierAutoConnect.isEnabled(context)) return
        val pending = goAsync()
        try {
            CarrierAutoConnect.evaluateInBackground(context.applicationContext) {
                pending.finish()
            }
        } catch (_: Exception) {
            pending.finish()
        }
    }
}`;

const transportProbe = String.raw`package com.example.advancedtunnel

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Bounded protocol diagnostics for endpoints the user owns or is authorized to test.
 * These probes validate a protocol handshake only; they do not create a VPN or forward
 * Android device traffic. WireGuard remains the full-device VPN engine.
 */
object AuthorizedTransportProbe {
    enum class Protocol(val usesTls: Boolean) {
        TLS_TCP(true),
        WS(false),
        WSS(true),
        HTTP_CONNECT(false),
        HTTPS_CONNECT(true),
        SOCKS5(false),
        SOCKS5_TLS(true)
    }

    data class Request(
        val protocol: Protocol,
        val endpointHost: String,
        val endpointPort: Int,
        val tlsServerName: String = "",
        val websocketPath: String = "/",
        val destinationHost: String = "",
        val destinationPort: Int = 443,
        val timeoutMs: Int = 10_000
    )

    data class Result(
        val protocol: String,
        val summary: String,
        val tlsVersion: String,
        val cipherSuite: String,
        val bytesSent: Int,
        val bytesReceived: Int
    )

    private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
    private val DNS_HOST = Regex("(?i)^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    private val IPV4_HOST = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

    fun execute(request: Request): Result {
        validate(request)
        val socket = open(request)
        try {
            val counts = when (request.protocol) {
                Protocol.TLS_TCP -> Pair(0, 0)
                Protocol.WS, Protocol.WSS -> probeWebSocket(socket, request)
                Protocol.HTTP_CONNECT, Protocol.HTTPS_CONNECT -> probeHttpConnect(socket, request)
                Protocol.SOCKS5, Protocol.SOCKS5_TLS -> probeSocks5(socket, request)
            }
            val tls = socket as? SSLSocket
            val tlsVersion = tls?.session?.protocol.orEmpty()
            val cipher = tls?.session?.cipherSuite.orEmpty()
            val summary = when (request.protocol) {
                Protocol.TLS_TCP -> "TLS handshake succeeded"
                Protocol.WS, Protocol.WSS -> "WebSocket upgrade accepted"
                Protocol.HTTP_CONNECT, Protocol.HTTPS_CONNECT -> "HTTP CONNECT returned 200"
                Protocol.SOCKS5, Protocol.SOCKS5_TLS -> "SOCKS5 CONNECT succeeded"
            }
            return Result(
                request.protocol.name,
                summary,
                tlsVersion,
                cipher,
                counts.first,
                counts.second
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun validate(request: Request) {
        require(request.endpointPort in 1..65535) { "Endpoint port must be 1-65535" }
        require(request.timeoutMs in 1_000..60_000) { "Timeout must be 1-60 seconds" }
        require(DNS_HOST.matches(request.endpointHost) || IPV4_HOST.matches(request.endpointHost)) {
            "Endpoint must be a DNS hostname or IPv4 address"
        }
        if (request.protocol.usesTls) {
            require(DNS_HOST.matches(request.endpointHost)) { "TLS requires a DNS endpoint hostname" }
            require(request.tlsServerName.equals(request.endpointHost, true)) {
                "TLS server name must match the authorized endpoint"
            }
        }
        if (request.protocol == Protocol.WS || request.protocol == Protocol.WSS) {
            require(request.websocketPath.startsWith("/") && request.websocketPath.length <= 256) {
                "WebSocket path must start with / and be at most 256 characters"
            }
            require(!request.websocketPath.contains('\r') &&
                !request.websocketPath.contains('\n') &&
                !request.websocketPath.contains('\u0000')) {
                "WebSocket path contains invalid characters"
            }
        }
        if (request.protocol == Protocol.HTTP_CONNECT ||
            request.protocol == Protocol.HTTPS_CONNECT ||
            request.protocol == Protocol.SOCKS5 ||
            request.protocol == Protocol.SOCKS5_TLS
        ) {
            require(DNS_HOST.matches(request.destinationHost) || IPV4_HOST.matches(request.destinationHost)) {
                "Proxy destination must be a DNS hostname or IPv4 address"
            }
            require(request.destinationPort in 1..65535) { "Destination port must be 1-65535" }
        }
    }

    private fun open(request: Request): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(request.endpointHost, request.endpointPort), request.timeoutMs)
        raw.soTimeout = request.timeoutMs
        if (!request.protocol.usesTls) return raw

        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, request.endpointHost, request.endpointPort, true) as SSLSocket
            tls.soTimeout = request.timeoutMs
            val parameters: SSLParameters = tls.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.serverNames = listOf(SNIHostName(request.tlsServerName))
            tls.sslParameters = parameters
            tls.startHandshake()
            return tls
        } catch (error: Exception) {
            runCatching { raw.close() }
            throw error
        }
    }

    private fun probeWebSocket(socket: Socket, request: Request): Pair<Int, Int> {
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        val key = Base64.getEncoder().encodeToString(nonce)
        val authority = request.endpointHost + ":" + request.endpointPort
        val text = "GET " + request.websocketPath + " HTTP/1.1\r\n" +
            "Host: " + authority + "\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: " + key + "\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        val bytes = text.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 4096) { "WebSocket request exceeds 4 KiB" }
        socket.getOutputStream().apply { write(bytes); flush() }
        val headers = readHttpHeaders(socket.getInputStream())
        val lines = headers.split("\r\n")
        require(lines.firstOrNull()?.matches(Regex("^HTTP/1\\.[01] 101(?: .*)?$")) == true) {
            "WebSocket endpoint did not return HTTP 101"
        }
        val fields = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }.groupBy({ it.first }, { it.second })
        require(fields["upgrade"]?.any { it.equals("websocket", true) } == true) {
            "WebSocket Upgrade header is missing"
        }
        require(fields["connection"]?.any { value -> value.split(',').any { it.trim().equals("upgrade", true) } } == true) {
            "WebSocket Connection header is missing Upgrade"
        }
        val expectedAccept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        )
        require(fields["sec-websocket-accept"]?.singleOrNull() == expectedAccept) {
            "WebSocket handshake response did not validate"
        }
        return Pair(bytes.size, headers.toByteArray(Charsets.ISO_8859_1).size)
    }

    private fun probeHttpConnect(socket: Socket, request: Request): Pair<Int, Int> {
        val destination = request.destinationHost + ":" + request.destinationPort
        val text = "CONNECT " + destination + " HTTP/1.1\r\n" +
            "Host: " + destination + "\r\n\r\n"
        val bytes = text.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= 4096) { "CONNECT request exceeds 4 KiB" }
        socket.getOutputStream().apply { write(bytes); flush() }
        val headers = readHttpHeaders(socket.getInputStream())
        val status = headers.substringBefore("\r\n")
        require(status.matches(Regex("^HTTP/1\\.[01] 200(?: .*)?$"))) {
            "HTTP CONNECT proxy did not return 200"
        }
        return Pair(bytes.size, headers.toByteArray(Charsets.ISO_8859_1).size)
    }

    private fun probeSocks5(socket: Socket, request: Request): Pair<Int, Int> {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val method = readExact(input, 2)
        require(method[0] == 0x05.toByte() && method[1] == 0x00.toByte()) {
            "SOCKS5 endpoint does not offer unauthenticated method 0"
        }

        val connect = ByteArrayOutputStream()
        connect.write(byteArrayOf(0x05, 0x01, 0x00))
        val ipv4 = request.destinationHost.split('.')
        if (IPV4_HOST.matches(request.destinationHost)) {
            connect.write(0x01)
            ipv4.forEach { connect.write(it.toInt()) }
        } else {
            val hostBytes = request.destinationHost.toByteArray(Charsets.UTF_8)
            require(hostBytes.isNotEmpty() && hostBytes.size <= 255) { "SOCKS5 destination hostname is too long" }
            connect.write(0x03)
            connect.write(hostBytes.size)
            connect.write(hostBytes)
        }
        connect.write((request.destinationPort shr 8) and 0xff)
        connect.write(request.destinationPort and 0xff)
        val bytes = connect.toByteArray()
        output.write(bytes)
        output.flush()

        val response = readExact(input, 4)
        require(response[0] == 0x05.toByte() && response[1] == 0x00.toByte() && response[2] == 0x00.toByte()) {
            "SOCKS5 CONNECT request was rejected"
        }
        val addressLength = when (response[3].toInt() and 0xff) {
            0x01 -> 4
            0x03 -> readExact(input, 1)[0].toInt() and 0xff
            0x04 -> 16
            else -> throw IllegalStateException("SOCKS5 returned an unknown address type")
        }
        readExact(input, addressLength + 2)
        return Pair(3 + bytes.size, 2 + 4 + addressLength + 2)
    }

    private fun readHttpHeaders(input: InputStream): String {
        val output = ByteArrayOutputStream()
        while (output.size() < MAX_HTTP_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) throw IllegalStateException("Endpoint closed before response headers completed")
            output.write(next)
            val data = output.toByteArray()
            val size = data.size
            if (size >= 4 && data[size - 4] == 13.toByte() && data[size - 3] == 10.toByte() &&
                data[size - 2] == 13.toByte() && data[size - 1] == 10.toByte()
            ) {
                return String(data, Charsets.ISO_8859_1)
            }
        }
        throw IllegalStateException("Response headers exceed 16 KiB")
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        require(count in 1..1024) { "Invalid protocol response length" }
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw IllegalStateException("Endpoint closed during protocol response")
            offset += read
        }
        return result
    }
}
`;

const tlsProbe = String.raw`package com.example.advancedtunnel

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Real TLS endpoint diagnostic for a server the user owns or is authorized to test. */
object AuthorizedTlsProbe {
    data class Result(val tlsVersion: String, val cipherSuite: String, val responsePreview: String, val bytesSent: Int, val bytesReceived: Int)

    fun execute(endpointHost: String, port: Int, tlsServerName: String, hostHeader: String, requestTemplate: String, timeoutMs: Int = 10_000): Result {
        require(endpointHost.matches(Regex("(?i)^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$"))) { "Endpoint must be a DNS hostname" }
        require(port in 1..65535) { "Invalid port" }
        require(timeoutMs in 1_000..60_000) { "Invalid timeout" }
        require(tlsServerName.equals(endpointHost, true)) { "TLS server name must match the authorized endpoint" }
        require(hostHeader.equals(tlsServerName, true)) { "Host header must match the TLS server name" }

        val request = requestTemplate.replace("[host]", hostHeader, true).replace("[crlf]", "\r\n", true)
        require(request.toByteArray(Charsets.UTF_8).size <= 4096) { "Request exceeds 4 KiB" }
        require(!request.contains('\u0000')) { "Request contains a NUL character" }
        val lines = request.replace("\r\n", "\n").split("\n")
        require(Regex("(?i)^(GET|HEAD|OPTIONS) /[^ ]* HTTP/1\\.[01]$").matches(lines.firstOrNull().orEmpty())) { "Only origin-form GET, HEAD, and OPTIONS requests are supported" }
        val hostLines = lines.drop(1).filter { it.startsWith("Host:", true) }
        require(hostLines.size == 1 && hostLines.single().substringAfter(':').trim().equals(hostHeader, true)) { "Exactly one matching Host header is required" }
        require(lines.none { it.startsWith("Proxy-Authorization:", true) || it.startsWith("Proxy-Connection:", true) }) { "Proxy control headers are not accepted" }

        val raw = Socket()
        raw.connect(InetSocketAddress(endpointHost, port), timeoutMs)
        raw.soTimeout = timeoutMs
        var tls: SSLSocket? = null
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            tls = factory.createSocket(raw, endpointHost, port, true) as SSLSocket
            tls.soTimeout = timeoutMs
            val params: SSLParameters = tls.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            params.serverNames = listOf(SNIHostName(tlsServerName))
            tls.sslParameters = params
            tls.startHandshake()

            val bytes = request.toByteArray(Charsets.UTF_8)
            val output = BufferedOutputStream(tls.outputStream)
            output.write(bytes)
            output.flush()
            val response = ByteArray(4096)
            val input = BufferedInputStream(tls.inputStream)
            var received = 0
            while (received < response.size) {
                val read = input.read(response, received, response.size - received)
                if (read <= 0) break
                received += read
                if (response.copyOf(received).toString(Charsets.UTF_8).contains("\r\n\r\n")) break
            }
            return Result(tls.session.protocol, tls.session.cipherSuite, response.copyOf(received).toString(Charsets.UTF_8).take(1024), bytes.size, received)
        } finally {
            runCatching { tls?.close() }
            runCatching { raw.close() }
        }
    }
}
`;

const controller = String.raw`package com.example.advancedtunnel

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.Executors

private class AppTunnel : Tunnel {
    @Volatile var state: Tunnel.State = Tunnel.State.DOWN
    override fun getName(): String = "console"
    override fun onStateChange(newState: Tunnel.State) { state = newState }
}

sealed interface TunnelStatus {
    data object Down : TunnelStatus
    data object WaitingForHandshake : TunnelStatus
    data class Connected(val rxBytes: Long, val txBytes: Long) : TunnelStatus
    data class Failed(val reason: String) : TunnelStatus
}

enum class EngineState { DOWN, STARTING, UP, STOPPING }

/**
 * Wraps the WireGuard GoBackend packet engine. GoBackend owns the VpnService, creates the
 * TUN interface, protects its UDP sockets from the VPN, and moves IP packets through
 * wireguard-go. All backend calls and credential mutations run on one worker thread, so
 * starts, stops and credential changes cannot overlap. The controller (not the UI) owns state.
 */
object TunnelController {
    private const val REJECT_AFTER_MS = 180_000L // WireGuard Reject-After-Time
    private val worker = Executors.newSingleThreadExecutor()
    private val tunnel = AppTunnel()
    @Volatile private var backend: GoBackend? = null
    @Volatile private var serverKey: Key? = null
    @Volatile var engineState: EngineState = EngineState.DOWN
        private set

    private fun backend(context: Context): GoBackend =
        backend ?: synchronized(this) {
            backend ?: GoBackend(context.applicationContext).also { backend = it }
        }

    /**
     * Parses with the engine's own parser and enforces the enrolled full-tunnel profile shape:
     * one server peer, endpoint, preshared key, one /32 address, tunnel DNS, safe MTU, and both
     * default routes so no IPv4 or IPv6 traffic silently bypasses the tunnel.
     */
    fun parse(configText: String): Config {
        val config = ByteArrayInputStream(configText.toByteArray(Charsets.UTF_8)).use { Config.parse(it) }
        require(config.peers.size == 1) { "Profile must contain exactly one server peer" }
        val peer = config.peers[0]
        val iface = config.${"`"}interface${"`"}
        require(peer.endpoint.isPresent) { "Server endpoint is required" }
        require(peer.preSharedKey.isPresent) { "Preshared key is required" }
        require(iface.addresses.size == 1 && iface.addresses.first().mask == 32) { "Exactly one /32 address is required" }
        require(iface.dnsServers.isNotEmpty()) { "Tunnel DNS is required" }
        require(!iface.mtu.isPresent || iface.mtu.get() in 1280..1420) { "MTU must be 1280-1420" }
        require(peer.allowedIps.any { it.mask == 0 && it.address is Inet4Address } &&
            peer.allowedIps.any { it.mask == 0 && it.address is Inet6Address }) { "Full-tunnel routes 0.0.0.0/0 and ::/0 are required" }
        return config
    }

    /** True only when the engine is confirmed down; credential changes are allowed only then. */
    fun isIdle(): Boolean = engineState == EngineState.DOWN && tunnel.state == Tunnel.State.DOWN

    /** Runs a credential mutation on the engine thread only while the engine is confirmed down. */
    fun whileIdle(action: () -> Unit, done: (Boolean) -> Unit) = worker.execute {
        val ok = isIdle() && try { action(); true } catch (_: Exception) { false }
        done(ok)
    }

    fun up(context: Context, configText: String, done: (String?) -> Unit) = worker.execute {
        if (!isIdle()) return@execute done("Tunnel is already running")
        engineState = EngineState.STARTING
        val error = try {
            val config = parse(configText)
            serverKey = config.peers[0].publicKey
            backend(context).setState(tunnel, Tunnel.State.UP, config)
            engineState = EngineState.UP
            null
        } catch (e: Exception) {
            // Confirm the engine really stayed down before reporting DOWN.
            engineState = if (tunnel.state == Tunnel.State.DOWN) EngineState.DOWN else EngineState.UP
            if (engineState == EngineState.DOWN) serverKey = null
            // Report the category only; exception text may contain configuration details.
            if (e is IllegalArgumentException) "Invalid profile" else "Engine could not start the tunnel"
        }
        done(error)
    }

    /** Reports true only after the engine confirms DOWN; on failure state stays UP (fail closed). */
    fun down(context: Context, done: (Boolean) -> Unit) = worker.execute {
        engineState = EngineState.STOPPING
        try { backend(context).setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        val stopped = tunnel.state == Tunnel.State.DOWN
        engineState = if (stopped) EngineState.DOWN else EngineState.UP
        if (stopped) serverKey = null
        done(stopped)
    }

    /** CONNECTED only after a fresh authenticated handshake with the configured server key. */
    fun status(context: Context, done: (TunnelStatus) -> Unit) = worker.execute {
        val key = serverKey
        val result = if (tunnel.state == Tunnel.State.DOWN) {
            // Android may revoke the VPN externally (another VPN, user, system).
            engineState = EngineState.DOWN
            serverKey = null
            TunnelStatus.Down
        } else if (key == null) {
            TunnelStatus.Failed("Engine running without a tracked server; stop the tunnel")
        } else try {
            val peer = backend(context).getStatistics(tunnel).peer(key)
            val age = System.currentTimeMillis() - (peer?.latestHandshakeEpochMillis() ?: 0L)
            if (peer == null || peer.latestHandshakeEpochMillis() == 0L || age > REJECT_AFTER_MS) {
                TunnelStatus.WaitingForHandshake
            } else {
                TunnelStatus.Connected(peer.rxBytes(), peer.txBytes())
            }
        } catch (_: Exception) {
            TunnelStatus.Failed("Statistics unavailable")
        }
        done(result)
    }
}`;

const mainActivity = String.raw`package com.example.advancedtunnel

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.widget.ArrayAdapter
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.advancedtunnel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecureConfigStore
    private lateinit var enrollment: DeviceEnrollment
    private val main = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private var stopReason: String? = null
    private var activityResumed = false
    private var autoStartInFlight = false
    private var pendingAutoManaged = false
    private var suppressAutoSwitchListener = false
    private var simReceiverRegistered = false
    private var networkCallbackRegistered = false
    private var transportProbeInFlight = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { refreshCarrierAutomation() }
        }
        override fun onLost(network: Network) {
            main.post { refreshCarrierAutomation() }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            main.post { refreshCarrierAutomation() }
        }
    }

    private val simReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshCarrierAutomation()
        }
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val managedByAutoRule = pendingAutoManaged
            pendingAutoManaged = false
            val stillEligible = !managedByAutoRule ||
                (CarrierAutoConnect.isEnabled(this) &&
                    CarrierAutoConnect.detect(this).state == CarrierSimState.MOBILY)
            if (result.resultCode == RESULT_OK && stillEligible) {
                startTunnel(managedByAutoRule)
            } else {
                autoStartInFlight = false
                if (managedByAutoRule) CarrierAutoConnect.setConsentRequired(this, true)
                showState("VPN permission denied or Mobily is no longer the active data SIM")
            }
        }

    private val phoneStatePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                CarrierAutoConnect.setEnabled(this, true)
                setAutoSwitchChecked(true)
                refreshCarrierAutomation()
            } else {
                setAutoSwitchChecked(false)
                CarrierAutoConnect.setEnabled(this, false)
                showState("SIM detection permission denied")
            }
        }

    // UI state is always derived from TunnelController; the activity keeps no running flag.
    private val poll = object : Runnable {
        override fun run() {
            TunnelController.status(this@MainActivity) { status -> main.post { render(status) } }
            if (!TunnelController.isIdle()) main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SecureConfigStore(this)
        enrollment = DeviceEnrollment(this)
        setAutoSwitchChecked(CarrierAutoConnect.isEnabled(this))
        binding.cbAutoMobily.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoSwitchListener) return@setOnCheckedChangeListener
            if (checked) {
                val granted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    CarrierAutoConnect.setEnabled(this, true)
                    refreshCarrierAutomation()
                } else {
                    setAutoSwitchChecked(false)
                    phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
                }
            } else {
                CarrierAutoConnect.setEnabled(this, false)
                CarrierAutoConnect.setConsentRequired(this, false)
                if (CarrierAutoConnect.isAutoStarted(this)) {
                    if (!TunnelController.isIdle()) {
                        stopReason = "Mobily automatic connection disabled"
                        stopTunnel()
                    } else {
                        CarrierAutoConnect.setAutoStarted(this, false)
                    }
                }
                binding.tvCarrierInfo.text = "Automatic Mobily connection is off"
            }
        }
        binding.btnIdentity.setOnClickListener {
            var key = ""
            TunnelController.whileIdle({ key = enrollment.publicKey() }) { ok ->
                main.post { if (ok) binding.tvDevicePublicKey.text = key else toast("Stop the tunnel first, or identity unavailable") }
            }
        }

        binding.btnSave.setOnClickListener { validateAndSave() }
        binding.spTransportProtocol.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AuthorizedTransportProbe.Protocol.values().map { it.name.replace('_', ' ') }
        )
        binding.btnProbeTransport.setOnClickListener {
            if (transportProbeInFlight) {
                toast("A protocol test is already running")
                return@setOnClickListener
            }
            transportProbeInFlight = true
            binding.btnProbeTransport.isEnabled = false
            val protocol = AuthorizedTransportProbe.Protocol.values()
                .getOrNull(binding.spTransportProtocol.selectedItemPosition)
                ?: AuthorizedTransportProbe.Protocol.TLS_TCP
            val endpointHost = binding.etTransportHost.text.toString().trim()
            val request = AuthorizedTransportProbe.Request(
                protocol = protocol,
                endpointHost = endpointHost,
                endpointPort = binding.etTransportPort.text.toString().trim().toIntOrNull() ?: 0,
                tlsServerName = binding.etTlsServerName.text.toString().trim().ifBlank { endpointHost },
                websocketPath = binding.etWebSocketPath.text.toString().trim().ifBlank { "/" },
                destinationHost = binding.etDestinationHost.text.toString().trim(),
                destinationPort = binding.etDestinationPort.text.toString().trim().toIntOrNull() ?: 0
            )
            binding.tvTransportResult.text = "Testing authorized endpoint..."
            Thread {
                val outcome = try {
                    val result = AuthorizedTransportProbe.execute(request)
                    val tlsDetails = if (result.tlsVersion.isNotBlank()) {
                        " · " + result.tlsVersion + " · " + result.cipherSuite
                    } else {
                        " · no TLS"
                    }
                    "PASS: " + result.summary + tlsDetails +
                        " · sent " + result.bytesSent + " B · received " + result.bytesReceived + " B"
                } catch (error: Exception) {
                    "FAILED: " + (error.message ?: "Transport check failed").take(160)
                }
                main.post {
                    transportProbeInFlight = false
                    binding.btnProbeTransport.isEnabled = true
                    binding.tvTransportResult.text = outcome
                }
            }.apply { isDaemon = true }.start()
        }

        binding.btnToggle.setOnClickListener {
            stopReason = null
            when (TunnelController.engineState) {
                EngineState.UP -> {
                    // An explicit manual stop also pauses the automatic rule.
                    CarrierAutoConnect.setEnabled(this, false)
                    setAutoSwitchChecked(false)
                    stopTunnel()
                }
                EngineState.DOWN -> if (store.load() != null) requestVpnPermission(false) else toast("Enroll this device first")
                else -> toast("Please wait")
            }
        }
        binding.btnClear.setOnClickListener {
            TunnelController.whileIdle({ enrollment.clear() }) { ok ->
                main.post {
                    if (!ok) toast("Stop the tunnel before deleting credentials") else {
                        binding.etWgConfig.setText("")
                        binding.etServerKey.setText("")
                        binding.tvDevicePublicKey.text = ""
                        toast("Device credentials deleted; revoke its public key on the server")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!simReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction("android.intent.action.SIM_STATE_CHANGED")
                addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(simReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(simReceiver, filter)
            }
            simReceiverRegistered = true
        }
        if (!networkCallbackRegistered) {
            val manager = getSystemService(ConnectivityManager::class.java)
            if (manager != null) {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    networkCallback
                )
                networkCallbackRegistered = true
            }
        }
        refreshCarrierAutomation()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        main.removeCallbacks(poll)
        main.post(poll)
        refreshCarrierAutomation()
    }

    override fun onPause() {
        activityResumed = false
        main.removeCallbacks(poll)
        binding.etWgConfig.setText("")
        super.onPause()
    }

    override fun onStop() {
        if (simReceiverRegistered) {
            unregisterReceiver(simReceiver)
            simReceiverRegistered = false
        }
        if (networkCallbackRegistered) {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    private fun setAutoSwitchChecked(checked: Boolean) {
        suppressAutoSwitchListener = true
        binding.cbAutoMobily.isChecked = checked
        suppressAutoSwitchListener = false
    }

    private fun refreshCarrierAutomation() {
        if (!CarrierAutoConnect.isEnabled(this)) {
            binding.tvCarrierInfo.text = "Automatic Mobily connection is off"
            return
        }

        val snapshot = CarrierAutoConnect.detect(this)
        val simName = snapshot.simOperatorName.ifBlank {
            snapshot.carrierName.ifBlank { "Unknown" }
        }
        val registered = snapshot.registeredNetworkName.ifBlank { "Unavailable" }
        binding.tvCarrierInfo.text =
            "SIM operator: " + simName + "\nRegistered network: " + registered

        when (snapshot.state) {
            CarrierSimState.MOBILY -> {
                if (store.load() == null) {
                    showState("Mobily data SIM detected. Enroll a WireGuard profile first.")
                    return
                }
                if (!TunnelController.isIdle() || autoStartInFlight) return

                if (VpnService.prepare(this) != null) {
                    CarrierAutoConnect.setConsentRequired(this, true)
                    if (activityResumed) {
                        requestVpnPermission(true)
                    } else {
                        showState("Mobily detected. Open the app to approve VPN access.")
                    }
                } else {
                    CarrierAutoConnect.setConsentRequired(this, false)
                    requestVpnPermission(true)
                }
            }
            CarrierSimState.OTHER -> stopAutoManagedTunnel(
                "Default data SIM is not Mobily; automatic tunnel stopped"
            )
            CarrierSimState.NO_ACTIVE_DATA_SIM -> stopAutoManagedTunnel(
                "No active data SIM; automatic tunnel stopped"
            )
            CarrierSimState.NON_CELLULAR_NETWORK ->
                stopAutoManagedTunnel("Wi-Fi or a non-cellular route is active; Mobily auto-connect is paused")
            CarrierSimState.PERMISSION_REQUIRED ->
                showState("Allow Phone permission to identify the Mobily SIM.")
            CarrierSimState.UNKNOWN ->
                showState("SIM operator is unavailable; automatic start is paused.")
        }
    }

    private fun stopAutoManagedTunnel(reason: String) {
        if (!CarrierAutoConnect.isAutoStarted(this)) return
        if (TunnelController.isIdle()) {
            CarrierAutoConnect.setAutoStarted(this, false)
        } else {
            stopReason = reason
            stopTunnel()
        }
    }

    private fun validateAndSave() {
        val text = binding.etWgConfig.text.toString()
        val serverKey = binding.etServerKey.text.toString()
        var rejected = false
        TunnelController.whileIdle({
            try { enrollment.enroll(text, serverKey) } catch (e: IllegalArgumentException) { rejected = true; throw e }
        }) { ok ->
            main.post {
                when {
                    ok -> { binding.etWgConfig.setText(""); toast("Profile saved securely"); refreshCarrierAutomation() }
                    !TunnelController.isIdle() -> toast("Stop the tunnel before replacing credentials")
                    rejected -> binding.etWgConfig.error = "Enrollment rejected; check server identity and full-tunnel profile"
                    else -> binding.etWgConfig.error = "Invalid WireGuard profile"
                }
            }
        }
    }

    private fun requestVpnPermission(managedByAutoRule: Boolean = false) {
        pendingAutoManaged = managedByAutoRule
        if (managedByAutoRule) autoStartInFlight = true
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermission.launch(intent)
        } else {
            startTunnel(managedByAutoRule)
        }
    }

    private fun startTunnel(managedByAutoRule: Boolean = false) {
        if (managedByAutoRule &&
            (!CarrierAutoConnect.isEnabled(this) ||
                CarrierAutoConnect.detect(this).state != CarrierSimState.MOBILY)
        ) {
            autoStartInFlight = false
            return showState("Mobily auto-connect was cancelled because the SIM selection changed")
        }
        val text = store.load() ?: run {
            autoStartInFlight = false
            return showState("No stored profile")
        }
        if (!managedByAutoRule) CarrierAutoConnect.setAutoStarted(this, false)
        showState("STARTING")
        renderControls()
        TunnelController.up(this, text) { error ->
            main.post {
                autoStartInFlight = false
                if (error != null) {
                    if (managedByAutoRule) CarrierAutoConnect.setAutoStarted(this, false)
                    showState(error)
                } else if (managedByAutoRule &&
                    (!CarrierAutoConnect.isEnabled(this) ||
                        CarrierAutoConnect.detect(this).state != CarrierSimState.MOBILY)
                ) {
                    TunnelController.down(this) { stopped ->
                        main.post {
                            CarrierAutoConnect.setAutoStarted(this, false)
                            showState(
                                if (stopped) "Mobily auto-connect cancelled after SIM selection changed"
                                else "STOP FAILED: tunnel still active and blocking traffic"
                            )
                            renderControls()
                        }
                    }
                } else {
                    CarrierAutoConnect.setAutoStarted(this, managedByAutoRule)
                    lastRx = 0L
                    lastTx = 0L
                    stopReason = null
                }
                main.removeCallbacks(poll)
                main.post(poll)
                renderControls()
            }
        }
        renderControls()
    }

    private fun stopTunnel() {
        main.removeCallbacks(poll)
        renderControls()
        TunnelController.down(this) { stopped ->
            main.post {
                if (stopped) {
                    CarrierAutoConnect.setAutoStarted(this, false)
                    render(TunnelStatus.Down)
                } else {
                    showState("STOP FAILED: tunnel still active and blocking traffic")
                    main.post(poll)
                }
                renderControls()
            }
        }
    }

    private fun render(status: TunnelStatus) {
        when (status) {
            TunnelStatus.Down -> {
                showState(stopReason ?: "DISCONNECTED")
                binding.tvDownloadSpeed.text = "0.00 KB/s"
                binding.tvUploadSpeed.text = "0.00 KB/s"
            }
            // Fail closed: keep the VPN up so traffic stays blocked instead of leaking outside
            // the tunnel. Only an explicit user stop removes protection.
            TunnelStatus.WaitingForHandshake ->
                showState("WAITING FOR HANDSHAKE: traffic blocked. Check server key, endpoint and firewall, or stop the tunnel.")
            is TunnelStatus.Connected -> {
                showState("CONNECTED")
                binding.tvDownloadSpeed.text = String.format("%.2f KB/s", (status.rxBytes - lastRx).coerceAtLeast(0L) / 1024.0)
                binding.tvUploadSpeed.text = String.format("%.2f KB/s", (status.txBytes - lastTx).coerceAtLeast(0L) / 1024.0)
                lastRx = status.rxBytes
                lastTx = status.txBytes
            }
            is TunnelStatus.Failed -> showState(status.reason)
        }
        renderControls()
    }

    private fun renderControls() {
        val state = TunnelController.engineState
        val idle = TunnelController.isIdle()
        binding.btnToggle.isEnabled = state == EngineState.UP || state == EngineState.DOWN
        binding.btnToggle.text = if (state == EngineState.DOWN) "START TUNNEL" else "STOP TUNNEL"
        // Credential controls are locked whenever the engine is not confirmed down.
        binding.btnIdentity.isEnabled = idle
        binding.btnSave.isEnabled = idle
        binding.btnClear.isEnabled = idle
    }

    private fun showState(text: String) { binding.tvStatusInfo.text = text }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}`;

const layout = String.raw`<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="20dp">

        <Button android:id="@+id/btnIdentity"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Generate device public identity" />
        <TextView android:id="@+id/tvDevicePublicKey"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:textIsSelectable="true" android:saveEnabled="false" />
        <EditText android:id="@+id/etServerKey"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Independently confirmed server public key"
            android:importantForAutofill="no" android:saveEnabled="false"
            android:inputType="textNoSuggestions" />
        <!-- Enrollment response contains a secret PSK but NEVER the device private key. -->
        <EditText android:id="@+id/etWgConfig"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Enrollment response from your administrator"
            android:saveEnabled="false"
            android:importantForAutofill="no"
            android:longClickable="false"
            android:inputType="textMultiLine|textNoSuggestions|textPassword"
            android:fontFamily="monospace" android:minLines="8" />

        <LinearLayout
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="horizontal" android:weightSum="3"
            android:layout_marginTop="16dp" android:layout_marginBottom="16dp">

            <TextView android:id="@+id/tvDownloadSpeed"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="0.00 KB/s" android:gravity="center" />
            <TextView android:id="@+id/tvUploadSpeed"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="0.00 KB/s" android:gravity="center" />
            <TextView android:id="@+id/tvStatusInfo"
                android:layout_width="0dp" android:layout_height="wrap_content"
                android:layout_weight="1" android:text="DISCONNECTED" android:gravity="center" />
        </LinearLayout>

        <CheckBox android:id="@+id/cbAutoMobily"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Auto-connect only on Mobily data SIM (balance is not checked)" />

        <TextView android:id="@+id/tvCarrierInfo"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Automatic Mobily connection is off"
            android:paddingTop="8dp" android:paddingBottom="8dp" />

        <TextView
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Authorized protocol diagnostics (not a full-device VPN)"
            android:textStyle="bold" android:paddingTop="16dp" />

        <Spinner android:id="@+id/spTransportProtocol"
            android:layout_width="match_parent" android:layout_height="wrap_content" />

        <EditText android:id="@+id/etTransportHost"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Authorized endpoint hostname" android:inputType="textUri"
            android:importantForAutofill="no" />

        <EditText android:id="@+id/etTransportPort"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Endpoint port" android:inputType="number"
            android:text="443" android:importantForAutofill="no" />

        <EditText android:id="@+id/etTlsServerName"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="TLS server name (must equal endpoint hostname)" android:inputType="textUri"
            android:importantForAutofill="no" />

        <EditText android:id="@+id/etWebSocketPath"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="WebSocket path (default /)" android:text="/"
            android:importantForAutofill="no" />

        <EditText android:id="@+id/etDestinationHost"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Proxy destination hostname (CONNECT / SOCKS5)" android:inputType="textUri"
            android:importantForAutofill="no" />

        <EditText android:id="@+id/etDestinationPort"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:hint="Proxy destination port" android:inputType="number"
            android:text="443" android:importantForAutofill="no" />

        <Button android:id="@+id/btnProbeTransport"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Test selected protocol" />

        <TextView android:id="@+id/tvTransportResult"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="No protocol test run" android:paddingTop="8dp"
            android:paddingBottom="12dp" />

        <Button android:id="@+id/btnToggle"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="START TUNNEL" />

        <Button android:id="@+id/btnSave"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Save enrollment securely" />

        <Button android:id="@+id/btnClear"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:text="Delete stored profile" />
    </LinearLayout>
</ScrollView>`;

const P = (name: string) => `<${name}>`;

/** Same generator the interop fixture uses; placeholders are filled by server-setup.sh. */
const serverTemplate = serverConfig({
  privateKey: P("SERVER_PRIVATE_KEY"),
  address: "10.66.66.1/24",
  listenPort: 51820,
  mtu: 1280,
  peers: [{ name: "android-device-1", publicKey: P("DEVICE_PUBLIC_KEY"), presharedKey: P("DEVICE_PRESHARED_KEY"), address: "10.66.66.2/32" }],
});

const clientTemplate = clientConfig({
  privateKey: P("DEVICE_PRIVATE_KEY"),
  address: "10.66.66.2/32",
  dns: "10.66.66.1",
  mtu: 1280,
  serverPublicKey: P("SERVER_PUBLIC_KEY"),
  presharedKey: P("DEVICE_PRESHARED_KEY"),
  endpointHost: "vpn.example.com",
  endpointPort: 51820,
  allowedIps: ["0.0.0.0/0", "::/0"],
  keepalive: 25,
});

const serverSetup = String.raw`#!/usr/bin/env bash
# server-setup.sh - WireGuard server for the Android client (Debian/Ubuntu, run as root).
# Run only on a server you own, for traffic you are authorized to carry.
# Replaces the earlier WebSocket relay: both sides now speak the WireGuard protocol.
set -euo pipefail
umask 077
EGRESS_IF="${"$"}{EGRESS_IF:-eth0}"       # interface that reaches the internet
[[ "$EGRESS_IF" =~ ^[a-zA-Z0-9_-]+$ ]] || exit 1

apt-get update && apt-get install -y wireguard-tools nftables unbound
install -d -m 700 /etc/wireguard
# Initial setup only: never silently replace an existing server identity.
[[ ! -e /etc/wireguard/wg0.conf && ! -e /etc/wireguard/server.key ]] || { echo "Server already initialized"; exit 1; }
wg genkey > /etc/wireguard/server.key
wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub
# No device private key is generated here; devices generate their own identities.
{ printf '[Interface]\nPrivateKey = '; cat /etc/wireguard/server.key;
  printf 'Address = 10.66.66.1/24\nListenPort = 51820\nMTU = 1280\n';
} > /etc/wireguard/wg0.conf
chmod 600 /etc/wireguard/wg0.conf

# Forward only tunnel traffic; block tunnel clients from private networks AND from the server
# itself (input path) except the tunnel DNS resolver. Persisted and loaded on every boot.
echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wireguard.conf && sysctl --system >/dev/null
install -d -m 755 /etc/nftables.d
cat > /etc/nftables.d/wireguard.nft <<NFT
table inet wg_filter {
  chain input {
    type filter hook input priority 0; policy accept;
    iifname "wg0" ct state established,related accept
    iifname "wg0" ip daddr 10.66.66.1 udp dport 53 accept
    iifname "wg0" ip daddr 10.66.66.1 tcp dport 53 accept
    iifname "wg0" drop
  }
  chain forward {
    type filter hook forward priority 0; policy drop;
    ct state established,related accept
    iifname "wg0" ip daddr { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 100.64.0.0/10 } drop
    iifname "wg0" oifname "$EGRESS_IF" accept
  }
}
table ip wg_nat {
  chain postrouting { type nat hook postrouting priority 100; oifname "$EGRESS_IF" ip saddr 10.66.66.0/24 masquerade; }
}
NFT
nft -c -f /etc/nftables.d/wireguard.nft
# Persist without overwriting existing host rules: include our file from nftables.conf once.
touch /etc/nftables.conf
grep -qxF 'include "/etc/nftables.d/wireguard.nft"' /etc/nftables.conf \
  || echo 'include "/etc/nftables.d/wireguard.nft"' >> /etc/nftables.conf
nft -c -f /etc/nftables.conf
systemctl enable nftables.service
systemctl restart nftables.service
nft list table inet wg_filter >/dev/null || { echo "Firewall not active; refusing to start VPN"; exit 1; }
# Never start the VPN before the firewall on boot.
install -d -m 755 /etc/systemd/system/wg-quick@wg0.service.d
printf '[Unit]\nRequires=nftables.service\nAfter=nftables.service\n' > /etc/systemd/system/wg-quick@wg0.service.d/firewall.conf
systemctl daemon-reload

# Tunnel-only DNS resolver at 10.66.66.1 (the profile's DNS), not exposed publicly.
cat > /etc/unbound/unbound.conf.d/wireguard.conf <<UNB
server:
  interface: 10.66.66.1
  access-control: 10.66.66.0/24 allow
UNB

systemctl enable --now wg-quick@wg0
systemctl restart unbound
echo "Server initialized without authorized devices. Use peer-admin.py enroll with the Android public key."
`;

export const BLUEPRINT_FILES: BlueprintFile[] = [
  { name: "android-workflow.md", path: "native/android/README.md (full workflow in project source)", lang: "markdown", code: `# Repeatable Android checks — all native results NOT RUN
Complete instructions: native/android/README.md in the project source.
Pinned tools: JDK 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, SDK 34.
Generate: bun native/android/generate-project.ts /tmp/tunnel-android
Create pinned wrapper: gradle -p /tmp/tunnel-android wrapper --gradle-version 8.9 --distribution-type bin
Build: bash native/android/run-checks.sh /tmp/tunnel-android build
Emulator: bash native/android/run-checks.sh /tmp/tunnel-android emulator emulator-5554
Physical: bash native/android/run-checks.sh /tmp/tunnel-android physical 'PHONE_SERIAL'
Run each supported API: 26, 31, 33, 34, and every newer claimed API.
Automated Android checks: encrypted credential round-trip, tamper/key-loss rejection,
fresh nonce, deletion, local identity and pinned-server enrollment.
Manual emulator checks: VPN consent, launch, timeout, start/stop, rotation and owned-server traffic.
Physical-only evidence: hardware-backed Keystore, Wi-Fi/mobile handoff, OEM/background,
16 KB device loading, real-network leak/telemetry and signed-release checks.
Result records separate emulator from physical; never claim network tests from instrumentation.
No JDK/Android SDK/emulator/phone is available here; native execution remains NOT RUN.
` },
  { name: "AndroidManifest.xml", path: "app/src/main/AndroidManifest.xml", lang: "xml", code: manifest },
  { name: "AuthorizedTransportProbe.kt", path: "app/src/main/java/com/example/advancedtunnel/AuthorizedTransportProbe.kt", lang: "kotlin", code: transportProbe },
  { name: "AuthorizedTlsProbe.kt", path: "app/src/main/java/com/example/advancedtunnel/AuthorizedTlsProbe.kt", lang: "kotlin", code: tlsProbe },
  { name: "TunnelController.kt", path: "app/src/main/java/com/example/advancedtunnel/TunnelController.kt", lang: "kotlin", code: controller },
  { name: "MainActivity.kt", path: "app/src/main/java/com/example/advancedtunnel/MainActivity.kt", lang: "kotlin", code: mainActivity },
  { name: "SecureConfigStore.kt", path: "app/src/main/java/com/example/advancedtunnel/SecureConfigStore.kt", lang: "kotlin", code: secureStore },
  { name: "CarrierAutoConnect.kt", path: "app/src/main/java/com/example/advancedtunnel/CarrierAutoConnect.kt", lang: "kotlin", code: carrierAutoConnect },
  { name: "DeviceEnrollment.kt", path: "app/src/main/java/com/example/advancedtunnel/DeviceEnrollment.kt", lang: "kotlin", code: deviceEnrollmentSource },
  { name: "NativeCredentialsTest.kt", path: "app/src/androidTest/java/com/example/advancedtunnel/NativeCredentialsTest.kt", lang: "kotlin", code: nativeCredentialTestsSource },
  { name: "native-auth.md", path: "docs/native-auth.md", lang: "markdown", code: enrollmentGuide },
  { name: "peer-admin.py", path: "server/peer-admin.py", lang: "python", code: peerAdminSource },
  { name: "activity_main.xml", path: "app/src/main/res/layout/activity_main.xml", lang: "xml", code: layout },
  { name: "build.gradle.kts", path: "app/build.gradle.kts", lang: "kotlin", code: gradle },
  { name: "wg0.conf", path: "server/wg0.conf", lang: "ini", code: serverTemplate },
  { name: "client.conf", path: "server/client.conf", lang: "ini", code: clientTemplate },
  { name: "server-setup.sh", path: "server/server-setup.sh", lang: "bash", code: serverSetup },
];
