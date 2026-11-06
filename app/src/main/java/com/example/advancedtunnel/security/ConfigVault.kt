package com.example.advancedtunnel.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultRelayNode(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val sni: String,
    val transport: String,
    val websocketPath: String
)

data class VaultPayloadPreset(
    val id: String,
    val name: String,
    val sni: String,
    val template: String
)

data class DecryptedVaultBundle(
    val version: Int,
    val revisionTimestamp: Long,
    val nodes: List<VaultRelayNode>,
    val presets: List<VaultPayloadPreset>
)

/**
 * Production Encrypted Configuration Vault combining:
 * 1. PBKDF2-HMAC-SHA256 + AES-256-GCM (96-bit IV, 128-bit auth tag, AAD metadata binding)
 *    for decrypting remote `.json` configuration envelopes.
 * 2. Hardware-backed AndroidKeyStore AES-256-GCM master key wrapping for at-rest local persistence.
 */
class ConfigVault(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "tunnel_console_vault_master_aes256"
        private const val PREFS_NAME = "tunnel_console_encrypted_vault_store"
        private const val PREF_KEY_CIPHERTEXT = "vault_local_ciphertext"
        private const val PREF_KEY_IV = "vault_local_iv"

        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PBKDF2_ITERATIONS = 120_000
        private const val AES_KEY_SIZE_BITS = 256
    }

    /**
     * Derives a 256-bit AES key from a passphrase and 128-bit salt using PBKDF2WithHmacSHA256.
     */
    fun deriveAes256KeyFromPassphrase(passphrase: CharArray, saltBytes: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase, saltBytes, PBKDF2_ITERATIONS, AES_KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val rawBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(rawBytes, KeyProperties.KEY_ALGORITHM_AES)
    }

    /**
     * Retrieves or generates a non-exportable hardware-backed AES-256-GCM SecretKey
     * inside the Android Keystore for local at-rest storage.
     */
    private fun getOrCreateKeystoreSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts a plaintext JSON configuration bundle into a portable AES-256-GCM envelope
     * with Additional Authenticated Data (AAD) binding version, keyId, and expiration timestamps.
     */
    fun encryptRemoteEnvelope(
        plaintextJson: String,
        passphrase: CharArray,
        keyId: String = "vault-v2",
        ttlSeconds: Long = 2_592_000L
    ): String {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { random.nextBytes(it) }
        val secretKey = deriveAes256KeyFromPassphrase(passphrase, salt)

        val nowSec = System.currentTimeMillis() / 1000L
        val expSec = nowSec + ttlSeconds
        val schemaVersion = 2

        val aad = "v=$schemaVersion;kid=$keyId;iat=$nowSec;exp=$expSec"
            .toByteArray(StandardCharsets.UTF_8)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintextJson.toByteArray(StandardCharsets.UTF_8))

        return JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("keyId", keyId)
            put("kdfSaltB64", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("ivB64", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("ciphertextB64", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            put("issuedAtEpochSec", nowSec)
            put("expiresAtEpochSec", expSec)
        }.toString()
    }

    /**
     * Decrypts a remote AES-256-GCM JSON envelope, validates its AAD and TTL,
     * caches the verified plaintext inside Android Keystore-protected storage,
     * and returns the strongly-typed DecryptedVaultBundle.
     */
    fun decryptAndPersistRemoteEnvelope(
        envelopeJson: String,
        passphrase: CharArray,
        currentEpochSec: Long = System.currentTimeMillis() / 1000L
    ): Result<DecryptedVaultBundle> {
        return try {
            val env = JSONObject(envelopeJson)
            val schemaVersion = env.getInt("schemaVersion")
            require(schemaVersion == 2) { "Unsupported vault schemaVersion: $schemaVersion" }

            val keyId = env.getString("keyId")
            val issuedAt = env.getLong("issuedAtEpochSec")
            val expiresAt = env.getLong("expiresAtEpochSec")
            require(currentEpochSec <= expiresAt) {
                "Vault bundle expired at epoch $expiresAt (current: $currentEpochSec)"
            }

            val salt = Base64.decode(env.getString("kdfSaltB64"), Base64.NO_WRAP)
            val iv = Base64.decode(env.getString("ivB64"), Base64.NO_WRAP)
            require(iv.size == GCM_IV_LENGTH_BYTES) { "Invalid GCM IV length: ${iv.size}" }

            val ciphertext = Base64.decode(env.getString("ciphertextB64"), Base64.NO_WRAP)
            val secretKey = deriveAes256KeyFromPassphrase(passphrase, salt)

            val aad = "v=$schemaVersion;kid=$keyId;iat=$issuedAt;exp=$expiresAt"
                .toByteArray(StandardCharsets.UTF_8)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(aad)

            val decryptedBytes = cipher.doFinal(ciphertext)
            val decryptedJsonString = String(decryptedBytes, StandardCharsets.UTF_8)

            val bundle = parseDecryptedJson(JSONObject(decryptedJsonString))
            persistToKeystoreVault(decryptedJsonString)
            Result.success(bundle)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Encrypts and persists the validated JSON configuration using the non-exportable
     * Android Keystore AES-256-GCM key.
     */
    fun persistToKeystoreVault(plaintextJson: String) {
        val keystoreKey = getOrCreateKeystoreSecretKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintextJson.toByteArray(StandardCharsets.UTF_8))

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(PREF_KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }

    /**
     * Loads and decrypts the cached configuration bundle from Android Keystore storage.
     */
    fun loadFromKeystoreVault(): Result<DecryptedVaultBundle> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ivB64 = prefs.getString(PREF_KEY_IV, null)
                ?: return Result.failure(IllegalStateException("No cached vault IV found"))
            val cipherB64 = prefs.getString(PREF_KEY_CIPHERTEXT, null)
                ?: return Result.failure(IllegalStateException("No cached vault ciphertext found"))

            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(cipherB64, Base64.NO_WRAP)

            val keystoreKey = getOrCreateKeystoreSecretKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            val rootObj = JSONObject(String(decryptedBytes, StandardCharsets.UTF_8))
            Result.success(parseDecryptedJson(rootObj))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseDecryptedJson(root: JSONObject): DecryptedVaultBundle {
        val version = root.optInt("version", 1)
        val revisionTimestamp = root.optLong("revisionTimestamp", System.currentTimeMillis())

        val nodesArray = root.optJSONArray("nodes") ?: JSONArray()
        val nodes = buildList {
            for (i in 0 until nodesArray.length()) {
                val item = nodesArray.getJSONObject(i)
                val port = item.optInt("port", 443)
                require(port in 1..65535) { "Invalid node port: $port" }
                add(
                    VaultRelayNode(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        host = item.getString("host"),
                        port = port,
                        sni = item.optString("sni", item.getString("host")),
                        transport = item.optString("transport", "VLESS-WS-TLS"),
                        websocketPath = item.optString("websocketPath", "/ws-tunnel")
                    )
                )
            }
        }

        val presetsArray = root.optJSONArray("presets") ?: JSONArray()
        val presets = buildList {
            for (i in 0 until presetsArray.length()) {
                val item = presetsArray.getJSONObject(i)
                add(
                    VaultPayloadPreset(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        sni = item.getString("sni"),
                        template = item.getString("template")
                    )
                )
            }
        }

        return DecryptedVaultBundle(
            version = version,
            revisionTimestamp = revisionTimestamp,
            nodes = nodes,
            presets = presets
        )
    }

    /**
     * Builds a plaintext JSON string from the active TunnelProfile and RelayServer list,
     * persists a copy into the hardware-backed Android Keystore, and returns a `TCFG://`
     * Base64-wrapped AES-256-GCM encrypted envelope protected by the user's passphrase.
     */
    fun exportEncryptedTcfgCode(
        activeIp: String,
        activePort: Int,
        activeSni: String,
        activePayload: String,
        serversJsonArray: JSONArray,
        passphrase: String
    ): String {
        val root = JSONObject().apply {
            put("version", 2)
            put("revisionTimestamp", System.currentTimeMillis())
            put("nodes", serversJsonArray)
            put(
                "presets",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "active")
                            put("name", "$activeIp:$activePort")
                            put("sni", activeSni)
                            put("template", activePayload)
                        }
                    )
                }
            )
        }
        val plaintext = root.toString()
        persistToKeystoreVault(plaintext)
        val envelopeJson = encryptRemoteEnvelope(
            plaintextJson = plaintext,
            passphrase = passphrase.toCharArray()
        )
        val encoded = Base64.encodeToString(
            envelopeJson.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        return "TCFG://$encoded"
    }

    /**
     * Accepts either a `TCFG://` Base64-wrapped envelope or a raw JSON envelope string,
     * decrypts it with the provided passphrase, and persists it in Android Keystore.
     */
    fun importEncryptedTcfgCode(
        rawCode: String,
        passphrase: String
    ): Result<DecryptedVaultBundle> {
        return try {
            val trimmed = rawCode.trim()
            val envelopeJson = if (trimmed.startsWith("TCFG://", ignoreCase = true)) {
                val b64 = trimmed.substring(7).trim()
                String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
            } else {
                trimmed
            }
            decryptAndPersistRemoteEnvelope(envelopeJson, passphrase.toCharArray())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun hasKeystoreBackup(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(PREF_KEY_CIPHERTEXT) && prefs.contains(PREF_KEY_IV)
    }
}

