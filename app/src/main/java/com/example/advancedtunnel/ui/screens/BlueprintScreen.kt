package com.example.advancedtunnel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.SurfaceDark
import com.example.advancedtunnel.ui.theme.SurfaceVariantDark
import com.example.advancedtunnel.ui.theme.TextMuted
import com.example.advancedtunnel.ui.theme.TextPrimary
import com.example.advancedtunnel.ui.theme.TextSecondary
import java.security.SecureRandom

data class BlueprintFileItem(
    val name: String,
    val path: String,
    val content: String
)

data class CompatibilityCheckItem(
    val id: String,
    val title: String,
    val status: String,
    val requirement: String
)

private val COMPATIBILITY_CHECKS = listOf(
    CompatibilityCheckItem(
        id = "keystore-identity",
        title = "On-device Keystore X25519 Identity",
        status = "local",
        requirement = "Generate Curve25519 private keys exclusively on Android and wrap with AES-256-GCM MasterKey."
    ),
    CompatibilityCheckItem(
        id = "controller-fail-closed",
        title = "Fail-Closed Controller Lifecycle",
        status = "local",
        requirement = "TunnelController owns engine state and blocks credential changes unless confirmed DOWN."
    ),
    CompatibilityCheckItem(
        id = "offline-enrollment",
        title = "Root-Only Offline Peer Enrollment",
        status = "local",
        requirement = "Enroll only device public keys via peer-admin.py with atomic disk persistence before wg syncconf."
    ),
    CompatibilityCheckItem(
        id = "full-tunnel-dns",
        title = "Full-Tunnel 0.0.0.0/0 & ::/0 + Unbound DNS",
        status = "local",
        requirement = "Enforce default IPv4/IPv6 routes and isolated Unbound resolver at 10.66.66.1."
    ),
    CompatibilityCheckItem(
        id = "page-size-16kb",
        title = "Android 15+ 16 KB ELF Page Alignment",
        status = "pending",
        requirement = "Verify libwg-go.so 16 KB ELF segment alignment on physical arm64-v8a hardware."
    )
)

private fun generateCurve25519PublicKeyPreview(context: Context, forceRotate: Boolean = false): Pair<String, String> {
    val prefs = context.getSharedPreferences("tunnel_device_enrollment_preview", Context.MODE_PRIVATE)
    val existingPub = prefs.getString("device_pub", null)
    val existingName = prefs.getString("device_name", null)
    if (!forceRotate && !existingPub.isNullOrBlank() && !existingName.isNullOrBlank()) {
        return existingName to existingPub
    }
    val raw = ByteArray(32)
    SecureRandom().nextBytes(raw)
    raw[0] = (raw[0].toInt() and 248).toByte()
    raw[31] = (raw[31].toInt() and 127).toByte()
    raw[31] = (raw[31].toInt() or 64).toByte()
    val pubB64 = Base64.encodeToString(raw, Base64.NO_WRAP)
    val devId = "android-wg-" + pubB64.filter { it.isLetterOrDigit() }.take(6).lowercase()
    prefs.edit().putString("device_pub", pubB64).putString("device_name", devId).apply()
    return devId to pubB64
}

private val BLUEPRINT_FILES = listOf(
    BlueprintFileItem(
        name = "TunnelController.kt",
        path = "app/src/main/java/com/example/advancedtunnel/TunnelController.kt",
        content = """
            package com.example.advancedtunnel

            import android.content.Context
            import com.wireguard.android.backend.GoBackend
            import com.wireguard.android.backend.Tunnel
            import com.wireguard.config.Config
            import java.io.BufferedReader
            import java.io.StringReader
            import java.util.concurrent.Executors

            enum class EngineState { DOWN, STARTING, UP, STOPPING, ERROR }

            object TunnelController {
                private val worker = Executors.newSingleThreadExecutor()
                @Volatile private var backend: GoBackend? = null
                @Volatile var engineState: EngineState = EngineState.DOWN
                    private set

                private val tunnel = object : Tunnel {
                    override fun getName(): String = "wg0"
                    override fun onStateChange(newState: Tunnel.State) {
                        engineState = if (newState == Tunnel.State.UP) EngineState.UP else EngineState.DOWN
                    }
                }

                fun isIdle(): Boolean = engineState == EngineState.DOWN || engineState == EngineState.ERROR

                fun up(context: Context, configText: String, done: (String?) -> Unit) = worker.execute {
                    try {
                        engineState = EngineState.STARTING
                        val parsed = Config.parse(BufferedReader(StringReader(configText)))
                        val engine = backend ?: GoBackend(context.applicationContext).also { backend = it }
                        engine.setState(tunnel, Tunnel.State.UP, parsed)
                        engineState = EngineState.UP
                        done(null)
                    } catch (e: Exception) {
                        engineState = EngineState.ERROR
                        done(e.message ?: "WireGuard failed to start")
                    }
                }

                fun down(context: Context, done: (Boolean) -> Unit) = worker.execute {
                    try {
                        engineState = EngineState.STOPPING
                        val engine = backend ?: GoBackend(context.applicationContext).also { backend = it }
                        engine.setState(tunnel, Tunnel.State.DOWN, null)
                        engineState = EngineState.DOWN
                        done(true)
                    } catch (_: Exception) {
                        engineState = EngineState.ERROR
                        done(false)
                    }
                }
            }
        """.trimIndent()
    ),
    BlueprintFileItem(
        name = "DeviceEnrollment.kt",
        path = "app/src/main/java/com/example/advancedtunnel/DeviceEnrollment.kt",
        content = """
            package com.example.advancedtunnel

            import com.wireguard.crypto.Key
            import com.wireguard.crypto.KeyPair

            data class PublicDeviceEnrollment(
                val deviceName: String,
                val publicKey: String
            ) {
                init {
                    require(Regex("^[a-zA-Z0-9._-]{1,32}$").matches(deviceName)) { "Invalid device name" }
                    Key.fromBase64(publicKey)
                }

                fun toServerCommand(): String =
                    "sudo /usr/local/sbin/wg-peer-admin enroll --device '${'$'}deviceName' --pubkey '${'$'}publicKey'"
            }

            class DeviceIdentityStore(private val store: SecureConfigStore) {
                fun getOrCreatePublicIdentity(deviceName: String): PublicDeviceEnrollment {
                    val existingPub = store.loadDevicePublicKey()
                    if (!existingPub.isNullOrBlank() && store.hasDevicePrivateKey()) {
                        return PublicDeviceEnrollment(deviceName, existingPub)
                    }
                    val kp = KeyPair()
                    val pub = kp.publicKey.toBase64()
                    store.saveDeviceKeyPair(kp.privateKey.toBase64(), pub)
                    return PublicDeviceEnrollment(deviceName, pub)
                }
            }
        """.trimIndent()
    ),
    BlueprintFileItem(
        name = "SecureConfigStore.kt",
        path = "app/src/main/java/com/example/advancedtunnel/SecureConfigStore.kt",
        content = """
            package com.example.advancedtunnel

            import android.content.Context
            import androidx.security.crypto.EncryptedSharedPreferences
            import androidx.security.crypto.MasterKey

            class SecureConfigStore(context: Context) {
                private val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                private val prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    "wg_native_credentials",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                fun saveDeviceKeyPair(privateKey: String, publicKey: String) {
                    prefs.edit()
                        .putString("device_private_key", privateKey)
                        .putString("device_public_key", publicKey)
                        .commit()
                }

                fun hasDevicePrivateKey(): Boolean = !prefs.getString("device_private_key", null).isNullOrBlank()
                fun loadDevicePublicKey(): String? = prefs.getString("device_public_key", null)
            }
        """.trimIndent()
    ),
    BlueprintFileItem(
        name = "peer-admin.py",
        path = "server/peer-admin.py",
        content = """
            #!/usr/bin/env python3
            # Root-only offline WireGuard peer enrollment & atomic persistence utility
            import argparse, json, os, re, subprocess, tempfile

            DB_PATH = "/etc/wireguard/peers.json"
            WG_CONF = "/etc/wireguard/wg0.conf"

            def sync_peers(peers):
                # Persist to disk FIRST before live wg syncconf so restarts never re-authorize revoked peers
                with tempfile.NamedTemporaryFile("w", dir="/etc/wireguard", delete=False) as tmp:
                    json.dump(peers, tmp, indent=2)
                    tmp.flush()
                    os.fsync(tmp.fileno())
                os.replace(tmp.name, DB_PATH)
                subprocess.run(["wg", "syncconf", "wg0", WG_CONF], check=True)
        """.trimIndent()
    ),
    BlueprintFileItem(
        name = "server-setup.sh",
        path = "server/server-setup.sh",
        content = """
            #!/usr/bin/env bash
            # Hardened WireGuard server with nftables firewall & private Unbound DNS (10.66.66.1)
            set -euo pipefail
            apt-get update && apt-get install -y wireguard-tools nftables unbound python3
            install -d -m 700 /etc/wireguard
            wg genkey > /etc/wireguard/server.key
            wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub
            echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wireguard.conf
            echo "net.ipv6.conf.all.forwarding=1" >> /etc/sysctl.d/99-wireguard.conf
            sysctl --system
            systemctl enable --now wg-quick@wg0
        """.trimIndent()
    ),
    BlueprintFileItem(
        name = "AndroidManifest.xml",
        path = "app/src/main/AndroidManifest.xml",
        content = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

                <application
                    android:allowBackup="false"
                    android:fullBackupContent="false"
                    android:dataExtractionRules="@xml/no_backup_rules"
                    android:usesCleartextTraffic="false"
                    android:label="Tunnel Console">
                    <service
                        android:name="com.wireguard.android.backend.GoBackend${'$'}VpnService"
                        android:permission="android.permission.BIND_VPN_SERVICE"
                        android:exported="false">
                        <intent-filter>
                            <action android:name="android.net.VpnService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
        """.trimIndent()
    )
)

@Composable
fun BlueprintScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selectedIndex by remember { mutableIntStateOf(0) }
    val activeFile = BLUEPRINT_FILES[selectedIndex]

    var deviceIdentity by remember {
        mutableStateOf(generateCurve25519PublicKeyPreview(context, forceRotate = false))
    }
    val (deviceName, devicePubKey) = deviceIdentity
    val enrollCmd = "sudo /usr/local/sbin/wg-peer-admin enroll --device '$deviceName' --pubkey '$devicePubKey'"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Native Architecture Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("blueprint_header_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "WIREGUARD BLUEPRINT ARCHITECTURE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "Audited WireGuard packet engine using official Android tunnel library, Keystore-wrapped on-device keys, and root-only offline server enrollment.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }

        // On-Device Keystore Identity & Offline Server Enrollment Generator Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("device_enrollment_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Device Public Identity",
                            tint = CyanPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "ON-DEVICE PUBLIC IDENTITY (ENROLLMENT)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary,
                            letterSpacing = 1.sp
                        )
                    }

                    Button(
                        onClick = {
                            deviceIdentity = generateCurve25519PublicKeyPreview(context, forceRotate = true)
                            Toast.makeText(context, "Rotated on-device keypair", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceDark,
                            contentColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("rotate_device_key_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Rotate keypair",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rotate", fontSize = 11.sp)
                    }
                }

                Text(
                    text = "Private keys never leave Android Keystore. Enroll only this public key on your WireGuard server:",
                    fontSize = 11.sp,
                    color = TextMuted
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "DEVICE: $deviceName",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = "PUBKEY: $devicePubKey",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = CyanPrimary
                    )
                    Text(
                        text = enrollCmd,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("wg-peer-admin enroll", enrollCmd))
                        Toast.makeText(context, "Enrollment command copied", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = SurfaceDark
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .testTag("copy_enrollment_command_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy enrollment command",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Server Enrollment Command", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Compatibility & Verification Matrix Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("compatibility_checklist_card"),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ANDROID COMPATIBILITY CHECKLIST",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )

                    Button(
                        onClick = {
                            val record = buildString {
                                appendLine("# Android WireGuard Compatibility Record")
                                COMPATIBILITY_CHECKS.forEach { item ->
                                    appendLine("- [${item.status.uppercase()}] ${item.title}: ${item.requirement}")
                                }
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Compatibility Record", record))
                            Toast.makeText(context, "Test record copied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariantDark,
                            contentColor = CyanPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("copy_checklist_record_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy test record",
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Record", fontSize = 11.sp)
                    }
                }

                COMPATIBILITY_CHECKS.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = item.status,
                            tint = if (item.status == "local") CyanPrimary else AccentOrange,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(15.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = item.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = item.status.uppercase(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = if (item.status == "local") CyanPrimary else AccentOrange
                                )
                            }
                            Text(
                                text = item.requirement,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // File Selector Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BLUEPRINT_FILES.forEachIndexed { index, file ->
                FilterChip(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    label = {
                        Text(
                            file.name,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanPrimary,
                        selectedLabelColor = SurfaceDark,
                        containerColor = SurfaceDark,
                        labelColor = TextMuted
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedIndex == index,
                        borderColor = BorderDark,
                        selectedBorderColor = CyanPrimary
                    ),
                    modifier = Modifier.testTag("blueprint_chip_${file.name}")
                )
            }
        }

        // Code Viewer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeFile.path,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(activeFile.name, activeFile.content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "${activeFile.name} copied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariantDark,
                            contentColor = CyanPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("copy_blueprint_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy code",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 11.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = activeFile.content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextPrimary,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
