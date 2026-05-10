package com.anezium.rokidlive.phone

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(private val preferences: SharedPreferences) {
    fun getString(key: String, defaultValue: String = ""): String {
        val encryptedKey = encryptedPreferenceKey(key)
        val encrypted = preferences.getString(encryptedKey, null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching { decrypt(encrypted) }
                .onFailure { preferences.edit().remove(encryptedKey).apply() }
                .getOrDefault(defaultValue)
        }

        val legacy = preferences.getString(key, null)
        if (!legacy.isNullOrBlank()) {
            putString(key, legacy)
            preferences.edit().remove(key).apply()
            return legacy
        }
        return defaultValue
    }

    fun putString(key: String, value: String) {
        if (value.isBlank()) {
            remove(key)
            return
        }
        preferences.edit()
            .putString(encryptedPreferenceKey(key), encrypt(value))
            .remove(key)
            .apply()
    }

    fun remove(key: String) {
        preferences.edit()
            .remove(encryptedPreferenceKey(key))
            .remove(key)
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(
            FORMAT_VERSION,
            cipher.iv.base64(),
            cipherText.base64()
        ).joinToString(":")
    }

    private fun decrypt(value: String): String {
        val parts = value.split(":")
        require(parts.size == 3 && parts[0] == FORMAT_VERSION) { "Unsupported secret format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, parts[1].fromBase64()))
        return cipher.doFinal(parts[2].fromBase64()).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private fun encryptedPreferenceKey(key: String): String = "${SECRET_PREFIX}$key"

    private companion object {
        private const val SECRET_PREFIX = "__secret_"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rokid_live_studio_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = "v1"
        private const val GCM_TAG_BITS = 128
    }
}
