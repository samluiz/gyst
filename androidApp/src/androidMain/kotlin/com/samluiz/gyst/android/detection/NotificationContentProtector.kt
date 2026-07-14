package com.samluiz.gyst.android.detection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the minimal provider-bound text while it waits for durable background analysis. */
interface NotificationContentProtector {
    fun protect(plainText: String): String

    fun reveal(protectedText: String): String
}

class AndroidKeystoreNotificationContentProtector : NotificationContentProtector {
    override fun protect(plainText: String): String {
        require(plainText.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.encodeToByteArray())
        return listOf(
            FORMAT_PREFIX,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString(DELIMITER)
    }

    override fun reveal(protectedText: String): String {
        val parts = protectedText.split(DELIMITER, limit = 3)
        require(parts.size == 3 && parts.first() == FORMAT_PREFIX) { "Unsupported protected-content format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).decodeToString()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "gyst.notification-analysis.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FORMAT_PREFIX = "gyst-enc-v1"
        const val DELIMITER = ":"
    }
}
