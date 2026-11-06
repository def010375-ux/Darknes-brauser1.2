package com.darknes.tor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val DarknesOrange = 0xFFFF9A43
private const val DarknesInk = 0xFF17110D
private const val DarknesMuted = 0xFFB8AAA0

@Composable
fun DarknesIdPanel(onAuthenticated: (() -> Unit)? = null) {
    val context = LocalContext.current
    var currentSession by remember { mutableStateOf(DarknesSessionStore.load(context)) }
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (currentSession != null) {
            Text("Darknes ID подключён", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(currentSession?.email.orEmpty(), color = Color(DarknesMuted), fontSize = 14.sp)
            Text("Сессия хранится в зашифрованном виде на этом устройстве.", color = Color(DarknesMuted), fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            DarknesDevicesPanel(session = currentSession!!)
            Button(
                onClick = {
                    val sessionToRevoke = currentSession
                    busy = true
                    scope.launch {
                        if (sessionToRevoke != null) {
                            withContext(Dispatchers.IO) { revokeDarknesSession(sessionToRevoke) }
                        }
                        DarknesSessionStore.clear(context)
                        currentSession = null
                        busy = false
                        message = "Вы вышли из аккаунта."
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(DarknesOrange), contentColor = Color(DarknesInk))
            ) { Text(if (busy) "Выход..." else "Выйти из аккаунта", fontWeight = FontWeight.Bold) }
            if (message.isNotBlank()) Text(message, color = Color(DarknesMuted), fontSize = 12.sp)
        } else {
            Text(
                if (isSignUp) "Создать Darknes ID" else "Войти в Darknes ID",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text("Единый аккаунт для приложений экосистемы Darknes", color = Color(DarknesMuted), fontSize = 13.sp)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Электронная почта") }
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Пароль") }
            )
            if (message.isNotBlank()) Text(message, color = Color(DarknesMuted), fontSize = 12.sp)
            Button(
                onClick = {
                    if (email.isBlank() || !email.contains("@") || password.length < 6) {
                        message = "Укажи корректную почту и пароль не короче 6 символов."
                    } else {
                        busy = true
                        message = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                requestDarknesAuth(context, email, password, isSignUp)
                            }
                            busy = false
                            message = result.message
                            result.session?.let {
                                currentSession = it
                                onAuthenticated?.invoke()
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(DarknesOrange), contentColor = Color(DarknesInk))
            ) {
                Text(when {
                    busy -> "Подключение..."
                    isSignUp -> "Создать аккаунт"
                    else -> "Войти"
                }, fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = { isSignUp = !isSignUp; message = "" },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (isSignUp) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться",
                    color = Color(DarknesOrange)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Пароль отправляется напрямую в Supabase Auth и не сохраняется приложением.",
                color = Color(DarknesMuted),
                fontSize = 10.sp
            )
        }
    }
}

private data class DarknesAuthResult(
    val session: DarknesSession? = null,
    val message: String
)

private fun requestDarknesAuth(
    context: android.content.Context,
    email: String,
    password: String,
    signUp: Boolean
): DarknesAuthResult {
    val endpoint = if (signUp) {
        "${BuildConfig.SUPABASE_URL}/auth/v1/signup"
    } else {
        "${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=password"
    }
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Accept", "application/json")
        }
        val payload = JSONObject().put("email", email).put("password", password).toString()
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(responseText) }.getOrNull()

        if (code in 200..299) {
            val accessToken = json?.optString("access_token").orEmpty()
            val refreshToken = json?.optString("refresh_token").orEmpty()
            if (accessToken.isNotBlank() && refreshToken.isNotBlank()) {
                val user = json?.optJSONObject("user")
                val session = DarknesSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    email = user?.optString("email").orEmpty().ifBlank { email },
                    userId = user?.optString("id").orEmpty(),
                    expiresAt = System.currentTimeMillis() / 1000L + (json?.optLong("expires_in", 3600L) ?: 3600L)
                )
                DarknesSessionStore.save(context, session)
                DarknesAuthResult(session, "Вход выполнен. Darknes ID подключён.")
            } else if (signUp) {
                DarknesAuthResult(message = "Аккаунт создан. Проверь почту и подтверди адрес, затем войди.")
            } else {
                DarknesAuthResult(message = "Сервер не выдал сессию. Попробуй ещё раз.")
            }
        } else {
            val detail = json?.optString("msg")?.takeIf { it.isNotBlank() }
                ?: json?.optString("message")?.takeIf { it.isNotBlank() }
                ?: "HTTP $code"
            DarknesAuthResult(message = when {
                detail.contains("Invalid login credentials", ignoreCase = true) -> "Неверная почта или пароль."
                detail.contains("already registered", ignoreCase = true) -> "Этот адрес уже зарегистрирован. Попробуй войти."
                detail.contains("Email not confirmed", ignoreCase = true) -> "Сначала подтверди почту по ссылке из письма."
                else -> "Не удалось выполнить запрос: $detail"
            })
        }
    } catch (_: Exception) {
        DarknesAuthResult(message = "Не удалось связаться с сервером. Проверь интернет и Project URL.")
    } finally {
        connection?.disconnect()
    }
}
