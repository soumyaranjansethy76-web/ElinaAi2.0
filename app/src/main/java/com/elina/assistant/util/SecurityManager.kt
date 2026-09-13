package com.elina.assistant.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityManager(context: Context) {
    private val prefs = context.getSharedPreferences("elina_secret_ciphertext", Context.MODE_PRIVATE)
    private val alias = "elina_secret_aes"
    private val key: SecretKey by lazy {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun saveApiCredential(value: String) {
        if (value.isBlank()) { prefs.edit().remove("api").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val blob = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString("api", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }
    fun readApiCredential(): String? = prefs.getString("api", null)?.let {
        runCatching { val blob=Base64.decode(it, Base64.NO_WRAP); val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,blob.copyOfRange(0,12))); String(cipher.doFinal(blob.copyOfRange(12,blob.size))) }.getOrNull()
    }
    fun hasCredential() = prefs.contains("api")
}
