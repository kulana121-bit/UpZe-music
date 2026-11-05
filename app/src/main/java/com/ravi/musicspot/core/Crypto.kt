package com.ravi.musicspot.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens secrets. The production implementation keeps its key in the Android Keystore. */
interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String
}

/**
 * AES-256-GCM with a non-exportable key in AndroidKeyStore (hardware-backed where the device has it).
 * Output: base64(iv[12] || ciphertext+tag). Equivalent to what EncryptedSharedPreferences/Tink do for values,
 * without the deprecated security-crypto dependency.
 */
class GcmBox(private val keyProvider: () -> SecretKey) : SecretBox {
    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyProvider())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(out)
    }

    override fun open(sealed: String): String {
        val raw = Base64.getDecoder().decode(sealed)
        require(raw.size > 12 + 16) { "sealed value too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "musicspot.accounts.v1"

        /**
         * Key lookup/creation is lazy (first seal/open, always off the main thread) and defensive: a broken or
         * unreadable entry (seen after OS updates / restores on some devices) is deleted and recreated once, and
         * key generation uses only parameters every API 23+ Keystore supports (no StrongBox, no user-auth,
         * no unlocked-device requirement). A failure surfaces as an exception from seal/open, which callers
         * catch; it never happens during app start.
         */
        fun keystore(): GcmBox {
            var cached: SecretKey? = null
            return GcmBox {
                cached ?: synchronized(this) {
                    cached ?: (runCatching { loadOrCreate() }.getOrElse {
                        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
                        loadOrCreate()
                    }).also { cached = it }
                }
            }
        }

        private fun loadOrCreate(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}
