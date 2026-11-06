package com.darknes.tor

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

private data class DarknesDevice(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val createdAt: String,
    val lastSeenAt: String
)

private object DarknesDeviceApi {
    private const val PREFS = "darknes_device_identity"
    private const val DEVICE_KEY = "device_id"

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(DEVICE_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(DEVICE_KEY, created).apply()
        return created
    }

    private fun request(session: DarknesSession, method: String, path: String, body: String? = null, prefer: String? = null): String {
        val connection = (URL("${BuildConfig.SUPABASE_URL}/rest/v1/darknes_devices$path")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            if (prefer != null) setRequestProperty("Prefer", prefer)
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: " + response.take(220))
            return response
        } finally {
            connection.disconnect()
        }
    }

    suspend fun register(context: Context, session: DarknesSession) = withContext(Dispatchers.IO) {
        val id = deviceId(context)
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100).ifBlank { "Android device" }
        val payload = JSONObject()
            .put("user_id", session.userId)
            .put("device_id", id)
            .put("device_name", name)
            .put("platform", "Android ${Build.VERSION.RELEASE}")
            .put("last_seen_at", "now()")
        request(session, "POST", "?on_conflict=user_id%2Cdevice_id", payload.toString(), "resolution=merge-duplicates,return=minimal")
    }

    suspend fun list(session: DarknesSession): List<DarknesDevice> = withContext(Dispatchers.IO) {
        val userId = URLEncoder.encode(session.userId, "UTF-8")
        val raw = request(session, "GET", "?select=device_id%2Cdevice_name%2Cplatform%2Ccreated_at%2Clast_seen_at&user_id=eq.$userId&order=last_seen_at.desc")
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            DarknesDevice(
                item.optString("device_id"),
                item.optString("device_name", "Android"),
                item.optString("platform", "Android"),
                item.optString("created_at"),
                item.optString("last_seen_at")
            )
        }
    }

    suspend fun remove(session: DarknesSession, id: String) = withContext(Dispatchers.IO) {
        val userId = URLEncoder.encode(session.userId, "UTF-8")
        val deviceId = URLEncoder.encode(id, "UTF-8")
        request(session, "DELETE", "?user_id=eq.$userId&device_id=eq.$deviceId")
    }
}

@Composable
internal fun DarknesDevicesPanel(session: DarknesSession) {
    val context = LocalContext.current
    var devices by remember(session.userId) { mutableStateOf<List<DarknesDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            error = ""
            try {
                withContext(Dispatchers.IO) { DarknesDeviceApi.register(context, session) }
                devices = DarknesDeviceApi.list(session)
            } catch (e: Exception) {
                error = "Не удалось загрузить устройства. Проверь SQL-миграцию и подключение Supabase."
            }
            loading = false
        }
    }

    LaunchedEffect(session.userId) { reload() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Устройства Darknes ID", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("Список устройств, на которых выполнялся вход в этот аккаунт.", color = Color(0xFFB8AAA0), fontSize = 12.sp)
        if (loading) {
            Row { CircularProgressIndicator(color = Color(0xFFFF9A43), modifier = Modifier.height(22.dp)); Text("  Загружаем список…", color = Color(0xFFB8AAA0)) }
        } else if (error.isNotBlank()) {
            Text(error, color = Color(0xFFFFC27A), fontSize = 12.sp)
            OutlinedButton(onClick = { reload() }) { Text("Повторить") }
        } else if (devices.isEmpty()) {
            Text("Устройств пока нет.", color = Color(0xFFB8AAA0), fontSize = 12.sp)
        } else {
            devices.forEach { device ->
                val isCurrent = device.deviceId == context.getSharedPreferences("darknes_device_identity", Context.MODE_PRIVATE).getString("device_id", null)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                ) {
                    Text(device.deviceName, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(device.platform + if (isCurrent) " · Это устройство" else "", color = Color(0xFFFFC27A), fontSize = 11.sp)
                    Text("Последняя активность: " + device.lastSeenAt.replace("T", " ").take(19), color = Color(0xFFB8AAA0), fontSize = 10.sp)
                    if (!isCurrent) {
                        OutlinedButton(
                            onClick = {
                                removing = device.deviceId
                                scope.launch {
                                    try {
                                        DarknesDeviceApi.remove(session, device.deviceId)
                                        devices = devices.filterNot { it.deviceId == device.deviceId }
                                        notice = "Запись устройства удалена."
                                    } catch (_: Exception) {
                                        notice = "Не удалось удалить запись устройства."
                                    }
                                    removing = ""
                                }
                            },
                            enabled = removing != device.deviceId
                        ) { Text(if (removing == device.deviceId) "Удаляем…" else "Удалить из списка") }
                    }
                }
            }
        }
        if (notice.isNotBlank()) Text(notice, color = Color(0xFFFFC27A), fontSize = 11.sp)
        Text(
            "Важно: удаление записи убирает устройство только из этого списка и само по себе не завершает его авторизационную сессию. Удалённый отзыв доступа будет добавлен отдельным защищённым серверным механизмом.",
            color = Color(0xFFB8AAA0), fontSize = 10.sp, lineHeight = 15.sp
        )
    }
}
