package com.auroratv.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.auroratv.core.Log
import com.auroratv.platform.SecureVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets encrypted with an AES-256-GCM key that never leaves the Android Keystore (hardware-backed
 * where the device supports it). Ciphertext + IV are stored in private SharedPreferences.
 */
class KeystoreVault(context: Context) : SecureVault {
    private val prefs = context.getSharedPreferences("aurora_vault", Context.MODE_PRIVATE)
    private val alias = "aurora_token_key"
    override val description = "Android Keystore (AES-256-GCM)"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(key, packed).apply()
    }

    override fun get(key: String): String? {
        val packed = prefs.getString(key, null) ?: return null
        return try {
            val (iv, data) = packed.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Key invalidated (e.g. restored backup on a new device): drop the secret, user signs in again.
            Log.w("Vault", "Could not decrypt stored secret; removing it", t)
            remove(key)
            null
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
