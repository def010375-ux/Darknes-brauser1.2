package com.darknes.tor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val IdInk = Color(0xFF050404)
private val IdPaper = Color(0xFFF6F1EC)
private val IdOrange = Color(0xFFFF8A1F)
private val IdMuted = Color(0xFF8B8580)
private val IdBorder = Color(0x35FFF4E8)

private data class DarknesIdUser(val username: String, val email: String)
private data class DarknesIdSession(val token: String, val user: DarknesIdUser)

private object DarknesIdTokenStore {
    private const val alias = "darknes_id_access_token_key"
    private const val prefsName = "darknes_id_secure_session"

    fun read(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val iv = Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString("data", null), Base64.NO_WRAP)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    fun save(context: Context, token: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var key = keyStore.getKey(alias, null) as? SecretKey
        if (key == null) {
            key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                generateKey()
            }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

private object DarknesIdApi {
    private const val baseUrl = "https://darknes-id.onrender.com"

    suspend fun register(username: String, email: String, password: String): DarknesIdSession =
        request("/v1/auth/register", JSONObject()
            .put("username", username)
            .put("email", email)
            .put("password", password))

    suspend fun login(login: String, password: String): DarknesIdSession =
        request("/v1/auth/login", JSONObject()
            .put("login", login)
            .put("password", password))

    suspend fun profile(token: String): DarknesIdUser = withContext(Dispatchers.IO) {
        val connection = (URL("$baseUrl/v1/auth/me").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 90000
            readTimeout = 90000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)
            if (code !in 200..299) throw IllegalStateException(json.optString("error", "Не удалось загрузить профиль."))
            val user = json.getJSONObject("user")
            DarknesIdUser(user.optString("username"), user.optString("email"))
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun request(path: String, payload: JSONObject): DarknesIdSession = withContext(Dispatchers.IO) {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 90000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)
            if (code !in 200..299) throw IllegalStateException(json.optString("error", "Ошибка Darknes ID ($code)."))
            val user = json.getJSONObject("user")
            DarknesIdSession(
                json.getString("accessToken"),
                DarknesIdUser(user.optString("username"), user.optString("email"))
            )
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
internal fun DarknesIdPanel(onAuthenticated: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRegister by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf<DarknesIdUser?>(null) }
    var loading by remember { mutableStateOf(false) }
    var checkingSavedSession by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val saved = DarknesIdTokenStore.read(context)
        if (saved != null) {
            token = saved
            loading = true
            try {
                user = DarknesIdApi.profile(saved)
                onAuthenticated()
            } catch (e: Exception) {
                DarknesIdTokenStore.clear(context)
                token = null
                message = e.message ?: "Сеанс завершён. Войдите снова."
            } finally {
                loading = false
                checkingSavedSession = false
            }
        } else {
            checkingSavedSession = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Darknes ID", color = IdPaper, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (checkingSavedSession) {
            Text("Восстанавливаем защищённую сессию…", color = IdMuted, fontSize = 13.sp)
            CircularProgressIndicator(color = IdOrange, modifier = Modifier.size(25.dp))
        } else if (user != null && token != null) {
            Text("Вы вошли в аккаунт", color = IdOrange, fontSize = 14.sp)
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF171311), RoundedCornerShape(18.dp))
                    .border(1.dp, IdBorder, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(user!!.username, color = IdPaper, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(user!!.email, color = IdMuted, fontSize = 14.sp)
            }
            Button(
                onClick = {
                    DarknesIdTokenStore.clear(context)
                    token = null
                    user = null
                    message = "Вы вышли из аккаунта."
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = IdOrange, contentColor = IdInk),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Выйти", fontWeight = FontWeight.Bold) }
        } else {
            Text(
                if (isRegister) "Создайте единый аккаунт для приложений Darknes." else "Войдите, чтобы использовать свой аккаунт Darknes.",
                color = IdMuted, fontSize = 13.sp, lineHeight = 19.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !isRegister, onClick = { isRegister = false; message = "" }, label = { Text("Вход") })
                FilterChip(selected = isRegister, onClick = { isRegister = true; message = "" }, label = { Text("Регистрация") })
            }
            if (isRegister) {
                IdField(username, { username = it }, "Имя пользователя")
                IdField(email, { email = it }, "Электронная почта", KeyboardType.Email)
            } else {
                IdField(login, { login = it }, "Почта или имя пользователя")
            }
            IdField(password, { password = it }, "Пароль", password = true)
            if (isRegister) {
                Text("Имя: 3–24 символа (латиница, цифры, _). Пароль: минимум 10 символов.", color = IdMuted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            if (message.isNotBlank()) {
                Text(message, color = Color(0xFFFFB08A), fontSize = 13.sp, lineHeight = 18.sp)
            }
            Button(
                onClick = {
                    message = ""
                    if (password.length < 10) {
                        message = "Пароль должен содержать минимум 10 символов."
                    } else if (isRegister && (!Regex("^[A-Za-z0-9_]{3,24}$").matches(username) || !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email))) {
                        message = "Проверьте имя пользователя и адрес почты."
                    } else if (!isRegister && login.isBlank()) {
                        message = "Введите почту или имя пользователя."
                    } else {
                        loading = true
                        scope.launch {
                            try {
                                val result = if (isRegister) DarknesIdApi.register(username.trim(), email.trim(), password)
                                    else DarknesIdApi.login(login.trim(), password)
                                DarknesIdTokenStore.save(context, result.token)
                                token = result.token
                                user = result.user
                                password = ""
                                message = ""
                                onAuthenticated()
                            } catch (e: Exception) {
                                message = e.message ?: "Не удалось связаться с Darknes ID."
                            } finally {
                                loading = false
                            }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = IdOrange, contentColor = IdInk),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (loading) "Подождите..." else if (isRegister) "Создать аккаунт" else "Войти в Darknes", fontWeight = FontWeight.Bold)
            }
        }
        if (loading && user == null) {
            CircularProgressIndicator(color = IdOrange, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun IdField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = IdPaper,
            unfocusedTextColor = IdPaper,
            focusedBorderColor = IdOrange,
            unfocusedBorderColor = IdBorder,
            focusedLabelColor = IdOrange,
            unfocusedLabelColor = IdMuted,
            cursorColor = IdOrange
        )
    )
}
