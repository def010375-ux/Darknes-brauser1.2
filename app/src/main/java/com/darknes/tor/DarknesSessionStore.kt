package com.darknes.tor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class DarknesSession(
    val accessToken: String,
    val refreshToken: String,
    val email: String,
    val userId: String,
    val expiresAt: Long
)

internal object DarknesSessionStore {
    private const val PREFS = "darknes_id_secure"
    private const val SESSION_KEY = "encrypted_session"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_ALIAS = "darknes_id_session_v1"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, session: DarknesSession) {
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("email", session.email)
            .put("user_id", session.userId)
            .put("expires_at", session.expiresAt)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json)
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SESSION_KEY, encoded).apply()
    }

    fun load(context: Context): DarknesSession? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SESSION_KEY, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12)
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        DarknesSession(
            json.getString("access_token"),
            json.getString("refresh_token"),
            json.optString("email"),
            json.optString("user_id"),
            json.optLong("expires_at", 0L)
        )
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(SESSION_KEY).apply()
    }
}

internal suspend fun refreshDarknesSession(
    context: Context,
    oldSession: DarknesSession
): DarknesSession? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL("${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
        }
        val body = JSONObject().put("refresh_token", oldSession.refreshToken).toString()
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) {
            // A failed refresh does not prove that the stored refresh token is
            // invalid: the response may be caused by a temporary server or
            // network problem. Keep the encrypted session so the user can
            // retry instead of being silently signed out.
            return@withContext null
        }
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) return@withContext null
        val user = json.optJSONObject("user")
        val session = DarknesSession(
            accessToken = access,
            refreshToken = refresh,
            email = user?.optString("email").orEmpty().ifBlank { oldSession.email },
            userId = user?.optString("id").orEmpty().ifBlank { oldSession.userId },
            expiresAt = System.currentTimeMillis() / 1000L + json.optLong("expires_in", 3600L)
        )
        DarknesSessionStore.save(context, session)
        session
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

internal suspend fun revokeDarknesSession(session: DarknesSession) = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL("${BuildConfig.SUPABASE_URL}/auth/v1/logout")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Accept", "application/json")
        }
        connection.responseCode
    } catch (_: Exception) {
        // Local credentials are still cleared by the caller.
    } finally {
        connection?.disconnect()
    }
}
