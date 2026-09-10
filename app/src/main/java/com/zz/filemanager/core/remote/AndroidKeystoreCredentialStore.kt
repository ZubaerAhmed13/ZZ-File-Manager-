package com.zz.filemanager.core.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecureCredentialStore {
    fun putBytes(reference: String, secret: ByteArray)
    fun getBytes(reference: String): ByteArray?
    fun putChars(reference: String, secret: CharArray)
    fun getChars(reference: String): CharArray?
    fun remove(reference: String)
}

/**
 * Stores only AES-GCM ciphertext/IV in app-private preferences. The encryption key is generated
 * inside Android Keystore and is never exported. Plain credentials are kept only for the scope of
 * an operation and caller-provided buffers are never persisted or logged.
 */
class AndroidKeystoreCredentialStore(context: Context) : SecureCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun putBytes(reference: String, secret: ByteArray) {
        require(reference.isNotBlank())
        val copy = secret.copyOf()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(copy)
            val payload = buildString {
                append(FORMAT_VERSION)
                append(':')
                append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                append(':')
                append(Base64.encodeToString(encrypted, Base64.NO_WRAP))
            }
            preferences.edit().putString(reference, payload).commit()
            encrypted.fill(0)
        } finally {
            copy.fill(0)
        }
    }

    @Synchronized
    override fun getBytes(reference: String): ByteArray? {
        val payload = preferences.getString(reference, null) ?: return null
        val parts = payload.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != FORMAT_VERSION) return null
        val iv = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull() ?: return null
        val encrypted = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted)
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    override fun putChars(reference: String, secret: CharArray) {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        try {
            putBytes(reference, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override fun getChars(reference: String): CharArray? {
        val bytes = getBytes(reference) ?: return null
        return try {
            val decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            CharArray(decoded.remaining()).also(decoded::get)
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized
    override fun remove(reference: String) {
        preferences.edit().remove(reference).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "zz_step5_secure_credentials"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zz_file_manager_step5_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION = "v1"

        fun passwordReference(connectionId: String) = "connection:$connectionId:password"
        fun privateKeyReference(connectionId: String) = "connection:$connectionId:private-key"
        fun privateKeyPassphraseReference(connectionId: String) = "connection:$connectionId:key-passphrase"
        fun oauthRefreshReference(accountId: String) = "oauth:$accountId:refresh-token"
    }
}
