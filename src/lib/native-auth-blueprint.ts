// Displayed Android/server source only. Never handle native credentials in the browser.
export const secureStoreSource = String.raw`package com.example.advancedtunnel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore encrypts at rest; WireGuard still needs plaintext key material in process memory. */
class SecureConfigStore(context: Context, private val name: String = "profile") {
    init { require(name.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) }
    private val alias = "tunnel_credential_" + name
    private val file = AtomicFile(File(context.noBackupFilesDir, name + ".enc"))
    private val aad = ("TunnelConsole:v1:" + context.packageName + ":" + name).toByteArray()

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key unavailable" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        return generator.generateKey()
    }

    @Synchronized fun save(text: String) {
        val plain = text.toByteArray(Charsets.UTF_8)
        require(plain.size in 1..65536) { "Invalid credential size" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key(true)); updateAAD(aad)
            }
            check(cipher.iv.size == 12)
            val sealed = cipher.doFinal(plain)
            val out = file.startWrite()
            try {
                out.write(1); out.write(cipher.iv); out.write(sealed); file.finishWrite(out)
            } catch (e: Exception) { file.failWrite(out); throw e }
        } finally { plain.fill(0) }
    }

    /** Never create a replacement key to decrypt old ciphertext. Fail closed and re-enroll. */
    @Synchronized fun load(): String? {
        if (!file.baseFile.exists()) return null
        return try {
            require(file.baseFile.length() in 30L..65565L)
            val bytes = file.readFully()
            require(bytes.size >= 30 && bytes[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 1, 12)); updateAAD(aad)
            }
            val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
            try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
        } catch (_: Exception) { clear(); null }
    }

    @Synchronized fun clear() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}`;

export const deviceEnrollmentSource = String.raw`package com.example.advancedtunnel

import android.content.Context
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/** Offline administrator enrollment: only the public identity leaves the device. */
class DeviceEnrollment(context: Context) {
    private val identity = SecureConfigStore(context, "device_identity")
    private val profile = SecureConfigStore(context)

    @Synchronized fun publicKey(): String {
        val existing = identity.load()
        val pair = if (existing == null) KeyPair().also { profile.clear(); identity.save(it.privateKey.toBase64()) }
            else KeyPair(Key.fromBase64(existing))
        return pair.publicKey.toBase64()
    }

    /** The server key is confirmed out-of-band; the response contains a per-device PSK. */
    @Synchronized fun enroll(response: String, trustedServerKey: String) {
        require(response.length in 1..65536) { "Invalid enrollment size" }
        require(!Regex("(?im)^\\s*PrivateKey\\s*=").containsMatchIn(response)) {
            "Enrollment must not contain a private key"
        }
        val privateKey = identity.load() ?: error("Generate a device identity first")
        // Insert our local private key into the response's Interface section, never into the UI.
        val marker = Regex("(?m)^\\[Interface]\\s*$")
        require(marker.findAll(response).count() == 1) { "Invalid enrollment response" }
        val combined = marker.replaceFirst(response, "[Interface]\nPrivateKey = " + privateKey)
        val config = TunnelController.parse(combined)
        require(config.peers[0].publicKey == Key.fromBase64(trustedServerKey.trim())) {
            "Server identity does not match"
        }
        profile.save(combined)
    }

    // Caller must stop the tunnel before deleting or rotating credentials.
    @Synchronized fun clear() { profile.clear(); identity.clear() }
}`;

export const peerAdminSource = String.raw`#!/usr/bin/env python3
"""Root-only, offline WireGuard peer enrollment/revocation/PSK rotation.
No device private keys. No secrets in argv, output, or environment.
Enroll response contains a PSK: transfer over an authenticated confidential channel.
"""
import argparse, base64, fcntl, ipaddress, os, pathlib, re, subprocess, tempfile

def key(value):
    try:
        raw = base64.b64decode(value, validate=True)
        if len(raw) != 32 or not any(raw) or base64.b64encode(raw).decode() != value:
            raise ValueError()
    except Exception:
        raise ValueError("Invalid public key") from None
    return value

def run(args, data=None):
    result = subprocess.run(args, input=data, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("WireGuard operation failed")  # never echo subprocess output
    return result.stdout.strip()

def atomic(path, text):
    fd, tmp = tempfile.mkstemp(dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as stream:
            stream.write(text); stream.flush(); os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

def apply(config, interface, text):
    # Strip wg-quick fields without handing secrets to command-line arguments.
    with tempfile.TemporaryDirectory(dir=config.parent) as directory:
        candidate = pathlib.Path(directory) / "candidate.conf"
        atomic(candidate, text)
        stripped = run(["wg-quick", "strip", str(candidate)])
        runtime = pathlib.Path(directory) / "runtime.conf"
        atomic(runtime, stripped + "\n")
        run(["wg", "syncconf", interface, str(runtime)])
    atomic(config, text)

def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["enroll", "rotate", "revoke"])
    parser.add_argument("--config", default="/etc/wireguard/wg0.conf")
    parser.add_argument("--interface", default="wg0")
    parser.add_argument("--public-key", required=True)
    parser.add_argument("--address")
    parser.add_argument("--endpoint")
    parser.add_argument("--response")
    args = parser.parse_args()
    if os.geteuid() != 0: parser.error("Run as root on your WireGuard server")
    try:
        public = key(args.public_key)
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,15}", args.interface): raise ValueError("Invalid interface")
        config = pathlib.Path(args.config).resolve(strict=True)
        if not config.is_file() or config.stat().st_mode & 0o077: raise ValueError("Config must be owner-only")
        with open(str(config) + ".lock", "a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            text = config.read_text()
            parts = re.split(r"(?m)^\[Peer\]\s*$", text)
            matches = [i for i in range(1, len(parts)) if re.search(r"(?m)^PublicKey\s*=\s*" + re.escape(public) + r"\s*$", parts[i])]
            if args.action == "revoke":
                if len(matches) != 1: raise ValueError("Peer not registered")
                del parts[matches[0]]
                apply(config, args.interface, "[Peer]\n".join(parts))
                return
            if not args.response or not args.address or not args.endpoint: raise ValueError("Response, address and endpoint required")
            address = ipaddress.IPv4Interface(args.address)
            if address.network.prefixlen != 32 or address.ip not in ipaddress.IPv4Network("10.66.66.0/24") or address.ip in [ipaddress.IPv4Address("10.66.66.0"), ipaddress.IPv4Address("10.66.66.1"), ipaddress.IPv4Address("10.66.66.255")]:
                raise ValueError("Device needs a unique usable /32 in 10.66.66.0/24")
            if not re.fullmatch(r"[a-zA-Z0-9.-]+:[0-9]{1,5}", args.endpoint) or not 1 <= int(args.endpoint.rsplit(":", 1)[1]) <= 65535:
                raise ValueError("Invalid endpoint")
            if args.action == "enroll" and matches: raise ValueError("Already enrolled; use rotate")
            if args.action == "rotate" and len(matches) != 1: raise ValueError("Peer not registered")
            if matches: del parts[matches[0]]
            for section in parts[1:]:
                ips = re.search(r"(?m)^AllowedIPs\s*=\s*(.+)$", section)
                if ips and any(address.ip in ipaddress.ip_network(c.strip()) for c in ips[1].split(",")):
                    raise ValueError("Address already assigned")
            response = pathlib.Path(args.response)
            if response.exists() or response.is_symlink(): raise ValueError("Response already exists")
            psk = key(run(["wg", "genpsk"]))
            server = key(run(["wg", "show", args.interface, "public-key"]))
            section = "\nPublicKey = " + public + "\nPresharedKey = " + psk + "\nAllowedIPs = " + str(address) + "\n"
            updated = "[Peer]\n".join(parts).rstrip() + "\n\n[Peer]" + section
            enrollment = "[Interface]\nAddress = " + str(address) + "\nDNS = 10.66.66.1\nMTU = 1280\n\n[Peer]\nPublicKey = " + server + "\nPresharedKey = " + psk + "\nEndpoint = " + args.endpoint + "\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25\n"
            # Reserve the output before modifying authorization; remove it on failure.
            fd = os.open(response, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(fd, "w") as stream: stream.write(enrollment); stream.flush(); os.fsync(stream.fileno())
                if args.action == "rotate": run(["wg", "set", args.interface, "peer", public, "remove"])
                apply(config, args.interface, updated)
            except Exception:
                response.unlink(missing_ok=True); raise
    except Exception as error:
        parser.exit(1, (str(error) if isinstance(error, (ValueError, RuntimeError)) else "Enrollment operation failed") + "\n")

if __name__ == "__main__": main()
`;

export const nativeCredentialTestsSource = String.raw`package com.example.advancedtunnel

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.security.KeyStore
import com.wireguard.crypto.KeyPair
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** MUST run on Android: these assert real Keystore and enrollment behavior, not text snapshots. */
@RunWith(AndroidJUnit4::class)
class NativeCredentialsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = SecureConfigStore(context, "test_credentials")
    private val file = File(context.noBackupFilesDir, "test_credentials.enc")
    @After fun cleanup() { store.clear(); DeviceEnrollment(context).clear() }

    @Test fun encryptedRoundTripAndFreshNonce() {
        store.save("credential-test-value")
        val first = file.readBytes()
        assertEquals("credential-test-value", store.load())
        assertFalse(String(first).contains("credential-test-value"))
        store.save("credential-test-value")
        assertFalse(first.copyOfRange(1,13).contentEquals(file.readBytes().copyOfRange(1,13)))
    }
    @Test fun tamperFailsClosed() {
        store.save("test")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(store.load()); assertFalse(file.exists())
    }
    @Test fun truncationFailsClosed() {
        store.save("test"); file.writeBytes(byteArrayOf(1))
        assertNull(store.load())
    }
    @Test fun keyLossRequiresReEnrollment() {
        store.save("test")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("tunnel_credential_test_credentials")
        assertNull(store.load()); assertFalse(file.exists())
    }
    @Test fun clearDeletesCiphertextAndKeystoreKey() {
        store.save("test"); store.clear()
        assertNull(store.load())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("tunnel_credential_test_credentials"))
    }
    @Test fun enrollmentUsesLocalPrivateKeyAndPinnedServerIdentity() {
        val enrollment = DeviceEnrollment(context)
        val devicePublic = enrollment.publicKey()
        assertEquals(devicePublic, enrollment.publicKey())
        val server = KeyPair().publicKey.toBase64()
        val response = "[Interface]\nAddress = 10.66.66.2/32\nDNS = 10.66.66.1\nMTU = 1280\n\n[Peer]\nPublicKey = " + server + "\nPresharedKey = " + KeyPair().privateKey.toBase64() + "\nEndpoint = vpn.example.com:51820\nAllowedIPs = 0.0.0.0/0, ::/0\n"
        enrollment.enroll(response, server)
        val stored = SecureConfigStore(context).load()
        assertNotNull(stored)
        val config = TunnelController.parse(requireNotNull(stored))
        assertEquals(devicePublic, config.${"`"}interface${"`"}.keyPair.publicKey.toBase64())
        try { enrollment.enroll(response, KeyPair().publicKey.toBase64()); fail("Wrong server accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(stored, SecureConfigStore(context).load())
        try { enrollment.enroll(response.replace("[Interface]", "[Interface]\nPrivateKey = " + KeyPair().privateKey.toBase64()), server); fail("Remote private key accepted") }
        catch (_: IllegalArgumentException) { }
    }
}`;

export const enrollmentGuide = `# Native device authentication

WireGuard authenticates each device's unique public key plus a per-device preshared key.
App/MCP account tokens are NOT VPN credentials. No credentials enter the web dashboard.

1. On Android, generate the device identity. The WireGuard private key stays on that device,
   encrypted at rest under Android Keystore. Give only its public key to your administrator.
2. On an already running, owned WireGuard server, run as root:
   python3 peer-admin.py enroll --public-key '<DEVICE_PUBLIC_KEY>' --address 10.66.66.2/32 --endpoint vpn.example.com:51820 --response /root/device.enrollment.conf
3. Confirm the server public key independently over a trusted channel. Deliver the response
   (contains a secret PSK) over an authenticated confidential channel, not chat/clipboard/share codes.
   Enter the response and confirmed public server key on Android, then save enrollment.
   Delete the transferred response after enrollment. Only encrypted native files persist.
4. Grant VPN consent and connect. CONNECTED requires an authenticated server handshake.
5. Revoke immediately (removes live peer and persists config):
   python3 peer-admin.py revoke --public-key '<DEVICE_PUBLIC_KEY>'
6. Rotate the PSK (old profile stops working; securely deliver a new response):
   python3 peer-admin.py rotate --public-key '<DEVICE_PUBLIC_KEY>' --address 10.66.66.2/32 --endpoint vpn.example.com:51820 --response /root/device.rotated.conf
   To rotate the device key, revoke the old public key first, stop/delete the native identity,
   generate a new one, and enroll again. WireGuard has no automatic credential expiry.

Run native tests ONLY on a disposable test installation (they erase its test identity):
  ./gradlew connectedDebugAndroidTest
API 26/31/33/34+ emulator results, real Keystore behavior and hardware-backed key properties
are NOT RUN here. Physical devices are required for hardware/OEM and network behavior.
The wrapping AES key is non-exportable; the WireGuard private key is plaintext in engine
memory while used, so a compromised/rooted app process remains out of scope.
Server syncconf/persistence, secure delivery, reboot/crash recovery and live Android
authentication must be verified against your deployment. Local Go tests are engine evidence only.
`;