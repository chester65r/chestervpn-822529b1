package com.example.advancedtunnel

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

/**
 * Hardware-backed Android Keystore AES-256-GCM encrypted storage for VPN profiles
 * and WireGuard device identities. Fails closed on tamper or key loss.
 */
class SecureConfigStore(context: Context, private val name: String = "profile") {
    init {
        require(name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
    }

    private val alias = "tunnel_credential_$name"
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.enc"))
    private val aad = ("TunnelConsole:v1:${context.packageName}:$name").toByteArray()

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key unavailable" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun save(text: String) {
        val plain = text.toByteArray(Charsets.UTF_8)
        require(plain.size in 1..65536) { "Invalid credential size" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key(true))
                updateAAD(aad)
            }
            check(cipher.iv.size == 12)
            val sealed = cipher.doFinal(plain)
            val out = file.startWrite()
            try {
                out.write(1)
                out.write(cipher.iv)
                out.write(sealed)
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Never create a replacement key to decrypt old ciphertext. Fail closed and re-enroll. */
    @Synchronized
    fun load(): String? {
        if (!file.baseFile.exists()) return null
        return try {
            require(file.baseFile.length() in 30L..65565L)
            val bytes = file.readFully()
            require(bytes.size >= 30 && bytes[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 1, 12))
                updateAAD(aad)
            }
            val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
            try {
                String(plain, Charsets.UTF_8)
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
