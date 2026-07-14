package com.samluiz.gyst.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.samluiz.gyst.domain.service.AdvisorSecretStore
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidAdvisorSecretStore(context: Context) : AdvisorSecretStore {
    private val preferences = context.getSharedPreferences("advisor_secrets", Context.MODE_PRIVATE)

    override suspend fun readApiKey(): String? = readApiKey(DEFAULT_PROFILE_SLOT)

    override suspend fun readApiKey(profileId: String): String? {
        val encrypted = preferences.getString(ciphertextKey(profileId), null) ?: return null
        val iv = preferences.getString(ivKey(profileId), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString()
        }.getOrNull()
    }

    override suspend fun writeApiKey(apiKey: String) = writeApiKey(DEFAULT_PROFILE_SLOT, apiKey)

    override suspend fun writeApiKey(
        profileId: String,
        apiKey: String,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.encodeToByteArray())
        preferences
            .edit()
            .putString(ciphertextKey(profileId), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey(profileId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun clearApiKey() = clearApiKey(DEFAULT_PROFILE_SLOT)

    override suspend fun clearApiKey(profileId: String) {
        preferences.edit().remove(ciphertextKey(profileId)).remove(ivKey(profileId)).apply()
    }

    override suspend fun clearAllApiKeys() = preferences.edit().clear().apply()

    private fun ciphertextKey(profileId: String): String = "api_key_ciphertext${profileSuffix(profileId)}"

    private fun ivKey(profileId: String): String = "api_key_iv${profileSuffix(profileId)}"

    private fun profileSuffix(profileId: String): String {
        if (profileId == DEFAULT_PROFILE_SLOT) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(profileId.encodeToByteArray())
        return "_" + digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "gyst_advisor_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_PROFILE_SLOT = "default"
    }
}
