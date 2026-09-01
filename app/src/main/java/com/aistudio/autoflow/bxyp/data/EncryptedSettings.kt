package com.aistudio.autoflow.bxyp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only local operation settings/logs, encrypted with a non-exportable Android Keystore key. */
class EncryptedSettings(context: Context) {
    private val preferences = context.getSharedPreferences("private_lot_settings", Context.MODE_PRIVATE)

    @Synchronized
    fun appendLog(entry: String) {
        val current = decrypt("trade_log")
            ?.lineSequence()
            ?.filter(String::isNotBlank)
            ?.toList()
            ?.takeLast(MAX_LOG_ENTRIES - 1)
            .orEmpty()
        preferences.edit().putString("trade_log", encrypt((current + entry).joinToString("\n"))).apply()
    }

    @Synchronized
    fun readLog(): List<String> = decrypt("trade_log")
        ?.lineSequence()
        ?.filter(String::isNotBlank)
        ?.toList()
        .orEmpty()

    @Synchronized
    fun writeStep(value: String) = putEncrypted(STEP_NAME, value)

    @Synchronized
    fun readStep(defaultValue: String): String = decrypt(STEP_NAME) ?: defaultValue

    @Synchronized
    fun writeReferenceOffset(value: String) = putEncrypted(OFFSET_NAME, value)

    @Synchronized
    fun readReferenceOffset(defaultValue: String): String = decrypt(OFFSET_NAME) ?: defaultValue

    private fun putEncrypted(name: String, value: String) {
        preferences.edit().putString(name, encrypt(value)).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(name: String): String? {
        return try {
            val pieces = preferences.getString(name, null)?.split(".") ?: return null
            if (pieces.size != 2) return null
            val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(pieces[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            cipher.doFinal(encrypted).decodeToString()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mt5_lot_settings_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_LOG_ENTRIES = 50
        const val STEP_NAME = "adjustment_step"
        const val OFFSET_NAME = "reference_offset"
    }
}
