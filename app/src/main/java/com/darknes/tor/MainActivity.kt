package com.darknes.tor

import android.os.Bundle
import android.view.WindowManager
import android.content.Intent
import android.os.SystemClock
import android.net.Uri
import android.widget.Toast
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONArray
import java.net.URLEncoder
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

// Compatibility aliases: existing screens keep their current appearance while
// new Remaster components share the centralized design-token source.
private val Ink = DarknesDesignTokens.ink
private val Paper = DarknesDesignTokens.paper
private val Orange = DarknesDesignTokens.cyan
private val Peach = DarknesDesignTokens.lavender
private val Violet = DarknesDesignTokens.violet
private val Muted = DarknesDesignTokens.muted
private val Glass = DarknesDesignTokens.glass
private val Border = DarknesDesignTokens.border
private fun uiLabel(language: String, russian: String, english: String): String = if (language == "en") english else russian

private data class PermissionAuditEntry(val host: String, val permission: String, val decision: String, val time: Long)
private fun readPermissionAudit(raw: String?): List<PermissionAuditEntry> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val host = item.optString("host")
        if (host.isBlank()) null else PermissionAuditEntry(host, item.optString("permission", "Доступ к сайту"), item.optString("decision", "Запрос"), item.optLong("time", 0L))
    }.sortedByDescending { it.time }.take(50)
}.getOrDefault(emptyList())
private fun encodePermissionAudit(entries: List<PermissionAuditEntry>): String = JSONArray().apply {
    entries.take(50).forEach { entry -> put(org.json.JSONObject().put("host", entry.host).put("permission", entry.permission).put("decision", entry.decision).put("time", entry.time)) }
}.toString()

private data class PendingSitePermission(val permission: GeckoSession.PermissionDelegate.ContentPermission, val result: GeckoResult<Int>, val label: String)
private data class PendingSiteMediaPermission(
    val uri: String,
    val video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
    val audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
    val callback: GeckoSession.PermissionDelegate.MediaCallback
)

private data class ThemePack(val id: String, val name: String, val accent: String, val incognitoAccent: String, val homeLayout: String, val blur: Float, val language: String)

private fun parseThemePacks(raw: String): List<ThemePack> = runCatching {
    val array = JSONArray(org.json.JSONObject(raw).optJSONArray("themes").toString())
    (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val accent = item.optString("accent", "cyan")
        val privateAccent = item.optString("incognitoAccent", "purple")
        val layout = item.optString("homeLayout", "grid")
        if (accent !in listOf("cyan", "blue", "purple", "green") || privateAccent !in listOf("cyan", "blue", "purple", "green") || layout !in listOf("grid", "list", "compact")) return@mapNotNull null
        ThemePack(item.optString("id"), item.optString("name"), accent, privateAccent, layout, item.optDouble("blur", 8.0).toFloat().coerceIn(0f, 32f), item.optString("language", "ru").takeIf { it == "en" } ?: "ru")
    }
}.getOrDefault(emptyList())

private data class DownloadRecord(val id: Long, val name: String, val status: String, val savedUri: String? = null, val downloadedBytes: Long = 0L, val totalBytes: Long = 0L)

private fun downloadCategory(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> "Изображения"
        "mp4", "mkv", "mov", "webm", "avi", "3gp" -> "Видео"
        "mp3", "wav", "ogg", "m4a", "flac", "aac" -> "Аудио"
        "pdf", "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "csv" -> "Документы"
        "apk", "zip", "rar", "7z", "tar", "gz" -> "Архивы и приложения"
        else -> "Другие"
    }
}

private fun formatDownloadSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "${bytes.coerceAtLeast(0L)} B"
}

private fun readDownloadRecords(raw: String?): List<DownloadRecord> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optLong("id", 0L)
        val name = item.optString("name")
        if (id <= 0L || name.isBlank()) return@mapNotNull null
        val storedStatus = item.optString("status", "Сохранено")
        val status = if (storedStatus == "Скачивается" || storedStatus == "Ожидает сохранения") "Прервано" else storedStatus
        DownloadRecord(id, name, status, item.optString("savedUri").takeIf { it.isNotBlank() && it != "null" }, item.optLong("downloadedBytes", 0L).coerceAtLeast(0L), item.optLong("totalBytes", 0L).coerceAtLeast(0L))
    }.sortedByDescending { it.id }.take(100)
}.getOrDefault(emptyList())

private fun encodeDownloadRecords(records: List<DownloadRecord>): String = JSONArray().apply {
    records.take(100).forEach { item ->
        put(org.json.JSONObject().put("id", item.id).put("name", item.name)
            .put("status", item.status).put("savedUri", item.savedUri ?: "")
            .put("downloadedBytes", item.downloadedBytes).put("totalBytes", item.totalBytes))
    }
}.toString()

private data class SavedPage(val title: String, val url: String, val time: Long = System.currentTimeMillis())
private data class OfflinePage(val title: String, val url: String, val content: String, val time: Long = System.currentTimeMillis())

private fun readOfflinePages(raw: String?): List<OfflinePage> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val url = item.optString("url")
        val content = item.optString("content")
        if (url.isBlank() || content.isBlank()) null
        else OfflinePage(item.optString("title").ifBlank { url }, url, content, item.optLong("time", 0L))
    }.take(30)
}.getOrDefault(emptyList())

private fun encodeOfflinePages(pages: List<OfflinePage>): String = JSONArray().apply {
    pages.take(30).forEach { page ->
        put(org.json.JSONObject().put("title", page.title).put("url", page.url)
            .put("content", page.content.take(20000)).put("time", page.time))
    }
}.toString()

private fun readSavedPages(raw: String?): List<SavedPage> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val url = item.optString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
        SavedPage(item.optString("title").ifBlank { url }, url, item.optLong("time", 0L))
    }
}.getOrDefault(emptyList())


private data class BrowserTabSnapshot(val title: String, val url: String, val pinned: Boolean = false)
private data class WorkspaceTabState(val tabs: List<BrowserTabSnapshot>, val groups: List<String>)

private fun readBrowserTabs(raw: String?): List<BrowserTabSnapshot> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val url = item.optString("url")
        if (url.isNotBlank() && !url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        BrowserTabSnapshot(item.optString("title").ifBlank { "Новая вкладка" }, url)
    }.take(30).ifEmpty { listOf(BrowserTabSnapshot("Новая вкладка", "")) }
}.getOrDefault(listOf(BrowserTabSnapshot("Новая вкладка", "")))

private fun readWorkspaceTabState(raw: String?): WorkspaceTabState = runCatching {
    val root = org.json.JSONObject(raw ?: "{}")
    val tabs = mutableListOf<BrowserTabSnapshot>()
    val groups = mutableListOf<String>()
    root.keys().forEach { group ->
        val array = root.optJSONArray(group) ?: return@forEach
        for (index in 0 until array.length()) {
            if (tabs.size >= 120) break
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (url.isNotBlank() && !url.startsWith("https://") && !url.startsWith("http://")) continue
            tabs.add(BrowserTabSnapshot(item.optString("title").ifBlank { "Новая вкладка" }, url, item.optBoolean("pinned", false)))
            groups.add(group.ifBlank { "Основная" })
        }
    }
    WorkspaceTabState(tabs, groups)
}.getOrDefault(WorkspaceTabState(emptyList(), emptyList()))

private fun encodeBrowserTabs(titles: List<String>, urls: List<String>): String = JSONArray().apply {
    titles.indices.take(30).forEach { index ->
        val url = urls.getOrNull(index).orEmpty()
        if (url.isBlank() || url.startsWith("https://") || url.startsWith("http://")) {
            put(org.json.JSONObject().put("title", titles.getOrNull(index).orEmpty().ifBlank { "Новая вкладка" }).put("url", url))
        }
    }
}.toString()

private fun encodeWorkspaceTabs(titles: List<String>, urls: List<String>, groups: List<String>, pinned: List<Boolean>): String {
    val root = org.json.JSONObject()
    groups.indices.forEach { index ->
        val group = groups[index].ifBlank { "Основная" }
        val url = urls.getOrNull(index).orEmpty()
        if (url.isBlank() || url.startsWith("https://") || url.startsWith("http://")) {
            val array = root.optJSONArray(group) ?: JSONArray().also { root.put(group, it) }
            if (array.length() < 30) array.put(org.json.JSONObject()
                .put("title", titles.getOrNull(index).orEmpty().ifBlank { "Новая вкладка" })
                .put("url", url)
                .put("pinned", pinned.getOrNull(index) ?: false))
        }
    }
    return root.toString()
}

private fun encodeSavedPages(pages: List<SavedPage>): String = JSONArray().apply {
    pages.forEach { page ->
        put(org.json.JSONObject().put("title", page.title).put("url", page.url).put("time", page.time))
    }
}.toString()

class MainActivity : ComponentActivity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var saveDownloadLauncher: ActivityResultLauncher<String>
    private var pendingDownload: WebResponse? = null
    private var pendingDownloadId: Long? = null
    private val downloadRecords = mutableStateListOf<DownloadRecord>()
    private data class ActiveDownload(val body: java.io.InputStream, val cancelled: java.util.concurrent.atomic.AtomicBoolean)
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<Long, ActiveDownload>()

    private fun persistDownloads() {
        getSharedPreferences("darknes_downloads", android.content.Context.MODE_PRIVATE)
            .edit().putString("records", encodeDownloadRecords(downloadRecords.toList())).apply()
    }

    private fun removeDownload(id: Long) {
        runOnUiThread {
            downloadRecords.removeAll { it.id == id }
            persistDownloads()
        }
    }

    private fun updateDownload(id: Long?, status: String, uri: String? = null, downloadedBytes: Long? = null, totalBytes: Long? = null) {
        if (id == null) return
        runOnUiThread {
            val index = downloadRecords.indexOfFirst { it.id == id }
            if (index >= 0) {
                val current = downloadRecords[index]
                downloadRecords[index] = current.copy(status = status, savedUri = uri ?: current.savedUri, downloadedBytes = downloadedBytes ?: current.downloadedBytes, totalBytes = totalBytes ?: current.totalBytes)
                persistDownloads()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadRecords.addAll(readDownloadRecords(
            getSharedPreferences("darknes_downloads", android.content.Context.MODE_PRIVATE).getString("records", null)
        ))
        saveDownloadLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
            val response = pendingDownload
            val recordId = pendingDownloadId
            pendingDownload = null
            pendingDownloadId = null
            if (destination == null || response == null) {
                response?.body?.close()
                updateDownload(recordId, "Отменено")
                Toast.makeText(this, "Скачивание отменено", Toast.LENGTH_SHORT).show()
            } else {
                val body = response.body
                if (body == null) {
                    updateDownload(recordId, "Ошибка")
                    return@registerForActivityResult
                }
                val task = ActiveDownload(body, java.util.concurrent.atomic.AtomicBoolean(false))
                if (recordId != null) activeDownloads[recordId] = task
                updateDownload(recordId, "Скачивается")
                Thread {
                    try {
                        val output = contentResolver.openOutputStream(destination)
                            ?: throw IllegalStateException("Не удалось открыть место сохранения")
                        val totalBytes = response.headers.entries.firstOrNull { it.key.equals("content-length", ignoreCase = true) }?.value?.toLongOrNull() ?: 0L
                        var copiedBytes = 0L
                        var lastReportedBytes = 0L
                        body.use { input -> output.use { target ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (task.cancelled.get()) throw java.io.IOException("Загрузка отменена")
                                val count = input.read(buffer)
                                if (task.cancelled.get()) throw java.io.IOException("Загрузка отменена")
                                if (count < 0) break
                                target.write(buffer, 0, count)
                                copiedBytes += count
                                if (copiedBytes - lastReportedBytes >= 256 * 1024 || (totalBytes > 0L && copiedBytes >= totalBytes)) {
                                    updateDownload(recordId, "Скачивается", downloadedBytes = copiedBytes, totalBytes = totalBytes)
                                    lastReportedBytes = copiedBytes
                                }
                            }
                            target.flush()
                        } }
                        if (task.cancelled.get()) {
                            updateDownload(recordId, "Отменено", downloadedBytes = copiedBytes, totalBytes = totalBytes)
                        } else {
                            updateDownload(recordId, "Сохранено", destination.toString(), copiedBytes, totalBytes)
                            runOnUiThread { Toast.makeText(this, "Файл сохранён", Toast.LENGTH_LONG).show() }
                        }
                    } catch (error: Exception) {
                        runCatching { body.close() }
                        if (task.cancelled.get()) {
                            updateDownload(recordId, "Отменено")
                        } else {
                            updateDownload(recordId, "Ошибка")
                            runOnUiThread { Toast.makeText(this, "Не удалось скачать файл: ${error.message}", Toast.LENGTH_LONG).show() }
                        }
                    } finally {
                        if (recordId != null) activeDownloads.remove(recordId, task)
                    }
                }.start()
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        runtime = GeckoRuntime.create(this)
        session = GeckoSession().also { it.open(runtime) }
        setContent { MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Glass, primary = Orange)) {
            DarknesApp(runtime, ::requestDownload, downloadRecords.toList(), ::removeDownload, ::cancelDownload)
        } }
    }

    private fun cancelDownload(id: Long) {
        val task = activeDownloads[id] ?: return
        if (task.cancelled.compareAndSet(false, true)) {
            runCatching { task.body.close() }
            updateDownload(id, "Отменено")
            Toast.makeText(this, "Загрузка отменена. Частичный файл может остаться в выбранном месте.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestDownload(response: WebResponse) {
        if (pendingDownload != null) {
            response.body?.close()
            Toast.makeText(this, "Сначала заверши предыдущее скачивание", Toast.LENGTH_SHORT).show()
            return
        }
        pendingDownload = response
        val rawName = Uri.parse(response.uri).lastPathSegment?.substringBefore("?").orEmpty()
        val filename = rawName.takeIf { it.isNotBlank() && it != "/" }
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "download"
        val recordId = System.currentTimeMillis()
        pendingDownloadId = recordId
        downloadRecords.add(0, DownloadRecord(recordId, filename, "Ожидает сохранения"))
        persistDownloads()
        saveDownloadLauncher.launch(filename)
    }

    override fun onDestroy() {
        pendingDownload?.body?.close()
        pendingDownload = null
        activeDownloads.values.forEach { task ->
            task.cancelled.set(true)
            runCatching { task.body.close() }
        }
        activeDownloads.clear()
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}

private data class SearchResult(val title: String, val url: String, val snippet: String, val displayUrl: String)
private data class PublishedVersion(val name: String, val tag: String, val notes: String, val apkUrl: String?)

private suspend fun fetchPublishedVersions(): List<PublishedVersion> = withContext(Dispatchers.IO) {
    val connection = java.net.URL("https://api.github.com/repos/def010375-ux/Darknes-brauser1.2/releases")
        .openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = 10000
    connection.readTimeout = 15000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", "Darknes-Tor-Android")
    try {
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val releases = JSONArray(response)
        (0 until releases.length()).mapNotNull { index ->
            val release = releases.optJSONObject(index) ?: return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    if (apkUrl != null) break
                }
            }
            PublishedVersion(
                name = release.optString("name").ifBlank { release.optString("tag_name") },
                tag = release.optString("tag_name"),
                notes = release.optString("body").take(500),
                apkUrl = apkUrl
            )
        }
    } finally {
        connection.disconnect()
    }
}

private suspend fun fetchSearchResults(query: String, engine: String): List<SearchResult> =
    fetchFallbackResults(query)


private suspend fun fetchReaderContent(pageUrl: String): String = withContext(Dispatchers.IO) {
    val connection = java.net.URL("https://r.jina.ai/$pageUrl").openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = 12000
    connection.readTimeout = 25000
    connection.setRequestProperty("User-Agent", "Darknes-Tor-Reader/1.4")
    connection.setRequestProperty("Accept", "text/plain")
    try { connection.inputStream.bufferedReader().use { it.readText().take(40000) } }
    finally { connection.disconnect() }
}

private suspend fun fetchFallbackResults(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
    val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
    val doc = Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
        .referrer("https://duckduckgo.com/").timeout(18000).get()
    val found = linkedMapOf<String, SearchResult>()
    doc.select(".result, .web-result").forEach { item ->
        val a = item.selectFirst("a.result__a, a.result-link")
        val title = a?.text().orEmpty()
        val href = a?.attr("href").orEmpty()
        val snippet = item.selectFirst(".result__snippet, .result-snippet")?.text().orEmpty()
        val decoded = runCatching {
            val parsed = java.net.URI(href)
            val queryPart = parsed.rawQuery.orEmpty()
            if (parsed.host.orEmpty().contains("duckduckgo.com") && queryPart.contains("uddg="))
                java.net.URLDecoder.decode(queryPart.substringAfter("uddg=").substringBefore("&"), "UTF-8")
            else href
        }.getOrDefault(href)
        val host = runCatching { java.net.URI(decoded).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
        if (title.length > 2 && host.isNotBlank() && decoded.startsWith("http"))
            found.putIfAbsent(decoded, SearchResult(title, decoded, snippet, host))
    }
    found.values.take(20).toList()
}

private fun resolveAddress(raw: String, engine: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return "about:blank"
    if (s.startsWith("https://", ignoreCase = true) || s.startsWith("http://", ignoreCase = true)) {
        return s.replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE)) { it.value.lowercase() }
    }
    if (s.startsWith("//")) return "https:$s"
    if (s.startsWith("www.", ignoreCase = true) || (s.contains(".") && !s.any { it.isWhitespace() })) return "https://$s"
    val q = java.net.URLEncoder.encode(s, "UTF-8")
    val base = when (engine) {
        "Google" -> "https://www.google.com/search?q="
        "Bing" -> "https://www.bing.com/search?q="
        "Brave" -> "https://search.brave.com/search?q="
        else -> "https://duckduckgo.com/?q="
    }
    return base + q
}


@Composable
private fun DarknesLaunchGate(onAuthenticated: () -> Unit) {
    val context = LocalContext.current
    val iconBurst = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(850)
        iconBurst.animateTo(1f, tween(780, easing = FastOutSlowInEasing))
        while (true) {
            iconBurst.animateTo(0.88f, tween(720, easing = FastOutSlowInEasing))
            iconBurst.animateTo(1f, tween(720, easing = FastOutSlowInEasing))
        }
    }
    var showLogin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(650)
        val savedSession = DarknesSessionStore.load(context)
        if (savedSession != null) {
            val nowSeconds = System.currentTimeMillis() / 1000L
            val validSession = if (savedSession.expiresAt > nowSeconds + 60L) {
                savedSession
            } else {
                refreshDarknesSession(context, savedSession)
            }
            if (validSession != null) {
                runCatching { DarknesDeviceApi.register(context, validSession) }
                onAuthenticated()
                return@LaunchedEffect
            }
        }
        showLogin = true
    }

    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0D1024), Ink, Color(0xFF050711)))
        ).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text("WELCOME TO", color = Muted, fontSize = 10.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "DARKNES",
            color = Paper,
            fontSize = 30.sp,
            letterSpacing = 5.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(top = 3.dp)
        )
        Text("ВХОД В ТВОЮ ВСЕЛЕННУЮ", color = Peach, fontSize = 9.sp, letterSpacing = 2.2.sp)
        Box(
            Modifier.fillMaxWidth().weight(0.9f).padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            BlackHoleScene(iconBurst = iconBurst.value)
        }
        AnimatedVisibility(
            visible = showLogin,
            enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 5 }
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 270.dp, max = 390.dp),
                color = Color(0xE6161210),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color(0x6646CFFF)),
                shadowElevation = 22.dp
            ) {
                Column(Modifier.padding(18.dp)) {
                    DarknesIdPanel(
                        onAuthenticated = onAuthenticated,
                        onContinueWithoutAccount = onAuthenticated
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("DARKNES ID  ·  ЕДИНЫЙ АККАУНТ", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(5.dp))
    }
}

@Composable
private fun BlackHoleScene(
    iconBurst: Float,
    modifier: Modifier = Modifier,
    accent: Color = Orange,
    showOrbitIcons: Boolean = true,
    showParticles: Boolean = true
) {
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, tween(1250, easing = FastOutSlowInEasing)) }
    val motion = rememberInfiniteTransition(label = "darknes-3d-black-hole")
    val rotation by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "accretion-rotation")
    val infall by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2300, easing = LinearEasing)), label = "particle-infall")
    val pulse by motion.animateFloat(.78f, 1.12f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "horizon-pulse")

    Box(modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            alpha = arrival.value
            val scale = .72f + arrival.value * .28f
            scaleX = scale
            scaleY = scale
            translationY = -54.dp.toPx() * (1f - arrival.value)
        }) {
            val cx = size.width * .5f
            val cy = size.height * .5f
            val unit = minOf(size.width, size.height)
            val rx = unit * .435f
            val ry = unit * .235f
            val horizon = unit * .112f

            // Deterministic deep-space star field: stable positions avoid visual flicker.
            for (i in 0 until 54) {
                val seedX = ((i * 73 + 19) % 101) / 101f
                val seedY = ((i * 47 + 31) % 103) / 103f
                val starX = size.width * seedX
                val starY = size.height * seedY
                val twinkle = .24f + .20f * kotlin.math.sin((rotation * .035f + i * 1.7f).toDouble()).toFloat().let { (it + 1f) * .5f }
                drawCircle(
                    color = Color(0xFFDCEBFF).copy(alpha = twinkle),
                    radius = unit * if (i % 11 == 0) .0042f else .0021f,
                    center = Offset(starX, starY)
                )
            }

            // Volumetric gravitational glow behind the accretion disk.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = .25f), Color(0xFF684BFF).copy(alpha = .12f), Color.Transparent),
                    center = Offset(cx, cy), radius = unit * .49f
                ),
                radius = unit * .49f, center = Offset(cx, cy)
            )
            for (layer in 0..3) {
                drawOval(
                    color = accent.copy(alpha = .09f - layer * .014f),
                    topLeft = Offset(cx - rx * (1.05f + layer * .055f), cy - ry * (1.18f + layer * .10f)),
                    size = androidx.compose.ui.geometry.Size(rx * (2.1f + layer * .11f), ry * (2.36f + layer * .20f)),
                    style = Stroke(width = unit * (.025f - layer * .004f))
                )
            }

            // A tilted, perspective-projected accretion disk. Rear edge is drawn first.
            rotate(-13f, pivot = Offset(cx, cy)) {
                rotate(rotation, pivot = Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = .25f), Color(0xFFFFD9A0), accent, Color.Transparent), center = Offset(cx, cy)),
                        startAngle = 180f, sweepAngle = 180f, useCenter = false,
                        topLeft = Offset(cx - rx, cy - ry), size = androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f),
                        style = Stroke(width = unit * .095f)
                    )
                    drawArc(
                        color = Color(0xFFFFE4B5).copy(alpha = .72f),
                        startAngle = 190f, sweepAngle = 160f, useCenter = false,
                        topLeft = Offset(cx - rx * .83f, cy - ry * .82f),
                        size = androidx.compose.ui.geometry.Size(rx * 1.66f, ry * 1.64f),
                        style = Stroke(width = unit * .018f)
                    )
                }

                // 3D particle field: orbital radius contracts toward the event horizon;
                // perspective depth changes each particle's apparent size and brightness.
                if (showParticles) {
                    // Scale particle density with the rendered scene size to protect slower devices.\n                    val particleCount = if (unit < 300f) 76 else if (unit < 430f) 104 else 132
                    for (i in 0 until particleCount) {
                        val seed = i / particleCount.toFloat()
                        val cycle = (seed + infall) % 1f
                        val radius = .20f + (1f - cycle) * .82f
                        val angle = i * 2.39996f + rotation * .0174533f * (1.1f + seed * 1.8f) + cycle * 5.6f
                        val depth = kotlin.math.sin(angle.toDouble()).toFloat()
                        val perspective = .58f + (depth + 1f) * .28f
                        val px = cx + kotlin.math.cos(angle.toDouble()).toFloat() * rx * radius
                        val py = cy + depth * ry * radius * .88f
                        val particleRadius = unit * (.0022f + perspective * .0045f) * (.65f + cycle * .45f)
                        val tint = if (i % 9 == 0) Color(0xFFFFE9C5) else if (i % 4 == 0) Color(0xFFB8A4FF) else accent
                        drawCircle(tint.copy(alpha = (.30f + perspective * .55f) * (1f - cycle * .18f)), particleRadius, Offset(px, py))
                        if (i % 3 == 0) {
                            val tailAngle = angle - .12f
                            val tx = cx + kotlin.math.cos(tailAngle.toDouble()).toFloat() * rx * radius * 1.035f
                            val ty = cy + kotlin.math.sin(tailAngle.toDouble()).toFloat() * ry * radius * .88f
                            drawLine(tint.copy(alpha = .24f * perspective), Offset(tx, ty), Offset(px, py), strokeWidth = particleRadius * .9f)
                        }
                    }
                }

                // Bright front lip of the disk gives the scene a strong 3D silhouette.
                rotate(rotation, pivot = Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, accent, Color(0xFFFFF1D2), accent.copy(alpha = .8f), Color.Transparent), center = Offset(cx, cy)),
                        startAngle = 0f, sweepAngle = 180f, useCenter = false,
                        topLeft = Offset(cx - rx, cy - ry), size = androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f),
                        style = Stroke(width = unit * .082f)
                    )
                    drawArc(
                        color = Color(0xFFFFEBCB).copy(alpha = .92f),
                        startAngle = 4f, sweepAngle = 172f, useCenter = false,
                        topLeft = Offset(cx - rx * .90f, cy - ry * .90f),
                        size = androidx.compose.ui.geometry.Size(rx * 1.8f, ry * 1.8f),
                        style = Stroke(width = unit * .012f)
                    )
                }
            }

            // Gravitational lensing: two soft concentric rings frame the event horizon.
            drawCircle(
                color = Color(0xFF9CCBFF).copy(alpha = .13f),
                radius = horizon * 1.42f,
                center = Offset(cx, cy),
                style = Stroke(width = unit * .012f)
            )
            drawCircle(
                color = accent.copy(alpha = .20f),
                radius = horizon * 1.68f,
                center = Offset(cx, cy),
                style = Stroke(width = unit * .006f)
            )

            // Event horizon: a dark spherical core with a thin gravitational-lensing rim.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF000000), Color(0xFF01030A), Color(0xFF151025).copy(alpha = .94f)),
                    center = Offset(cx - horizon * .22f, cy - horizon * .22f),
                    radius = horizon * 1.45f
                ),
                radius = horizon * pulse, center = Offset(cx, cy)
            )
            drawCircle(
                color = Color(0xFFFFE7C0).copy(alpha = .75f),
                radius = horizon * 1.08f * pulse, center = Offset(cx, cy),
                style = Stroke(width = unit * .008f)
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(accent.copy(alpha = .18f), Color.Transparent), center = Offset(cx, cy), radius = horizon * 1.9f),
                radius = horizon * 1.9f, center = Offset(cx, cy)
            )

            // A few fast inward streaks emphasize the suction motion.
            if (showParticles) for (i in 0 until 24) {
                val t = (i / 24f + infall) % 1f
                val angle = i * .83f + rotation * .012f + t * 6.4f
                val r = horizon * (2.0f + (1f - t) * 4.3f)
                val x = cx + kotlin.math.cos(angle.toDouble()).toFloat() * r * 1.7f
                val y = cy + kotlin.math.sin(angle.toDouble()).toFloat() * r * .72f
                val tailR = r * 1.24f
                val tx = cx + kotlin.math.cos((angle + .16f).toDouble()).toFloat() * tailR * 1.7f
                val ty = cy + kotlin.math.sin((angle + .16f).toDouble()).toFloat() * tailR * .72f
                drawLine(accent.copy(alpha = .58f * (1f - t * .45f)), Offset(tx, ty), Offset(x, y), strokeWidth = unit * .004f)
                drawCircle(Color(0xFFFFE9C5).copy(alpha = .85f - t * .4f), unit * .004f, Offset(x, y))
            }
        }

        val bubble = Modifier.size(39.dp).clip(CircleShape)
            .background(Color(0xE6161B2B)).border(1.dp, accent.copy(alpha = .55f), CircleShape)
        @Composable fun BurstIcon(icon: ImageVector, description: String, travelX: Dp, travelY: Dp) {
            Box(
                Modifier.align(Alignment.Center).offset(x = travelX * iconBurst, y = travelY * iconBurst)
                    .then(bubble).graphicsLayer {
                        val spread = .18f + iconBurst * .82f
                        scaleX = spread; scaleY = spread; alpha = iconBurst
                    }, contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = description, tint = Paper, modifier = Modifier.size(19.dp)) }
        }
        if (showOrbitIcons) {
            BurstIcon(Icons.Default.Search, "Поиск", (-112).dp, (-54).dp)
            BurstIcon(Icons.Default.Shield, "Защита", 100.dp, (-75).dp)
            BurstIcon(Icons.Default.AutoAwesome, "ИИ", (-72).dp, 78.dp)
            BurstIcon(Icons.Default.Tab, "Вкладки", 112.dp, 38.dp)
            BurstIcon(Icons.Default.Extension, "Расширения", 12.dp, 105.dp)
        }
    }
}

@Composable
private fun MaintenanceLockScreen(hour: Int, minute: Int) {
    BackHandler(enabled = true) { }
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF080D19), Color(0xFF111B31), Color(0xFF080D19)))
        ).padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier.size(86.dp).clip(CircleShape)
                    .background(Color(0xFF38D9E8).copy(alpha = .10f))
                    .border(1.dp, Color(0xFF38D9E8).copy(alpha = .38f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFF38D9E8), modifier = Modifier.size(39.dp))
            }
            Text("ТЕХНИЧЕСКИЕ РАБОТЫ", color = Color(0xFF38D9E8), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            Text("Darknes Tor временно недоступен", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(
                "Мы проводим плановое обслуживание. Все функции приложения временно отключены.",
                color = Color(0xFFB8C4D8), fontSize = 14.sp, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text("ОКНО ОБСЛУЖИВАНИЯ · МОСКВА", color = Color(0xFF8293AD), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("17:31 — 17:42 МСК", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            Text("Текущее время: %02d:%02d МСК".format(hour, minute), color = Color(0xFF38D9E8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Доступ восстановится автоматически в 17:42", color = Color(0xFF8293AD), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private fun DarknesApp(runtime: GeckoRuntime, onDownload: (WebResponse) -> Unit, downloads: List<DownloadRecord>, onRemoveDownload: (Long) -> Unit, onCancelDownload: (Long) -> Unit) {
    // Daily maintenance window uses Moscow time regardless of the device's selected timezone.
    var maintenanceClockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            maintenanceClockTick = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val moscowClock = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow")).apply {
        timeInMillis = maintenanceClockTick
    }
    val moscowMinutes = moscowClock.get(Calendar.HOUR_OF_DAY) * 60 + moscowClock.get(Calendar.MINUTE)
    val maintenanceActive = moscowMinutes >= (17 * 60 + 31) && moscowMinutes < (17 * 60 + 42)
    if (maintenanceActive) {
        MaintenanceLockScreen(
            hour = moscowClock.get(Calendar.HOUR_OF_DAY),
            minute = moscowClock.get(Calendar.MINUTE)
        )
        return
    }

    var launchAuthenticated by remember { mutableStateOf(false) }
    if (!launchAuthenticated) {
        DarknesLaunchGate(onAuthenticated = { launchAuthenticated = true })
        return
    }
    val context = LocalContext.current
    val tabsPrefs = remember(context) { context.getSharedPreferences("darknes_tabs", android.content.Context.MODE_PRIVATE) }
    val restoredWorkspaceState = remember(tabsPrefs) {
        readWorkspaceTabState(tabsPrefs.getString("workspace_sessions", null))
    }
    val restoredTabs = remember(tabsPrefs, restoredWorkspaceState) {
        restoredWorkspaceState.tabs.ifEmpty { readBrowserTabs(tabsPrefs.getString("tabs", null)) }
    }
    val restoredTabGroups = restoredWorkspaceState.groups
    var incognito by remember { mutableStateOf(false) }
    DisposableEffect(incognito, context) {
        val window = (context as? ComponentActivity)?.window
        if (incognito) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var privateModeUsed by remember { mutableStateOf(false) }
    val sessions = remember {
        mutableStateListOf<GeckoSession>().apply {
            restoredTabs.forEach { saved ->
                add(GeckoSession().also { tabSession ->
                    tabSession.open(runtime)
                    if (saved.url.isNotBlank()) tabSession.loadUri(saved.url)
                })
            }
        }
    }
    // Close every owned GeckoSession when the browser composition leaves the screen.
    DisposableEffect(sessions) {
        onDispose {
            sessions.toList().forEach { tabSession ->
                runCatching {
                    tabSession.setPermissionDelegate(null)
                    tabSession.setNavigationDelegate(null)
                    tabSession.setContentDelegate(null)
                    tabSession.close()
                }
            }
        }
    }
    val tabPreviews = remember { mutableStateMapOf<GeckoSession, Bitmap>() }
    var activeGeckoView by remember { mutableStateOf<GeckoView?>(null) }
    val tabTitles = remember { mutableStateListOf<String>().apply { addAll(restoredTabs.map { it.title }) } }
    val tabUrls = remember { mutableStateListOf<String>().apply { addAll(restoredTabs.map { it.url }) } }
    val tabGroups = remember(tabsPrefs) {
        val stored = runCatching {
            val array = JSONArray(tabsPrefs.getString("groups", "[]") ?: "[]")
            (0 until array.length()).map { array.optString(it, "Основная") }
        }.getOrDefault(emptyList())
        mutableStateListOf<String>().apply {
            addAll(restoredTabs.indices.map { index ->
                restoredTabGroups.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: stored.getOrNull(index)?.takeIf { group -> group.isNotBlank() }
                    ?: "Основная"
            })
        }
    }
    val tabPinned = remember(tabsPrefs) {
        val stored = runCatching {
            val array = JSONArray(tabsPrefs.getString("pinned_tabs", "[]") ?: "[]")
            (0 until array.length()).map { array.optBoolean(it, false) }
        }.getOrDefault(emptyList())
        mutableStateListOf<Boolean>().apply {
            addAll(restoredTabs.indices.map { index ->
                if (restoredWorkspaceState.tabs.isNotEmpty()) restoredWorkspaceState.tabs.getOrNull(index)?.pinned ?: false
                else stored.getOrNull(index) ?: false
            })
        }
    }
    val workspaceWallpapers = remember(tabsPrefs) { mutableStateMapOf<String, String>().apply {
        runCatching { val json = org.json.JSONObject(tabsPrefs.getString("workspace_wallpapers", "{}") ?: "{}"); json.keys().forEach { key -> put(key, json.optString(key)) } }
    } }
    val workspaceTracking = remember(tabsPrefs) { mutableStateMapOf<String, Boolean>().apply {
        runCatching { val json = org.json.JSONObject(tabsPrefs.getString("workspace_tracking", "{}") ?: "{}"); json.keys().forEach { key -> put(key, json.optBoolean(key, true)) } }
    } }
    val groupNames = remember(tabsPrefs) {
        val stored = runCatching { val array = JSONArray(tabsPrefs.getString("group_names", "[]") ?: "[]"); (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() } }.getOrDefault(emptyList())
        mutableStateListOf<String>().apply { addAll((stored + tabGroups.toList() + "Основная").distinct()) }
    }
    var activeTab by remember {
        val savedWorkspace = tabsPrefs.getString("active_workspace", null)
        val savedUrl = tabsPrefs.getString("active_workspace_url", null)
        val restoredIndex = if (restoredWorkspaceState.tabs.isNotEmpty() && savedWorkspace != null) {
            restoredTabs.indices.firstOrNull { index ->
                restoredTabGroups.getOrNull(index) == savedWorkspace && restoredTabs.getOrNull(index)?.url == savedUrl
            } ?: tabsPrefs.getInt("active", 0).coerceIn(0, restoredTabs.lastIndex)
        } else tabsPrefs.getInt("active", 0).coerceIn(0, restoredTabs.lastIndex)
        mutableIntStateOf(restoredIndex)
    }
    val session = sessions[activeTab.coerceIn(0, sessions.lastIndex)]
    val navigationBackBySession = remember { mutableStateMapOf<GeckoSession, Boolean>() }
    val navigationForwardBySession = remember { mutableStateMapOf<GeckoSession, Boolean>() }
    val canGoBack = navigationBackBySession[session] ?: false
    val canGoForward = navigationForwardBySession[session] ?: false
    fun captureActiveTabPreview() {
        if (incognito) return
        val view = activeGeckoView ?: return
        val capturedSession = session
        runCatching { view.capturePixels().accept({ bitmap ->
            if (!incognito && sessions.contains(capturedSession) && bitmap != null && bitmap.width > 0) {
                val thumbnailHeight = (bitmap.height * 480f / bitmap.width).toInt().coerceAtLeast(1)
                tabPreviews[capturedSession] = Bitmap.createScaledBitmap(bitmap, 480, thumbnailHeight, true)
                // Keep thumbnail memory bounded; older previews can be recaptured on demand.
                while (tabPreviews.size > 12) {
                    val oldest = tabPreviews.keys.firstOrNull { it !== capturedSession } ?: break
                    tabPreviews.remove(oldest)
                }
            }
        }, { }) }
    }
    val splitSession = remember(runtime) { GeckoSession().also { it.open(runtime) } }
    var splitScreen by remember { mutableStateOf(false) }
    DisposableEffect(splitSession) {
        onDispose { splitSession.close() }
    }
    var recentlyClosedTab by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var recentlyClosedIndex by remember { mutableIntStateOf(0) }
    fun addTab() {
        if (incognito) privateModeUsed = true
        sessions.add(GeckoSession().also { it.open(runtime) })
        tabTitles.add("Новая вкладка")
        tabUrls.add("")
        tabGroups.add("Основная")
        tabPinned.add(false)
        activeTab = sessions.lastIndex
    }
    fun moveTab(from: Int, to: Int) {
        if (from !in sessions.indices || to !in sessions.indices || from == to) return
        val movedSession = sessions.removeAt(from)
        val movedTitle = tabTitles.removeAt(from)
        val movedUrl = tabUrls.removeAt(from)
        val movedGroup = tabGroups.removeAt(from)
        val movedPinned = tabPinned.removeAt(from)
        sessions.add(to, movedSession)
        tabTitles.add(to, movedTitle)
        tabUrls.add(to, movedUrl)
        tabGroups.add(to, movedGroup)
        tabPinned.add(to, movedPinned)
        activeTab = when {
            activeTab == from -> to
            from < activeTab && to >= activeTab -> activeTab - 1
            from > activeTab && to <= activeTab -> activeTab + 1
            else -> activeTab
        }
    }
    fun closeTab(index: Int) {
        if (index !in sessions.indices) return
        if (!incognito) {
            recentlyClosedTab = Triple(
                tabTitles.getOrNull(index).orEmpty().ifBlank { "Новая вкладка" },
                tabUrls.getOrNull(index).orEmpty(),
                tabGroups.getOrNull(index).orEmpty().ifBlank { "Основная" }
            )
            recentlyClosedIndex = index
        }
        if (sessions.size > 1) {
            val closingSession = sessions[index]
            tabPreviews.remove(closingSession)
            navigationBackBySession.remove(closingSession)
            navigationForwardBySession.remove(closingSession)
            runCatching {
                closingSession.setPermissionDelegate(null)
                closingSession.setNavigationDelegate(null)
                closingSession.setContentDelegate(null)
                closingSession.close()
            }
            sessions.removeAt(index)
            tabTitles.removeAt(index)
            tabUrls.removeAt(index)
            tabGroups.removeAt(index)
            tabPinned.removeAt(index)
            activeTab = when {
                index < activeTab -> activeTab - 1
                index == activeTab -> (index - 1).coerceAtLeast(0)
                else -> activeTab
            }
        } else {
            tabPreviews.remove(sessions[0])
            sessions[0].purgeHistory()
            sessions[0].loadUri("about:blank")
            tabTitles[0] = "Новая вкладка"
            tabUrls[0] = ""
            tabPinned[0] = false
        }
    }
    val settingsPrefs = remember(context) { context.getSharedPreferences("darknes_settings", android.content.Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf(tabUrls.getOrNull(activeTab).orEmpty()) }
    var islandOpen by remember { mutableStateOf(false) }
    var showPageActions by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }
    val sitePermissionPrefs = remember(context) { context.getSharedPreferences("darknes_site_permissions", android.content.Context.MODE_PRIVATE) }
    val permissionAudit = remember(sitePermissionPrefs) { mutableStateListOf<PermissionAuditEntry>().apply { addAll(readPermissionAudit(sitePermissionPrefs.getString("audit", "[]"))) } }
    fun recordPermissionAudit(host: String, permission: String, decision: String) {
        if (incognito) return
        permissionAudit.add(0, PermissionAuditEntry(host.ifBlank { "Неизвестный сайт" }.take(180), permission.take(80), decision, System.currentTimeMillis()))
        while (permissionAudit.size > 50) permissionAudit.removeAt(permissionAudit.lastIndex)
        sitePermissionPrefs.edit().putString("audit", encodePermissionAudit(permissionAudit.toList())).apply()
    }
    val sitePermissionDecisions = remember(sitePermissionPrefs) {
        mutableStateMapOf<String, Boolean>().apply {
            runCatching {
                val json = org.json.JSONObject(sitePermissionPrefs.getString("decisions", "{}") ?: "{}")
                json.keys().forEach { key -> put(key, json.optBoolean(key)) }
            }
        }
    }
    fun persistSitePermissionDecisions() {
        val json = org.json.JSONObject()
        sitePermissionDecisions.forEach { (key, value) -> json.put(key, value) }
        sitePermissionPrefs.edit().putString("decisions", json.toString()).apply()
    }
    var pendingSitePermission by remember { mutableStateOf<PendingSitePermission?>(null) }
    var pendingSiteMediaPermission by remember { mutableStateOf<PendingSiteMediaPermission?>(null) }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val request = pendingSiteMediaPermission
        if (request != null) {
            val needsCamera = request.video?.any { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA } == true
            val needsMicrophone = request.audio?.any { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE } == true
            val cameraGranted = !needsCamera || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val microphoneGranted = !needsMicrophone || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (cameraGranted && microphoneGranted) {
                val videoSource = request.video?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA } ?: request.video?.firstOrNull()
                val audioSource = request.audio?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE } ?: request.audio?.firstOrNull()
                request.callback.grant(videoSource, audioSource)
                val host = runCatching { Uri.parse(request.uri).host.orEmpty() }.getOrDefault("")
                recordPermissionAudit(host, listOfNotNull(if (needsCamera) "Камера" else null, if (needsMicrophone) "Микрофон" else null).joinToString(" + "), "Разрешено")
            } else {
                request.callback.reject()
                val host = runCatching { Uri.parse(request.uri).host.orEmpty() }.getOrDefault("")
                recordPermissionAudit(host, "Камера / микрофон", "Отклонено системой Android")
                Toast.makeText(context, "Доступ к камере или микрофону не предоставлен Android", Toast.LENGTH_LONG).show()
            }
            pendingSiteMediaPermission = null
        }
    }
    var storedSitePermissions by remember { mutableStateOf<List<GeckoSession.PermissionDelegate.ContentPermission>>(emptyList()) }
    fun refreshSitePermissions() {
        if (incognito) {
            storedSitePermissions = emptyList()
            return
        }
        runtime.storageController.getAllPermissions().accept({ permissions ->
            storedSitePermissions = permissions.orEmpty().filter {
                !it.privateMode && it.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
            }.sortedBy { it.uri }
        }, {
            storedSitePermissions = emptyList()
        })
    }
    LaunchedEffect(panel, incognito) {
        if (panel == "Безопасность") refreshSitePermissions()
    }
    var engine by remember(settingsPrefs) { mutableStateOf(settingsPrefs.getString("searchEngine", "DuckDuckGo") ?: "DuckDuckGo") }
    val onLoad: (String) -> Unit = { raw ->
        runCatching { session.loadUri(resolveAddress(raw, engine)) }
            .onFailure { Toast.makeText(context, "Не удалось открыть страницу. Попробуй ещё раз.", Toast.LENGTH_SHORT).show() }
    }
    var blur by remember { mutableFloatStateOf(settingsPrefs.getFloat("blur", 8f).coerceIn(0f, 24f)) }
    var wallpaperUri by remember { mutableStateOf(settingsPrefs.getString("wallpaperUri", "") ?: "") }
    var incognitoAccentChoice by remember { mutableStateOf(settingsPrefs.getString("incognitoAccent", "purple") ?: "purple") }
    var homeLayout by remember { mutableStateOf(settingsPrefs.getString("homeLayout", "grid") ?: "grid") }
    var interfaceLanguage by remember { mutableStateOf(settingsPrefs.getString("language", "ru") ?: "ru") }
    var showHomeBranding by remember { mutableStateOf(settingsPrefs.getBoolean("showHomeBranding", true)) }
    var showHomeShortcuts by remember { mutableStateOf(settingsPrefs.getBoolean("showHomeShortcuts", true)) }
    var showHomeBookmarks by remember { mutableStateOf(settingsPrefs.getBoolean("showHomeBookmarks", true)) }
    var themePacks by remember { mutableStateOf<List<ThemePack>>(emptyList()) }
    var loadingThemePacks by remember { mutableStateOf(false) }
    val customHomeSites = remember(settingsPrefs) { mutableStateListOf<SavedPage>().apply { addAll(readSavedPages(settingsPrefs.getString("customHomeSites", null))) } }
    var animations by remember { mutableStateOf(settingsPrefs.getBoolean("animations", true)) }
    var trackers by remember { mutableStateOf(settingsPrefs.getBoolean("trackers", true)) }
    var darkSites by remember { mutableStateOf(settingsPrefs.getBoolean("darkSites", true)) }
    var adBlock by remember { mutableStateOf(settingsPrefs.getBoolean("adBlock", true)) }
    var nightPages by remember { mutableStateOf(settingsPrefs.getBoolean("nightPages", true)) }
    val activeWorkspace = tabGroups.getOrNull(activeTab).orEmpty().ifBlank { "Основная" }
    val effectiveTrackers = workspaceTracking[activeWorkspace] ?: trackers
    val effectiveWallpaper = workspaceWallpapers[activeWorkspace]?.takeIf { it.isNotBlank() } ?: wallpaperUri
    var model by remember { mutableStateOf("Darknes AI") }
    var chatInput by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(tabUrls.getOrNull(activeTab).orEmpty().isNotBlank()) }
    var showSearchResults by remember { mutableStateOf(false) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var activeSearchText by remember { mutableStateOf("") }
    var searchSource by remember { mutableStateOf(engine) }
    var tabCount by remember { mutableIntStateOf(1) }
    var toast by remember { mutableStateOf("") }
    fun restoreRecentlyClosedTab() {
        val saved = recentlyClosedTab ?: return
        val restoredSession = GeckoSession().also { it.open(runtime) }
        if (saved.second.isNotBlank()) restoredSession.loadUri(saved.second)
        val insertAt = recentlyClosedIndex.coerceIn(0, sessions.size)
        sessions.add(insertAt, restoredSession)
        tabTitles.add(insertAt, saved.first)
        tabUrls.add(insertAt, saved.second)
        tabGroups.add(insertAt, saved.third)
        activeTab = insertAt
        recentlyClosedTab = null
        canGoBack = false
        canGoForward = false
        query = saved.second
        loaded = saved.second.isNotBlank()
        showSearchResults = false
        toast = "Вкладка восстановлена"
    }
    fun exitPrivateSession() {
        sessions.getOrNull(activeTab)?.let { privateSession ->
            privateSession.purgeHistory()
            privateSession.loadUri("about:blank")
        }
        if (activeTab in tabUrls.indices) tabUrls[activeTab] = ""
        if (activeTab in tabTitles.indices) tabTitles[activeTab] = "Новая вкладка"
        query = ""
        loaded = false
        showSearchResults = false
        incognito = false
        privateModeUsed = false
        islandOpen = false
        panel = ""
        toast = "Приватная вкладка очищена"
    }
    var readerContent by remember { mutableStateOf("") }
    var readerSource by remember { mutableStateOf("") }
    var readerLoading by remember { mutableStateOf(false) }
    var readerError by remember { mutableStateOf("") }
    var accentChoice by remember { mutableStateOf(settingsPrefs.getString("accent", "cyan") ?: "cyan") }
    var testerTapCount by remember { mutableIntStateOf(0) }
    var testerLastTap by remember { mutableLongStateOf(0L) }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboardManager = remember(context) { context.getSystemService(android.content.ClipboardManager::class.java) }
    val savedPrefs = remember(context) { context.getSharedPreferences("darknes_browser_data", android.content.Context.MODE_PRIVATE) }
    val history = remember(savedPrefs) {
        mutableStateListOf<SavedPage>().apply { addAll(readSavedPages(savedPrefs.getString("history", null))) }
    }
    val bookmarks = remember(savedPrefs) {
        mutableStateListOf<SavedPage>().apply { addAll(readSavedPages(savedPrefs.getString("bookmarks", null))) }
    }
    var desktopMode by remember { mutableStateOf(settingsPrefs.getBoolean("desktopMode", false)) }
    var workspaceWallpaperTarget by remember { mutableStateOf<String?>(null) }
    val wallpaperPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val targetGroup = workspaceWallpaperTarget
            if (targetGroup != null) workspaceWallpapers[targetGroup] = uri.toString()
            else wallpaperUri = uri.toString()
        }
        workspaceWallpaperTarget = null
    }
    val configPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("empty")
            val config = org.json.JSONObject(raw)
            config.optString("accent", accentChoice).takeIf { it in listOf("cyan", "blue", "purple", "green") }?.let { accentChoice = it }
            config.optString("incognitoAccent", incognitoAccentChoice).takeIf { it in listOf("purple", "cyan", "blue", "green") }?.let { incognitoAccentChoice = it }
            config.optString("homeLayout", homeLayout).takeIf { it in listOf("grid", "list", "compact") }?.let { homeLayout = it }
            config.optString("language", interfaceLanguage).takeIf { it in listOf("ru", "en") }?.let { interfaceLanguage = it }
            if (config.has("blur")) blur = config.optDouble("blur", blur.toDouble()).toFloat().coerceIn(0f, 32f)
            if (config.has("wallpaperUri")) wallpaperUri = config.optString("wallpaperUri")
            if (config.has("showHomeBranding")) showHomeBranding = config.optBoolean("showHomeBranding")
            if (config.has("showHomeShortcuts")) showHomeShortcuts = config.optBoolean("showHomeShortcuts")
            if (config.has("showHomeBookmarks")) showHomeBookmarks = config.optBoolean("showHomeBookmarks")
            config.optJSONArray("customSites")?.let { array ->
                customHomeSites.clear()
                for (i in 0 until minOf(array.length(), 24)) {
                    val item = array.optJSONObject(i) ?: continue
                    val siteUrl = item.optString("url")
                    if (siteUrl.startsWith("https://") || siteUrl.startsWith("http://")) customHomeSites.add(SavedPage(item.optString("title").ifBlank { siteUrl }, siteUrl))
                }
            }
            toast = "Конфигурация применена"
        }.onFailure { toast = "Не удалось прочитать конфигурацию JSON" }
    }
    LaunchedEffect(customHomeSites.toList()) { settingsPrefs.edit().putString("customHomeSites", encodeSavedPages(customHomeSites.toList())).apply() }
    val exportConfigPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val config = org.json.JSONObject().put("format", "darknes-studio-v1")
                .put("accent", accentChoice).put("incognitoAccent", incognitoAccentChoice)
                .put("homeLayout", homeLayout).put("language", interfaceLanguage).put("blur", blur)
                .put("wallpaperUri", wallpaperUri).put("showHomeBranding", showHomeBranding)
                .put("showHomeShortcuts", showHomeShortcuts).put("showHomeBookmarks", showHomeBookmarks)
                .put("customSites", JSONArray().apply { customHomeSites.forEach { put(org.json.JSONObject().put("title", it.title).put("url", it.url)) } })
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(config.toString(2)) } ?: error("Cannot write file")
            toast = "Конфигурация экспортирована"
        }.onFailure { toast = "Ошибка экспорта конфигурации" }
    }
    val offlinePages = remember(savedPrefs) {
        mutableStateListOf<OfflinePage>().apply {
            addAll(readOfflinePages(savedPrefs.getString("offline_pages", null)))
        }
    }
    var selectedOfflinePage by remember { mutableStateOf<OfflinePage?>(null) }
    LaunchedEffect(history.toList()) {
        savedPrefs.edit().putString("history", encodeSavedPages(history.toList())).apply()
    }
    LaunchedEffect(bookmarks.toList()) {
        savedPrefs.edit().putString("bookmarks", encodeSavedPages(bookmarks.toList())).apply()
    }
    LaunchedEffect(offlinePages.toList()) {
        savedPrefs.edit().putString("offline_pages", encodeOfflinePages(offlinePages.toList())).apply()
    }
    LaunchedEffect(groupNames.toList()) {
        tabsPrefs.edit().putString("group_names", JSONArray(groupNames.toList()).toString()).apply()
    }
    LaunchedEffect(workspaceWallpapers.toMap()) {
        val json = org.json.JSONObject(); workspaceWallpapers.forEach { (key, value) -> json.put(key, value) }
        tabsPrefs.edit().putString("workspace_wallpapers", json.toString()).apply()
    }
    LaunchedEffect(workspaceTracking.toMap()) {
        val json = org.json.JSONObject(); workspaceTracking.forEach { (key, value) -> json.put(key, value) }
        tabsPrefs.edit().putString("workspace_tracking", json.toString()).apply()
    }
    LaunchedEffect(tabTitles.toList(), tabUrls.toList(), tabGroups.toList(), tabPinned.toList(), activeTab, incognito, privateModeUsed) {
        // Once private browsing has been used in this app session, do not overwrite
        // the last normal-tab snapshot with URLs that may have been opened privately.
        if (!incognito && !privateModeUsed) {
            tabsPrefs.edit()
                .putString("tabs", encodeBrowserTabs(tabTitles.toList(), tabUrls.toList()))
                .putString("workspace_sessions", encodeWorkspaceTabs(tabTitles.toList(), tabUrls.toList(), tabGroups.toList(), tabPinned.toList()))
                .putString("active_workspace", tabGroups.getOrNull(activeTab).orEmpty().ifBlank { "Основная" })
                .putString("active_workspace_url", tabUrls.getOrNull(activeTab).orEmpty())
                .putString("groups", JSONArray(tabGroups.toList()).toString())
                .putString("pinned_tabs", JSONArray(tabPinned.toList()).toString())
                .putInt("active", activeTab.coerceIn(0, (tabTitles.size - 1).coerceAtLeast(0)))
                .apply()
        }
    }
    LaunchedEffect(engine) {
        settingsPrefs.edit().putString("searchEngine", engine).apply()
    }
    LaunchedEffect(blur, animations, trackers, darkSites, adBlock, nightPages, accentChoice, desktopMode, wallpaperUri, incognitoAccentChoice, homeLayout, interfaceLanguage, showHomeBranding, showHomeShortcuts, showHomeBookmarks) {
        settingsPrefs.edit()
            .putBoolean("desktopMode", desktopMode)
            .putString("accent", accentChoice)
            .putString("wallpaperUri", wallpaperUri)
            .putString("incognitoAccent", incognitoAccentChoice)
            .putString("homeLayout", homeLayout)
            .putString("language", interfaceLanguage)
            .putBoolean("showHomeBranding", showHomeBranding)
            .putBoolean("showHomeShortcuts", showHomeShortcuts)
            .putBoolean("showHomeBookmarks", showHomeBookmarks)
            .putFloat("blur", blur)
            .putBoolean("animations", animations)
            .putBoolean("trackers", trackers)
            .putBoolean("darkSites", darkSites)
            .putBoolean("adBlock", adBlock)
            .putBoolean("nightPages", nightPages)
            .apply()
    }
    val accent = when (if (incognito) incognitoAccentChoice else accentChoice) {
        "blue" -> Color(0xFF55A8FF)
        "purple" -> Color(0xFFB28AFF)
        "green" -> Color(0xFF65D69A)
        "cyan" -> Orange
        else -> if (incognito) Violet else Orange
    }
    LaunchedEffect(workspaceTracking.toMap(), tabGroups.toList(), sessions.size, activeWorkspace) {
        sessions.forEachIndexed { index, browserSession ->
            val groupName = tabGroups.getOrNull(index).orEmpty().ifBlank { "Основная" }
            browserSession.settings.setUseTrackingProtection(workspaceTracking[groupName] ?: trackers)
        }
        splitSession.settings.setUseTrackingProtection(effectiveTrackers)
    }
    LaunchedEffect(desktopMode, sessions.size) {
        sessions.forEach { browserSession ->
            browserSession.settings.userAgentMode = if (desktopMode) {
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            }
            browserSession.settings.viewportMode = if (desktopMode) {
                GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            }
        }
        splitSession.settings.userAgentMode = if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        splitSession.settings.viewportMode = if (desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
    }
    LaunchedEffect(darkSites, nightPages) {
        runtime.settings.setPreferredColorScheme(
            if (darkSites && nightPages) GeckoRuntimeSettings.COLOR_SCHEME_DARK
            else GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        )
    }
    LaunchedEffect(adBlock) {
        runtime.settings.contentBlocking.setCookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
        runtime.settings.contentBlocking.setEnhancedTrackingProtectionLevel(
            if (adBlock) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT
        )
    }
    val scope = rememberCoroutineScope()
    fun runSearch(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (text.startsWith("!")) {
            val command = text.substringBefore(" ").lowercase()
            val commands = mapOf(
                "!tabs" to "Вкладки", "!settings" to "Настройки",
                "!menu" to "Меню", "!security" to "Безопасность",
                "!reader" to "Чтение", "!offline" to "Офлайн-страницы",
                "!ai" to "ИИ-чат"
            )
            val target = commands[command]
            if (target != null) {
                panel = target
                showSearchResults = false
                loaded = false
                toast = "Команда выполнена: $target"
                return
            }
            if (command == "!history" || command == "!bookmarks") {
                panel = "Меню"
                showSearchResults = false
                loaded = false
                toast = if (command == "!history") "Открой раздел «История» в меню" else "Открой раздел «Закладки» в меню"
                return
            }
            if (command == "!help") {
                toast = "Команды: !tabs, !history, !bookmarks, !settings, !security, !reader, !offline, !ai, !menu"
                return
            }
            toast = "Неизвестная команда. Введи !help"
            return
        }
        val isWebAddress = text.startsWith("https://", ignoreCase = true) ||
            text.startsWith("http://", ignoreCase = true) || text.startsWith("//") ||
            text.startsWith("www.", ignoreCase = true) ||
            (text.contains(".") && !text.any { it.isWhitespace() })
        if (isWebAddress) {
            showSearchResults = false
            loaded = true
            onLoad(text)
            return
        }
        if (engine != "DuckDuckGo") {
            showSearchResults = false
            loaded = true
            onLoad(text)
            return
        }
        activeSearchText = text
        searchSource = engine
        query = text
        showSearchResults = true
        loaded = false
        searchLoading = true
        searchError = ""
        searchResults = emptyList()
        scope.launch {
            try {
                var results = fetchSearchResults(text, engine)
                if (results.isEmpty()) {
                    results = fetchFallbackResults(text)
                    if (results.isNotEmpty()) searchSource = "Резервный поиск (DuckDuckGo)"
                }
                searchResults = results
                if (results.isEmpty()) searchError = "Не удалось получить результаты. Поисковик мог ограничить запрос. Попробуй ещё раз."
            } catch (error: Exception) {
                searchError = when (error) {
                    is java.net.UnknownHostException -> "Нет подключения к интернету."
                    is java.net.SocketTimeoutException -> "Поиск занял слишком много времени. Попробуй ещё раз."
                    else -> "Не удалось получить результаты. Возможно, поисковая система временно ограничила запросы."
                }
            } finally {
                searchLoading = false
            }
        }
    }
    fun saveCurrentPageOffline() {
        val currentUrl = tabUrls.getOrNull(activeTab).orEmpty()
        if (!currentUrl.startsWith("https://") && !currentUrl.startsWith("http://")) {
            toast = "Сначала открой веб-страницу"
            return
        }
        val title = tabTitles.getOrNull(activeTab).orEmpty().ifBlank { currentUrl }
        toast = "Сохраняем текст страницы офлайн…"
        scope.launch {
            try {
                val content = fetchReaderContent(currentUrl)
                if (content.isBlank()) throw IllegalStateException("Пустая страница")
                val existing = offlinePages.indexOfFirst { it.url == currentUrl }
                if (existing >= 0) offlinePages.removeAt(existing)
                offlinePages.add(0, OfflinePage(title, currentUrl, content))
                while (offlinePages.size > 100) offlinePages.removeAt(offlinePages.lastIndex)
                toast = "Страница сохранена для чтения офлайн"
            } catch (problem: Exception) {
                toast = "Не удалось сохранить страницу офлайн"
            }
        }
    }
    fun openReader() {
        val currentUrl = tabUrls.getOrNull(activeTab).orEmpty()
        if (!currentUrl.startsWith("https://") && !currentUrl.startsWith("http://")) { toast = "Сначала открой веб-страницу"; return }
        readerSource = currentUrl; readerContent = ""; readerError = ""; readerLoading = true; panel = "Чтение"
        scope.launch {
            try { readerContent = fetchReaderContent(currentUrl); if (readerContent.isBlank()) readerError = "Не удалось извлечь текст этой страницы." }
            catch (problem: Exception) { readerError = "Не удалось загрузить текст. Проверь подключение и попробуй ещё раз." }
            finally { readerLoading = false }
        }
    }
    val transition = updateTransition(targetState = panel, label = "panels")
    DisposableEffect(session, incognito) {
        session.setPermissionDelegate(object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                val origin = runCatching { Uri.parse(perm.uri).host ?: perm.uri }.getOrDefault(perm.uri)
                val kind = when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "Геолокация"
                    GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "Уведомления"
                    else -> "Доступ к сайту"
                }
                val key = "$origin|${perm.permission}"
                val saved = if (incognito || perm.privateMode) null else sitePermissionDecisions[key]
                if (saved != null) {
                    recordPermissionAudit(origin, kind, if (saved) "Разрешено ранее" else "Запрещено ранее")
                    return GeckoResult.fromValue(
                        if (saved) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }
                val result = GeckoResult<Int>()
                recordPermissionAudit(origin, kind, "Запрос")
                pendingSitePermission = PendingSitePermission(perm, result, "$kind — $origin")
                return result
            }

            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<String>,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                val granted = permissions.all { permission ->
                    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (granted) callback.grant() else callback.reject()
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                if (video.isNullOrEmpty() && audio.isNullOrEmpty()) {
                    callback.reject()
                    return
                }
                pendingSiteMediaPermission?.callback?.reject()
                val host = runCatching { Uri.parse(uri).host.orEmpty() }.getOrDefault("")
                recordPermissionAudit(host, listOfNotNull(if (!video.isNullOrEmpty()) "Камера" else null, if (!audio.isNullOrEmpty()) "Микрофон" else null).joinToString(" + "), "Запрос")
                pendingSiteMediaPermission = PendingSiteMediaPermission(uri, video, audio, callback)
            }
        })
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                onDownload(response)
            }
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val tabIndex = sessions.indexOf(session)
                if (tabIndex !in tabTitles.indices) return
                tabTitles[tabIndex] = title?.takeIf { it.isNotBlank() } ?: "Без названия"
            }
        })
        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                navigationBackBySession[session] = value
            }
            override fun onCanGoForward(session: GeckoSession, value: Boolean) {
                navigationForwardBySession[session] = value
            }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                val tabIndex = sessions.indexOf(session)
                if (tabIndex !in tabUrls.indices || url.isNullOrBlank()) return
                tabUrls[tabIndex] = url
                if (tabIndex == activeTab) {
                    loaded = url != "about:blank"
                    query = url
                }
                if (url == "about:blank") tabTitles[tabIndex] = "Новая вкладка"
                if (!incognito && (url.startsWith("https://") || url.startsWith("http://"))) {
                    val pageTitle = tabTitles.getOrNull(tabIndex)?.takeIf { it.isNotBlank() && it != "Новая вкладка" } ?: url
                    val existing = history.indexOfFirst { it.url == url }
                    if (existing >= 0) history.removeAt(existing)
                    history.add(0, SavedPage(pageTitle, url))
                    while (history.size > 500) history.removeAt(history.lastIndex)
                }
            }
        })
        onDispose {
            session.setPermissionDelegate(null)
            session.setNavigationDelegate(null)
            session.setContentDelegate(null)
        }
    }
    BackHandler(enabled = panel.isNotEmpty() || islandOpen || incognito) {
        when {
            panel.isNotEmpty() -> panel = ""
            islandOpen -> islandOpen = false
            incognito -> exitPrivateSession()
        }
    }
    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) { kotlinx.coroutines.delay(2100); toast = "" }
    }

    val activeAddress = tabUrls.getOrNull(activeTab).orEmpty()
    val addressSecurityLabel = when {
        activeAddress.startsWith("https://", ignoreCase = true) -> "HTTPS · шифрование включено"
        activeAddress.startsWith("http://", ignoreCase = true) -> "HTTP · соединение не защищено"
        activeAddress.isBlank() || activeAddress == "about:blank" -> "Поиск и адрес сайта"
        else -> "Внутренняя страница"
    }
    val addressSecurityIcon = when {
        activeAddress.startsWith("https://", ignoreCase = true) -> Icons.Default.Lock
        activeAddress.startsWith("http://", ignoreCase = true) -> Icons.Default.Warning
        else -> Icons.Default.Language
    }
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        // Dynamic Island-style address capsule
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
            Surface(
                color = Glass,
                shape = RoundedCornerShape(if (islandOpen) 28.dp else 30.dp),
                border = BorderStroke(1.dp, Border),
                shadowElevation = 18.dp,
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .fillMaxWidth(if (islandOpen) 0.96f else 0.92f)
                    .height(if (islandOpen) 62.dp else 50.dp)
                    .clickable { if (!islandOpen) islandOpen = true }
                    .animateContentSize(if (animations) spring(dampingRatio = 0.72f, stiffness = 380f) else snap())
            ) {
                if (!islandOpen) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                            Icon(
                                if (incognito) Icons.Default.VisibilityOff else addressSecurityIcon,
                                contentDescription = if (incognito) "Приватный режим" else addressSecurityLabel,
                                tint = if (!incognito && activeAddress.startsWith("http://", ignoreCase = true)) Peach else accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (incognito) "ПРИВАТНЫЙ РЕЖИМ" else if (loaded) runCatching { java.net.URI(query).host?.removePrefix("www.") ?: "Darknes Tor" }.getOrDefault("Darknes Tor") else "DARKNES TOR",
                                color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                modifier = Modifier.clickable {
                                    if (incognito) {
                                        val now = SystemClock.elapsedRealtime()
                                        testerTapCount = if (now - testerLastTap <= 1400L) testerTapCount + 1 else 1
                                        testerLastTap = now
                                        if (testerTapCount >= 3) {
                                            panel = "Тестер"
                                            islandOpen = false
                                            testerTapCount = 0
                                        }
                                    } else islandOpen = true
                                }
                            )
                            Text(
                                if (incognito) "История не сохраняется" else addressSecurityLabel,
                                color = if (!incognito && activeAddress.startsWith("http://", ignoreCase = true)) Peach else Muted,
                                fontSize = 9.sp, maxLines = 1
                            )
                        }
                        Icon(Icons.Default.ExpandMore, contentDescription = "Открыть адресную строку", tint = accent, modifier = Modifier.size(20.dp))
                        Box {
                            IconButton(onClick = { showPageActions = true }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Действия страницы", tint = accent)
                            }
                            DropdownMenu(
                                expanded = showPageActions,
                                onDismissRequest = { showPageActions = false },
                                modifier = Modifier.background(Color(0xFF17233A))
                            ) {
                                val pageUrl = tabUrls.getOrNull(activeTab).orEmpty()
                                val validPage = pageUrl.startsWith("https://", true) || pageUrl.startsWith("http://", true)
                                DropdownMenuItem(
                                    text = { Text("Копировать ссылку", color = Paper) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = accent) },
                                    enabled = validPage,
                                    onClick = {
                                        if (validPage) {
                                            clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("Darknes URL", pageUrl))
                                            toast = "Ссылка скопирована"
                                        }
                                        showPageActions = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Открыть в новой вкладке", color = Paper) },
                                    leadingIcon = { Icon(Icons.Default.Tab, null, tint = accent) },
                                    enabled = validPage,
                                    onClick = {
                                        if (validPage) {
                                            addTab()
                                            sessions[activeTab].loadUri(pageUrl)
                                            query = pageUrl
                                            loaded = true
                                        }
                                        showPageActions = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Добавить в закладки", color = Paper) },
                                    leadingIcon = { Icon(Icons.Default.BookmarkAdd, null, tint = accent) },
                                    enabled = validPage && !incognito,
                                    onClick = {
                                        if (validPage && !incognito) {
                                            if (bookmarks.none { it.url == pageUrl }) {
                                                bookmarks.add(0, SavedPage(tabTitles.getOrNull(activeTab).orEmpty().ifBlank { pageUrl }, pageUrl))
                                                toast = "Добавлено в закладки"
                                            } else toast = "Уже в закладках"
                                        }
                                        showPageActions = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Поделиться ссылкой", color = Paper) },
                                    leadingIcon = { Icon(Icons.Default.Share, null, tint = accent) },
                                    enabled = validPage,
                                    onClick = {
                                        if (validPage) {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, pageUrl)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Поделиться ссылкой"))
                                        }
                                        showPageActions = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Обновить страницу", color = Paper) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null, tint = accent) },
                                    enabled = validPage,
                                    onClick = {
                                        if (validPage) session.reload()
                                        showPageActions = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (incognito) Icons.Default.VisibilityOff else if (loaded) addressSecurityIcon else Icons.Default.Search,
                            contentDescription = if (incognito) "Инкогнито" else addressSecurityLabel,
                            tint = if (!incognito && activeAddress.startsWith("http://", ignoreCase = true)) Peach else accent,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                            singleLine = true, textStyle = TextStyle(color = Paper, fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                if (query.isNotBlank()) runSearch(query)
                                keyboard?.hide(); islandOpen = false
                            }),
                            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Поиск или адрес", color = Muted, fontSize = 14.sp); inner() } }
                        )
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить адрес", tint = Muted, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                val textToCopy = activeAddress.ifBlank { query }
                                if (textToCopy.isNotBlank()) {
                                    clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("Darknes URL", textToCopy))
                                    toast = "Адрес скопирован"
                                }
                            },
                            enabled = activeAddress.isNotBlank() || query.isNotBlank(),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Копировать адрес", tint = if (activeAddress.isNotBlank() || query.isNotBlank()) Muted else Muted.copy(alpha = .35f), modifier = Modifier.size(17.dp))
                        }
                        IconButton(
                            onClick = {
                                val pasted = runCatching { clipboardManager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() }.getOrDefault("")
                                if (pasted.isNotBlank()) query = pasted
                                else toast = "Буфер обмена пуст"
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Вставить адрес", tint = Muted, modifier = Modifier.size(17.dp))
                        }
                        Box(
                            Modifier.size(42.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(accent, Violet)))
                                .clickable {
                                    if (query.isNotBlank()) runSearch(query)
                                    keyboard?.hide(); islandOpen = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Перейти", tint = Ink, modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
        }

        val addressSuggestions = remember(query, islandOpen, engine) {
            val typed = query.trim()
            if (!islandOpen || typed.isBlank()) emptyList()
            else if (typed.startsWith("!")) {
                listOf("!tabs", "!history", "!bookmarks", "!settings", "!security", "!reader", "!offline", "!ai", "!menu", "!help")
                    .filter { it.startsWith(typed.substringBefore(" "), ignoreCase = true) }
                    .take(4)
                    .map { it to "Команда " + it }
            } else {
                val suggestions = mutableListOf<Pair<String, String>>()
                suggestions.add(typed to ("Искать «" + typed.take(42) + "» · " + engine))
                if (typed.startsWith("www.", ignoreCase = true) || (typed.contains(".") && !typed.any { it.isWhitespace() }) || typed.startsWith("http", ignoreCase = true)) {
                    suggestions.add(0, (if (typed.startsWith("//")) "https:$typed" else typed) to ("Открыть сайт · " + typed.take(42)))
                }
                suggestions.take(3)
            }
        }
        AnimatedVisibility(
            visible = addressSuggestions.isNotEmpty(),
            enter = fadeIn(tween(130)) + expandVertically(tween(170)),
            exit = fadeOut(tween(90)) + shrinkVertically(tween(120))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF018243B))
                    .border(1.dp, Border.copy(alpha = .8f), RoundedCornerShape(18.dp))
                    .padding(vertical = 5.dp)
            ) {
                addressSuggestions.forEachIndexed { index, suggestion ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            query = suggestion.first
                            runSearch(suggestion.first)
                            keyboard?.hide()
                            islandOpen = false
                        }.padding(horizontal = 15.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (suggestion.second.startsWith("Открыть сайт")) Icons.Default.Language else if (suggestion.first.startsWith("!")) Icons.Default.Terminal else Icons.Default.Search,
                            contentDescription = null, tint = accent, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(11.dp))
                        Text(suggestion.second, color = Paper, fontSize = 12.sp, maxLines = 1)
                    }
                    if (index != addressSuggestions.lastIndex) Divider(color = Border.copy(alpha = .45f), thickness = 0.5.dp)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (splitScreen) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    key(session) {
                        AndroidView(
                            factory = { ctx -> GeckoView(ctx).apply { setSession(session); activeGeckoView = this } },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    key(splitSession) {
                        AndroidView(
                            factory = { ctx -> GeckoView(ctx).apply { setSession(splitSession) } },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            } else {
                key(session) {
                    AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(session); activeGeckoView = this } }, modifier = Modifier.fillMaxSize())
                }
            }
            if (showSearchResults) {
                SearchResultsScreen(
                    query = activeSearchText, engine = searchSource, results = searchResults,
                    loading = searchLoading, error = searchError, accent = accent,
                    onRetry = { runSearch(activeSearchText) },
                    onBackToHome = { showSearchResults = false; loaded = false },
                    onOpenResult = { url ->
                        showSearchResults = false
                        loaded = true
                        query = url
                        session.loadUri(url)
                    }
                )
            } else if (!loaded && incognito) IncognitoHomeScreen(
                accent = accent,
                searchText = query,
                onSearchTextChange = { query = it },
                onSearch = { runSearch(query); keyboard?.hide() },
                onExit = { exitPrivateSession() }
            ) else if (!loaded) HomeScreen(
                accent = accent,
                blur = blur,
                animations = animations,
                searchText = query,
                onSearchTextChange = { query = it },
                engine = engine,
                onEngineChange = { engine = it },
                bookmarks = bookmarks.toList(),
                suggestionPages = (bookmarks.toList() + history.toList()).distinctBy { it.url }.take(30),
                wallpaperUri = effectiveWallpaper,
                homeLayout = homeLayout,
                showBranding = showHomeBranding,
                showShortcuts = showHomeShortcuts,
                showBookmarks = showHomeBookmarks,
                customSites = customHomeSites.toList(),
                language = interfaceLanguage,
                onSecretSettings = { panel = "Настройки" },
                onSearch = {
                    runSearch(query)
                    keyboard?.hide()
                },
                onCategorySearch = { searchText, category ->
                    val encoded = URLEncoder.encode(searchText, "UTF-8")
                    val target = when (category) {
                        "Картинки" -> "https://duckduckgo.com/?q=$encoded&iax=images&ia=images"
                        "Видео" -> "https://duckduckgo.com/?q=$encoded&iax=videos&ia=videos"
                        "Новости" -> "https://duckduckgo.com/?q=$encoded&ia=news"
                        "Карты" -> "https://www.google.com/maps/search/?api=1&query=$encoded"
                        else -> resolveAddress(searchText, engine)
                    }
                    query = target
                    showSearchResults = false
                    loaded = true
                    onLoad(target)
                },
                onShortcut = { link ->
                    query = link
                    onLoad(link)
                    loaded = true
                }
            )
            Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = panel.isNotEmpty(),
                enter = if (animations) fadeIn(tween(220)) + slideInVertically(animationSpec = spring(dampingRatio = .82f, stiffness = 340f)) { it / 3 } + scaleIn(initialScale = .97f) else EnterTransition.None,
                exit = if (animations) fadeOut(tween(150)) + slideOutVertically { it / 4 } else ExitTransition.None,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { panel = "" }, contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier.fillMaxWidth().fillMaxHeight(if (panel == "Вкладки" || panel == "Меню" || panel == "Безопасность" || panel == "Чтение" || panel == "Darknes Studio") .92f else .78f)
                            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                            .background(Glass).border(1.dp, Border, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                            .clickable(enabled = false) {}.padding(20.dp)
                    ) {
                        AnimatedContent(
                            targetState = panel,
                            transitionSpec = {
                                (fadeIn(tween(190)) + slideInHorizontally(tween(230, easing = FastOutSlowInEasing)) { it / 12 } + scaleIn(initialScale = .985f))
                                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(150)) { -it / 18 })
                            },
                            label = "darknes-panel-transition"
                        ) { currentPanel ->
                        when (currentPanel) {
                            "Меню" -> MenuPanel(accent) { action ->
                                panel = when(action) {
                                    "Новая вкладка" -> { addTab(); loaded = false; "" }
                                    "Инкогнито" -> { incognito = true; privateModeUsed = true; addTab(); loaded = false; "" }
                                    "Перевести страницу" -> {
                                        val currentUrl = tabUrls.getOrNull(activeTab).orEmpty()
                                        if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://")) {
                                            val translated = "https://translate.google.com/translate?sl=auto&tl=ru&u=" + URLEncoder.encode(currentUrl, "UTF-8")
                                            query = translated; session.loadUri(translated); loaded = true; showSearchResults = false
                                        } else toast = "Сначала открой веб-страницу"
                                        ""
                                    }
                                    "Режим чтения" -> { openReader(); "" }
                                    "Сохранить офлайн" -> { saveCurrentPageOffline(); "" }
                                    "Офлайн-страницы" -> "Офлайн-страницы"
                                    "Разделить экран" -> {
                                        splitScreen = !splitScreen
                                        if (splitScreen) {
                                            val currentUrl = tabUrls.getOrNull(activeTab).orEmpty()
                                            splitSession.loadUri(if (currentUrl.startsWith("https://") || currentUrl.startsWith("http://")) currentUrl else "about:blank")
                                        }
                                        ""
                                    }
                                    "Добавить закладку" -> {
                                        val currentUrl = tabUrls.getOrNull(activeTab).orEmpty()
                                        if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://")) {
                                            if (bookmarks.none { it.url == currentUrl }) {
                                                bookmarks.add(0, SavedPage(tabTitles.getOrNull(activeTab).orEmpty().ifBlank { currentUrl }, currentUrl))
                                                toast = "Добавлено в закладки"
                                            } else toast = "Уже в закладках"
                                        } else toast = "Сначала открой страницу"
                                        ""
                                    }
                                    else -> action
                                }
                            }
                            "Darknes Studio" -> DarknesStudioPanel(
                                accent = accent, accentChoice = accentChoice, onAccentChange = { accentChoice = it },
                                incognitoAccentChoice = incognitoAccentChoice, onIncognitoAccentChange = { incognitoAccentChoice = it },
                                blur = blur, onBlurChange = { blur = it }, homeLayout = homeLayout, onHomeLayoutChange = { homeLayout = it },
                                animations = animations, onAnimationsChange = { animations = it },
                                onChooseWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                                onResetWallpaper = { wallpaperUri = "" }, wallpaperSelected = wallpaperUri.isNotBlank()
                            )
                            "Настройки" -> SettingsPanel(engine, { engine = it }, trackers, { trackers = it }, darkSites, { darkSites = it }, animations, { animations = it }, blur, { blur = it }, accentChoice, { accentChoice = it }, desktopMode, { desktopMode = it }, incognitoAccentChoice, { incognitoAccentChoice = it }, homeLayout, { homeLayout = it }, interfaceLanguage, { interfaceLanguage = it }, wallpaperUri, { wallpaperPicker.launch(arrayOf("image/*")) }, { wallpaperUri = "" }, { configPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }, showHomeBranding, { showHomeBranding = it }, showHomeShortcuts, { showHomeShortcuts = it }, showHomeBookmarks, { showHomeBookmarks = it }, customHomeSites.toList(), { title, rawUrl -> val clean = rawUrl.trim(); val normalized = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "https://$clean"; if (title.isBlank() || Uri.parse(normalized).host.isNullOrBlank()) toast = "Проверь название и адрес сайта" else if (customHomeSites.any { it.url == normalized }) toast = "Этот сайт уже добавлен" else { customHomeSites.add(SavedPage(title.trim(), normalized)); toast = "Сайт добавлен на главную" } }, { index, direction -> val target = index + direction; if (target in customHomeSites.indices) { val item = customHomeSites.removeAt(index); customHomeSites.add(target, item) } }, { site -> customHomeSites.removeAll { it.url == site.url } }, themePacks, loadingThemePacks, { scope.launch { loadingThemePacks = true; runCatching { withContext(Dispatchers.IO) { URL("https://raw.githubusercontent.com/def010375-ux/Darknes-brauser1.2/main/themes/catalog.json").openConnection().apply { connectTimeout = 9000; readTimeout = 9000 }.getInputStream().bufferedReader().use { it.readText() } } }.onSuccess { themePacks = parseThemePacks(it); toast = "Загружено тем: ${themePacks.size}" }.onFailure { toast = "Каталог тем недоступен" }; loadingThemePacks = false } }, { pack -> accentChoice = pack.accent; incognitoAccentChoice = pack.incognitoAccent; homeLayout = pack.homeLayout; blur = pack.blur; interfaceLanguage = pack.language; toast = "Тема «${pack.name}» установлена" }, { exportConfigPicker.launch("Darknes-Studio-Config.json") })
                            "Безопасность" -> PrivacyPanel(trackers, { trackers = it }, adBlock, { adBlock = it }, darkSites, { darkSites = it }, nightPages, { nightPages = it }, groupNames.toList(), workspaceTracking.toMap(), { group, enabled -> workspaceTracking[group] = enabled }, storedSitePermissions, ::refreshSitePermissions, { permission ->
                                runtime.storageController.setPermission(permission, GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
                                val origin = runCatching { Uri.parse(permission.uri).host ?: permission.uri }.getOrDefault(permission.uri)
                                sitePermissionDecisions.remove("$origin|" + permission.permission)
                                persistSitePermissionDecisions()
                                recordPermissionAudit(origin, "Разрешение сайта", "Доступ отозван")
                                storedSitePermissions = storedSitePermissions.filterNot { it.uri == permission.uri && it.permission == permission.permission }
                            }, sitePermissionDecisions.toMap(), permissionAudit.toList(), { key ->
                                sitePermissionDecisions.remove(key)
                                persistSitePermissionDecisions()
                                recordPermissionAudit(key.substringBefore("|"), "Разрешение сайта", "Решение сброшено")
                            }, {
                                sitePermissionDecisions.clear()
                                persistSitePermissionDecisions()
                                recordPermissionAudit("Все сайты", "Разрешения", "Все сохранённые решения сброшены")
                            }, {
                                permissionAudit.clear()
                                sitePermissionPrefs.edit().putString("audit", "[]").apply()
                            }, {
                                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                                    .onFailure { toast = "Не удалось открыть настройки Android" }
                            }
                            "Чтение" -> ReaderPanel(readerSource, readerContent, readerLoading, readerError, onRetry = { openReader() })
                            "Офлайн-страницы" -> OfflinePagesPanel(
                                offlinePages = offlinePages.toList(),
                                onOpen = { page -> selectedOfflinePage = page; panel = "Офлайн-просмотр" },
                                onRemove = { page -> offlinePages.removeAll { it.url == page.url } }
                            )
                            "Офлайн-просмотр" -> OfflinePageViewerPanel(selectedOfflinePage, onBack = { panel = "Офлайн-страницы" })
                            "Тестер" -> SecretTesterPanel(
                                runtime = runtime,
                                sessionCount = sessions.size,
                                permissionCount = storedSitePermissions.size,
                                downloadCount = downloads.size,
                                privateMode = incognito,
                                onClose = { panel = "" }
                            )
                            "Расширения" -> ExtensionsPanel(adBlock, { adBlock = it }, nightPages, { nightPages = it }) { toast = "Установка расширений появится позже" }
                            "Аккаунт" -> DarknesIdPanel()
                            "ИИ-чат" -> ChatPanel {
                                panel = ""
                                query = "https://duck.ai"
                                session.loadUri("https://duck.ai")
                                loaded = true
                                showSearchResults = false
                            }
                            "Загрузки" -> DownloadsPanel(downloads, onRemoveDownload, onCancelDownload) { item ->
                                val saved = item.savedUri ?: return@DownloadsPanel
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(saved), "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }.onFailure { toast = "Не удалось открыть файл" }
                            }
                            "Закладки" -> SavedPagesPanel("Закладки", "Сайты, которые ты сохранил", bookmarks.toList(),
                                onOpen = { url -> session.loadUri(url); query = url; loaded = true; showSearchResults = false; panel = "" },
                                onRemove = { page -> bookmarks.remove(page) })
                            "История" -> SavedPagesPanel("История", "Последние посещённые страницы", history.toList(),
                                onOpen = { url -> session.loadUri(url); query = url; loaded = true; showSearchResults = false; panel = "" },
                                onRemove = { page -> history.remove(page) },
                                onClear = { history.clear() })
                            "Вкладки" -> TabsPanel(tabTitles.toList(), tabUrls.toList(), tabGroups.toList(), groupNames.toList(), tabPinned.toList(), workspaceWallpapers.toMap(), workspaceTracking.toMap(), { group -> workspaceWallpaperTarget = group; wallpaperPicker.launch(arrayOf("image/*")) }, activeTab, incognito, { value -> if (value) { privateModeUsed = true; tabPreviews.clear() }; incognito = value }, { addTab(); loaded = false }, { index -> closeTab(index); loaded = false }, { index -> activeTab = index; loaded = tabUrls.getOrNull(index).orEmpty().isNotBlank(); query = tabUrls.getOrNull(index).orEmpty(); panel = "" }, { group -> val target = tabGroups.indexOfFirst { it == group }; if (target >= 0) { activeTab = target; loaded = tabUrls.getOrNull(target).orEmpty().isNotBlank(); query = tabUrls.getOrNull(target).orEmpty() } }, { index -> tabGroups[index] = when (tabGroups[index]) { "Основная" -> "Работа"; "Работа" -> "Личное"; else -> "Основная" } }, { from, to -> moveTab(from, to) }, { index, group -> if (index in tabGroups.indices) tabGroups[index] = group }, { index -> if (!incognito && index in tabPinned.indices) tabPinned[index] = !tabPinned[index] }, { group, image, protection -> workspaceWallpapers[group] = image; workspaceTracking[group] = protection }, { name -> if (name.isNotBlank() && name !in groupNames) groupNames.add(name) }, { old, new -> if (old in groupNames && new.isNotBlank() && new !in groupNames) { groupNames[groupNames.indexOf(old)] = new; tabGroups.indices.forEach { if (tabGroups[it] == old) tabGroups[it] = new }; workspaceWallpapers[ new ] = workspaceWallpapers.remove(old).orEmpty(); workspaceTracking[new] = workspaceTracking.remove(old) ?: trackers } }, { name -> if (name != "Основная" && name in groupNames) { tabGroups.indices.forEach { if (tabGroups[it] == name) tabGroups[it] = "Основная" }; workspaceWallpapers.remove(name); workspaceTracking.remove(name); groupNames.remove(name) } }, sessions.map { tabPreviews[it] }, { captureActiveTabPreview() }, recentlyClosedTab != null && !incognito, { restoreRecentlyClosedTab() }, { panel = "" })
                        }
                        }
                    }
                }
            }
            val mediaPrompt = pendingSiteMediaPermission
            if (mediaPrompt != null && pendingSitePermission == null) {
                val host = runCatching { Uri.parse(mediaPrompt.uri).host ?: mediaPrompt.uri }.getOrDefault(mediaPrompt.uri)
                val asksCamera = mediaPrompt.video?.any { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA } == true
                val asksMicrophone = mediaPrompt.audio?.any { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE } == true
                val requestedLabels = listOfNotNull(
                    if (asksCamera) "камера" else null,
                    if (asksMicrophone) "микрофон" else null
                ).joinToString(" и ")
                AlertDialog(
                    onDismissRequest = {
                        mediaPrompt.callback.reject()
                        recordPermissionAudit(host, requestedLabels, "Отклонено пользователем")
                        pendingSiteMediaPermission = null
                    },
                    title = { Text("Доступ к устройствам", color = Paper) },
                    text = { Text("Сайт $host запрашивает доступ: $requestedLabels. После согласия Android отдельно запросит системное разрешение, если оно ещё не выдано.", color = Muted) },
                    confirmButton = {
                        TextButton(onClick = {
                            val missing = buildList {
                                if (asksCamera && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(android.Manifest.permission.CAMERA)
                                if (asksMicrophone && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(android.Manifest.permission.RECORD_AUDIO)
                            }
                            if (missing.isNotEmpty()) {
                                mediaPermissionLauncher.launch(missing.toTypedArray())
                            } else {
                                val videoSource = mediaPrompt.video?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA } ?: mediaPrompt.video?.firstOrNull()
                                val audioSource = mediaPrompt.audio?.firstOrNull { it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE } ?: mediaPrompt.audio?.firstOrNull()
                                mediaPrompt.callback.grant(videoSource, audioSource)
                                recordPermissionAudit(host, requestedLabels, "Разрешено")
                                pendingSiteMediaPermission = null
                            }
                        }) { Text("Разрешить", color = Orange) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            mediaPrompt.callback.reject()
                            pendingSiteMediaPermission = null
                        }) { Text("Запретить", color = Peach) }
                    },
                    containerColor = Color(0xFF17233A)
                )
            }
            val permissionPrompt = pendingSitePermission
            if (permissionPrompt != null) {
                LaunchedEffect(permissionPrompt) { permissionPrompt.permission.notifyShown() }
                AlertDialog(
                    onDismissRequest = {
                        permissionPrompt.result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                        recordPermissionAudit(runCatching { Uri.parse(permissionPrompt.permission.uri).host.orEmpty() }.getOrDefault(""), "Разрешение сайта", "Отклонено пользователем")
                        pendingSitePermission = null
                    },
                    title = { Text("Запрос разрешения", color = Paper) },
                    text = { Text("Сайт запрашивает: ${permissionPrompt.label}. Разрешить доступ?", color = Muted) },
                    confirmButton = {
                        TextButton(onClick = {
                            val perm = permissionPrompt.permission
                            val origin = runCatching { Uri.parse(perm.uri).host ?: perm.uri }.getOrDefault(perm.uri)
                            if (!incognito && !perm.privateMode) {
                                val key = "$origin|${perm.permission}"
                                sitePermissionDecisions[key] = true
                                persistSitePermissionDecisions()
                            }
                            permissionPrompt.result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                            recordPermissionAudit(origin, "Разрешение сайта", "Разрешено пользователем")
                            pendingSitePermission = null
                        }) { Text("Разрешить", color = Orange) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            val perm = permissionPrompt.permission
                            val origin = runCatching { Uri.parse(perm.uri).host ?: perm.uri }.getOrDefault(perm.uri)
                            if (!incognito && !perm.privateMode) {
                                val key = "$origin|${perm.permission}"
                                sitePermissionDecisions[key] = false
                                val json = org.json.JSONObject()
                                sitePermissionDecisions.forEach { (k, v) -> json.put(k, v) }
                                sitePermissionPrefs.edit().putString("decisions", json.toString()).apply()
                            }
                            permissionPrompt.result.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                            recordPermissionAudit(origin, "Разрешение сайта", "Запрещено пользователем")
                            pendingSitePermission = null
                        }) { Text("Запретить", color = Peach) }
                    },
                    containerColor = Color(0xFF17233A)
                )
            }
            AnimatedVisibility(toast.isNotEmpty(), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)) {
                Surface(color = Glass, shape = CircleShape, border = BorderStroke(1.dp, Border), shadowElevation = 14.dp) {
                    Text(toast, color = Paper, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), fontSize = 13.sp)
                }
            }
            }
        }

        // Remaster floating navigation dock: compact, thumb-friendly and always available.
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)
                .height(70.dp).animateContentSize(spring(dampingRatio = .78f, stiffness = 420f))
                .clip(RoundedCornerShape(DarknesDesignTokens.radiusLarge))
                .background(Brush.horizontalGradient(listOf(Color(0xF01A2944), Color(0xF00D1528), Color(0xF01B2040))))
                .border(1.dp, DarknesDesignTokens.border.copy(alpha = .9f), RoundedCornerShape(DarknesDesignTokens.radiusLarge))
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            NavCircle(Icons.Default.ArrowBack, accent, enabled = canGoBack, description = "Назад") { if (canGoBack) session.goBack() }
            NavCircle(Icons.Default.ArrowForward, accent, enabled = canGoForward, description = "Вперёд") { if (canGoForward) session.goForward() }
            NavCircle(Icons.Default.Refresh, accent, enabled = tabUrls.getOrNull(activeTab).orEmpty().isNotBlank(), description = "Обновить") {
                if (tabUrls.getOrNull(activeTab).orEmpty().isNotBlank()) session.reload()
            }
            NavCircle(Icons.Default.Home, accent, description = "Главная") {
                panel = ""
                showSearchResults = false
                loaded = false
                query = ""
                islandOpen = false
            }
            NavCircle(Icons.Default.AutoAwesome, accent, description = "Darknes AI") { panel = "ИИ-чат" }
            Box(
                Modifier.size(42.dp).animateContentSize(spring(dampingRatio = .7f, stiffness = 500f))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = .28f), Violet.copy(alpha = .20f))))
                    .border(1.dp, accent.copy(alpha = .5f), RoundedCornerShape(14.dp))
                    .clickable { panel = "Вкладки" },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = sessions.size,
                    transitionSpec = { (fadeIn(tween(140)) + slideInVertically(tween(180)) { it / 2 }).togetherWith(fadeOut(tween(100)) + slideOutVertically(tween(130)) { -it / 2 }) },
                    label = "tab-count"
                ) { count ->
                    Text("$count", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            NavCircle(if (incognito) Icons.Default.Close else Icons.Default.MoreHoriz, accent, description = if (incognito) "Завершить приватный режим" else "Меню") {
                if (incognito) exitPrivateSession() else panel = "Меню"
            }
        }
    }
}

@Composable
private fun SearchResultsScreen(
    query: String, engine: String, results: List<SearchResult>, loading: Boolean, error: String,
    accent: Color, onRetry: () -> Unit, onBackToHome: () -> Unit, onOpenResult: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackToHome) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Paper) }
            Column(Modifier.weight(1f)) {
                Text("Результаты поиска", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Darknes · $engine", color = accent, fontSize = 11.sp)
            }
            Text("\${results.size}", color = Muted, fontSize = 12.sp)
        }
        Surface(color = Color(0xFF10172B), shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = .35f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, tint = accent, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(query, color = Paper, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
                Text(engine, color = Peach, fontSize = 11.sp)
            }
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = accent, strokeWidth = 3.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Ищем в интернете…", color = Muted, fontSize = 13.sp)
                }
            }
            error.isNotEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SearchOff, contentDescription = null, tint = accent, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(error, color = Paper, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Повторить", color = Ink) }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)) {
                items(results.size) { index ->
                    val result = results[index]
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D1426))
                        .border(1.dp, Border, RoundedCornerShape(18.dp)).clickable { onOpenResult(result.url) }.padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(25.dp).clip(CircleShape).background(Color(0xFF17213B)), contentAlignment = Alignment.Center) {
                                Text(result.displayUrl.take(1).uppercase(), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(result.displayUrl, color = Muted, fontSize = 11.sp, maxLines = 1)
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(result.title, color = Peach, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        if (result.snippet.isNotBlank()) {
                            Spacer(Modifier.height(5.dp))
                            Text(result.snippet, color = Paper.copy(alpha = .82f), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4)
                        }
                        Spacer(Modifier.height(7.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Открыть страницу", color = accent, fontSize = 11.sp)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowOutward, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncognitoHomeScreen(
    accent: Color,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF100D25), Color(0xFF070A14), Color(0xFF080D1B)))
        ).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(76.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = .30f), Violet.copy(alpha = .12f), Color.Transparent)))
                .border(1.dp, accent.copy(alpha = .48f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("DARKNES · PRIVATE", color = Paper, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(7.dp))
        Text("Приватный сеанс", color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("История посещений не добавляется в историю браузера. Закрой приватную вкладку, когда закончишь.",
            color = Muted, fontSize = 12.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF17152E)).border(1.dp, accent.copy(alpha = .32f), RoundedCornerShape(18.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Shield, tint = Color(0xFF62E6C2), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Защита экрана активна", color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Снимки экрана и запись экрана заблокированы", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Box(Modifier.size(7.dp).background(Color(0xFF62E6C2), CircleShape))
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                .background(Color(0xFF111A30)).border(1.dp, Border, RoundedCornerShape(17.dp)).padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, tint = accent, contentDescription = null)
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                if (searchText.isEmpty()) Text("Поиск в приватном режиме", color = Muted, fontSize = 13.sp)
                BasicTextField(value = searchText, onValueChange = onSearchTextChange, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, textStyle = TextStyle(color = Paper, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }))
            }
            IconButton(onClick = onSearch, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.ArrowForward, tint = accent, contentDescription = "Искать")
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF10172A)).border(1.dp, Border, RoundedCornerShape(18.dp)).padding(15.dp)
        ) {
            Text("ПРАВИЛА ПРИВАТНОГО СЕАНСА", color = accent, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("• Страницы не записываются в локальную историю браузера", color = Paper, fontSize = 11.sp, lineHeight = 17.sp)
            Text("• Завершение сеанса очищает навигационную историю текущей вкладки", color = Paper, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
            Text("• Приватный режим не скрывает трафик от сайтов, сети или интернет-провайдера", color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 7.dp))
        }
        Spacer(Modifier.height(22.dp))
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Завершить приватный сеанс", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HomeScreen(
    accent: Color,
    blur: Float,
    animations: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    engine: String,
    onEngineChange: (String) -> Unit,
    bookmarks: List<SavedPage>,
    suggestionPages: List<SavedPage>,
    wallpaperUri: String,
    homeLayout: String,
    showBranding: Boolean,
    showShortcuts: Boolean,
    showBookmarks: Boolean,
    customSites: List<SavedPage>,
    language: String,
    onSecretSettings: () -> Unit,
    onSearch: () -> Unit,
    onCategorySearch: (String, String) -> Unit,
    onShortcut: (String) -> Unit
) {
    var titleTapCount by remember { mutableIntStateOf(0) }
    var lastTitleTapAt by remember { mutableLongStateOf(0L) }
    var orbitExpanded by remember { mutableStateOf(false) }
    val orbitProgress by animateFloatAsState(
        targetValue = if (orbitExpanded) 1f else 0f,
        animationSpec = spring(dampingRatio = .68f, stiffness = 260f),
        label = "home-black-hole-orbit"
    )
    val wallpaperContext = LocalContext.current
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showBranding) {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            val wallpaperBitmap = remember(wallpaperUri) {
                if (wallpaperUri.isBlank()) null else runCatching {
                    val uri = Uri.parse(wallpaperUri)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    wallpaperContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val sample = maxOf(1, maxOf(bounds.outWidth / 1200, bounds.outHeight / 900))
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    wallpaperContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
                }.getOrNull()
            }
            if (wallpaperBitmap != null) {
                Image(bitmap = wallpaperBitmap, contentDescription = "Обои стартовой страницы", contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).blur(blur.dp))
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .28f)))
            }
            BlackHoleScene(iconBurst = orbitProgress,
                modifier = Modifier.width(270.dp).height(205.dp).clickable { orbitExpanded = !orbitExpanded },
                accent = accent, showOrbitIcons = true, showParticles = true)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Darknes Tor",
            modifier = Modifier.clickable {
                val now = SystemClock.elapsedRealtime()
                titleTapCount = if (now - lastTitleTapAt <= 900L) titleTapCount + 1 else 1
                lastTitleTapAt = now
                if (titleTapCount >= 3) {
                    titleTapCount = 0
                    onSecretSettings()
                }
            },
            style = TextStyle(brush = Brush.linearGradient(listOf(Paper, Orange, Violet)), fontSize = 39.sp, fontWeight = FontWeight.Bold)
        )
        Text(uiLabel(language, "Приватность под твоим контролем", "Privacy on your terms"), color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(13.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50.dp))
                .background(Color(0xFF102B31).copy(alpha = .72f))
                .border(1.dp, Color(0xFF62E6C2).copy(alpha = .28f), RoundedCornerShape(50.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).background(Color(0xFF62E6C2), CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(uiLabel(language, "ЗАЩИТА ВКЛЮЧЕНА", "PROTECTION ENABLED"), color = Color(0xFF9AF2D9), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.width(10.dp))
            Text("·", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            Text(uiLabel(language, "БЕЗ АККАУНТА", "NO ACCOUNT"), color = Paper.copy(alpha = .78f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .7.sp)
        }
        Spacer(Modifier.height(20.dp))
        }
        HomeSearchBar(
            value = searchText,
            onValueChange = onSearchTextChange,
            engine = engine,
            onEngineChange = onEngineChange,
            accent = accent,
            suggestionPages = suggestionPages,
            onSearch = onSearch,
            onCategorySearch = onCategorySearch
        )
        if (showShortcuts) {
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(uiLabel(language, "ТВОЁ ПРОСТРАНСТВО", "YOUR SPACE"), color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Text(uiLabel(language, "Быстрый доступ к любимым сайтам", "Quick access to favorite sites"), color = Muted, fontSize = 10.sp)
            }
            Text("${7 + customSites.size} ${uiLabel(language, "САЙТОВ", "SITES")}", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        }
        val sites = listOf(
            Triple("YouTube", Icons.Default.PlayCircleFilled, "https://youtube.com"),
            Triple("DuckDuckGo", Icons.Default.Search, "https://duckduckgo.com"),
            Triple("GitHub", Icons.Default.Code, "https://github.com"),
            Triple("Wikipedia", Icons.Default.Language, "https://wikipedia.org"),
            Triple("Telegram", Icons.Default.Send, "https://telegram.org"),
            Triple("Reddit", Icons.Default.Forum, "https://reddit.com"),
            Triple("Twitch", Icons.Default.LiveTv, "https://twitch.tv")
        ) + customSites.map { Triple(it.title, Icons.Default.Public, it.url) }
        val siteColumns = when (homeLayout) { "compact" -> 5; "list" -> 1; else -> 4 }
        LazyVerticalGrid(columns = GridCells.Fixed(siteColumns), modifier = Modifier.fillMaxWidth().height(if (homeLayout == "list") 420.dp else 204.dp).padding(horizontal = 14.dp), userScrollEnabled = false) {
            items(sites) { (name, icon, url) ->
                Column(
                    Modifier.padding(5.dp).clip(RoundedCornerShape(19.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF1D3455), Color(0xFF10172B))))
                        .border(1.dp, accent.copy(alpha = .20f), RoundedCornerShape(19.dp))
                        .clickable { onShortcut(url) }
                        .animateContentSize(spring(dampingRatio = .72f, stiffness = 420f))
                        .padding(vertical = 13.dp, horizontal = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = .23f), Violet.copy(alpha = .13f))))
                        .border(1.dp, accent.copy(alpha = .22f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = name, tint = accent, modifier = Modifier.size(23.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(name, color = Paper, fontSize = 9.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                }
            }
        }
        }
        AnimatedVisibility(
            visible = showBookmarks && bookmarks.isNotEmpty(),
            enter = if (animations) fadeIn(tween(220)) + expandVertically(tween(260)) else EnterTransition.None,
            exit = if (animations) fadeOut(tween(140)) + shrinkVertically(tween(180)) else ExitTransition.None
        ) {
            Column {
            Text(uiLabel(language, "ТВОИ ЗАКЛАДКИ", "YOUR BOOKMARKS"), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.align(Alignment.Start).padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp).padding(horizontal = 12.dp), userScrollEnabled = false) {
                items(bookmarks.take(6)) { page ->
                    Column(Modifier.padding(5.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF1A2B49), Color(0xFF10172B)))).border(1.dp, Border, RoundedCornerShape(16.dp)).clickable { onShortcut(page.url) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Bookmark, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(5.dp)); Text(page.title, color = Paper, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun HomeSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    engine: String,
    onEngineChange: (String) -> Unit,
    accent: Color,
    suggestionPages: List<SavedPage>,
    onSearch: () -> Unit,
    onCategorySearch: (String, String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val searchContext = LocalContext.current
    val searchPrefs = remember(searchContext) { searchContext.getSharedPreferences("darknes_search", android.content.Context.MODE_PRIVATE) }
    var engineMenuExpanded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("Все") }
    var recentQueries by remember(searchPrefs) {
        mutableStateOf(runCatching {
            val saved = JSONArray(searchPrefs.getString("recent_queries", "[]") ?: "[]")
            (0 until saved.length()).map { saved.optString(it) }.filter { it.isNotBlank() }.take(6)
        }.getOrDefault(emptyList()))
    }
    val matchingSuggestions = recentQueries.filter { it.contains(value, ignoreCase = true) }.take(4)
    val matchingPages = if (value.isBlank()) emptyList() else suggestionPages
        .filter { it.title.contains(value, true) || it.url.contains(value, true) }
        .distinctBy { it.url }.take(4)
    fun submitSearch() {
        if (value.isBlank()) return
        recentQueries = (listOf(value.trim()) + recentQueries.filterNot { it.equals(value.trim(), true) }).take(6)
        searchPrefs.edit().putString("recent_queries", JSONArray(recentQueries).toString()).apply()
        if (selectedCategory == "Все") onSearch()
        else onCategorySearch(value.trim(), selectedCategory)
        keyboard?.hide()
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(DarknesDesignTokens.radiusLarge))
            .background(Brush.linearGradient(listOf(DarknesDesignTokens.elevatedSurface, DarknesDesignTokens.glass, DarknesDesignTokens.surface)))
            .border(1.dp, accent.copy(alpha = .48f), RoundedCornerShape(DarknesDesignTokens.radiusLarge))
            .padding(horizontal = 16.dp, vertical = 15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(DarknesDesignTokens.radiusSmall)).background(accent.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Search, contentDescription = "Поиск", tint = accent, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(11.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = Paper, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    submitSearch()
                }),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text("Поиск или адрес сайта", color = Muted, fontSize = 14.sp)
                        inner()
                    }
                }
            )
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить", tint = Muted)
                }
            }
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(DarknesDesignTokens.radiusMedium))
                    .background(Brush.linearGradient(listOf(accent, Violet)))
                    .clickable { submitSearch() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Искать", tint = Ink, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(Color(0xFF62E6C2), CircleShape))
            Spacer(Modifier.width(7.dp))
            Text("ПОИСКОВАЯ СИСТЕМА", color = Muted, fontSize = 9.sp, letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF25365A))
                        .clickable { engineMenuExpanded = true }.padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(engine, color = Paper, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = "Выбрать поисковик", tint = accent, modifier = Modifier.size(15.dp))
                }
                DropdownMenu(expanded = engineMenuExpanded, onDismissRequest = { engineMenuExpanded = false }) {
                    listOf("DuckDuckGo", "Google", "Bing", "Brave").forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider) },
                            leadingIcon = { Icon(if (provider == engine) Icons.Default.CheckCircle else Icons.Default.Search, null) },
                            onClick = { onEngineChange(provider); engineMenuExpanded = false }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        Text("КАТЕГОРИИ ПОИСКА", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Все", "Картинки", "Видео", "Карты", "Новости").forEach { category ->
                val selected = selectedCategory == category
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (selected) accent.copy(alpha = .22f) else Color(0xFF17233C))
                        .border(1.dp, if (selected) accent.copy(alpha = .75f) else Border, RoundedCornerShape(50))
                        .clickable { selectedCategory = category }
                        .padding(horizontal = 13.dp, vertical = 8.dp)
                ) {
                    Text(category, color = if (selected) accent else Paper.copy(alpha = .82f), fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            when (selectedCategory) {
                "Картинки" -> "Поиск изображений"
                "Видео" -> "Поиск видеороликов"
                "Карты" -> "Места и маршруты"
                "Новости" -> "Свежие публикации"
                else -> "Веб-страницы и сайты"
            },
            color = Muted, fontSize = 9.sp
        )
        AnimatedVisibility(visible = value.isNotBlank() && (matchingSuggestions.isNotEmpty() || matchingPages.isNotEmpty()),
            enter = fadeIn(tween(140)) + expandVertically(tween(180)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(120))) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("НЕДАВНИЕ ЗАПРОСЫ", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                matchingSuggestions.forEach { suggestion ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { onValueChange(suggestion) }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = Muted, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(suggestion, color = Paper, fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (matchingPages.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Text("ЗАКЛАДКИ И ИСТОРИЯ", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    matchingPages.forEach { page ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { onValueChange(page.url) }
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bookmark, null, tint = accent, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(page.title.ifBlank { page.url }, color = Paper, fontSize = 11.sp, maxLines = 1)
                                Text(page.url, color = Muted, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun NavCircle(
    icon: ImageVector,
    accent: Color,
    enabled: Boolean = true,
    description: String,
    action: () -> Unit
) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) accent.copy(alpha = .11f) else Color.Transparent)
            .border(1.dp, if (enabled) accent.copy(alpha = .22f) else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClickLabel = description) { action() }
            .animateContentSize(spring(dampingRatio = .72f, stiffness = 460f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) Paper else Muted.copy(alpha = .35f), modifier = Modifier.size(20.dp))
    }
}
@Composable private fun PanelTitle(title: String, subtitle: String = "") {
    Text(title, color = Paper, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    if (subtitle.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun DarknesStudioPanel(
    accent: Color, accentChoice: String, onAccentChange: (String) -> Unit,
    incognitoAccentChoice: String, onIncognitoAccentChange: (String) -> Unit,
    blur: Float, onBlurChange: (Float) -> Unit, homeLayout: String, onHomeLayoutChange: (String) -> Unit,
    animations: Boolean, onAnimationsChange: (Boolean) -> Unit,
    onChooseWallpaper: () -> Unit, onResetWallpaper: () -> Unit, wallpaperSelected: Boolean
) {
    val swatches = listOf("cyan" to Color(0xFF38D9E8), "blue" to Color(0xFF5798FF), "purple" to Color(0xFFAC83FF), "green" to Color(0xFF55D6A2))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DARKNES STUDIO", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("Твоя визуальная система", color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = accent, modifier = Modifier.size(25.dp))
        }
        Surface(Modifier.fillMaxWidth(), color = Color(0xFF111A2D), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, accent.copy(alpha = .38f))) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("ПРЕДПРОСМОТР", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(accent, Violet))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Darknes Tor", color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Поиск · вкладки · приватность", color = Muted, fontSize = 10.sp)
                    }
                    Icon(Icons.Default.Search, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
                }
                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(Color(0xFF25324A))) {
                    Box(Modifier.fillMaxWidth(.62f).fillMaxHeight().clip(CircleShape).background(Brush.horizontalGradient(listOf(accent, Violet))))
                }
                Text("Предпросмотр выбранного акцента", color = Muted, fontSize = 9.sp)
            }
        }
        Text("ОСНОВНОЙ АКЦЕНТ", color = Paper, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { (id, color) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onAccentChange(id) }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(color).then(if (accentChoice == id) Modifier.border(3.dp, Paper, CircleShape) else Modifier))
                    Text(if (accentChoice == id) "Выбран" else id, color = if (accentChoice == id) color else Muted, fontSize = 9.sp)
                }
            }
        }
        Text("АКЦЕНТ ИНКОГНИТО", color = Paper, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { (id, color) ->
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(11.dp))
                    .background(color.copy(alpha = if (incognitoAccentChoice == id) .9f else .25f))
                    .border(1.dp, if (incognitoAccentChoice == id) Paper else color.copy(alpha = .45f), RoundedCornerShape(11.dp))
                    .clickable { onIncognitoAccentChange(id) }, contentAlignment = Alignment.Center) {
                    if (incognitoAccentChoice == id) Icon(Icons.Default.Check, contentDescription = "Выбран", tint = Ink)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Размытие фона", color = Paper, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("${blur.toInt()} dp", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = blur, onValueChange = onBlurChange, valueRange = 0f..24f)
        Text("РАСКЛАДКА ГЛАВНОЙ", color = Paper, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("grid" to "Сетка", "list" to "Список", "compact" to "Компактно").forEach { (id, label) ->
                val selected = homeLayout == id
                Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (selected) accent.copy(alpha = .20f) else Color(0xFF1A263D))
                    .border(1.dp, if (selected) accent else Border, RoundedCornerShape(11.dp))
                    .clickable { onHomeLayoutChange(id) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) accent else Paper, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Анимации интерфейса", color = Paper, fontSize = 12.sp)
                Text("Переходы и появления", color = Muted, fontSize = 9.sp)
            }
            Switch(checked = animations, onCheckedChange = onAnimationsChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChooseWallpaper, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (wallpaperSelected) "Сменить фон" else "Выбрать фон", color = Ink, fontSize = 10.sp)
            }
            OutlinedButton(onClick = onResetWallpaper, enabled = wallpaperSelected, modifier = Modifier.weight(1f)) { Text("Сбросить фон", fontSize = 10.sp) }
        }
        Text("Изменения сохраняются автоматически на устройстве.", color = Muted, fontSize = 9.sp)
    }
}

@Composable private fun MenuPanel(accent: Color, onAction: (String) -> Unit) {
    PanelTitle("Меню")
    val items = listOf(
        Icons.Default.Add to "Новая вкладка",
        Icons.Default.Bookmark to "Добавить закладку",
        Icons.Default.Bookmarks to "Закладки",
        Icons.Default.History to "История",
        Icons.Default.Download to "Загрузки",
        Icons.Default.VisibilityOff to "Инкогнито",
        Icons.Default.Extension to "Расширения",
        Icons.Default.AutoAwesome to "ИИ-чат",
        Icons.Default.Settings to "Настройки",
        Icons.Default.Palette to "Darknes Studio",
        Icons.Default.Person to "Аккаунт",
        Icons.Default.Shield to "Безопасность",
        Icons.Default.Translate to "Перевести страницу",
        Icons.Default.MenuBook to "Режим чтения",
        Icons.Default.ViewColumn to "Разделить экран",
        Icons.Default.Download to "Сохранить офлайн",
        Icons.Default.Bookmarks to "Офлайн-страницы"
    )
    LazyVerticalGrid(columns = GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { (icon, name) ->
            Column(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(21.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1C2D50), Color(0xFF10182D))))
                    .border(1.dp, Border, RoundedCornerShape(21.dp))
                    .clickable { onAction(name) }.padding(10.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = .22f), Violet.copy(alpha = .13f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = name, tint = accent, modifier = Modifier.size(25.dp))
                }
                Spacer(Modifier.height(9.dp))
                Text(name, color = Paper, fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13.sp, maxLines = 2)
            }
        }
    }
}
@Composable
private fun SavedPagesPanel(
    title: String, subtitle: String, pages: List<SavedPage>, onOpen: (String) -> Unit,
    onRemove: (SavedPage) -> Unit, onClear: (() -> Unit)? = null
) {
    var filter by remember(title) { mutableStateOf("") }
    val accent = Orange
    PanelTitle(title, subtitle)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFF172641), Color(0xFF11182D))))
        .border(1.dp, accent.copy(alpha = .35f), RoundedCornerShape(17.dp))
        .padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, contentDescription = null, tint = accent)
        Spacer(Modifier.width(9.dp))
        BasicTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.weight(1f),
            singleLine = true, textStyle = TextStyle(color = Paper, fontSize = 14.sp),
            decorationBox = { inner -> Box { if (filter.isEmpty()) Text("Найти сайт или адрес", color = Muted, fontSize = 13.sp); inner() } })
        if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Очистить поиск", tint = Muted, modifier = Modifier.size(17.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("НАЙДЕНО: ${pages.count { it.title.contains(filter, true) || it.url.contains(filter, true) }}", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (onClear != null && pages.isNotEmpty()) TextButton(onClick = onClear) { Text("Очистить всё", color = accent, fontSize = 11.sp) }
    }
    val visiblePages = pages.filter { it.title.contains(filter, true) || it.url.contains(filter, true) }
    if (visiblePages.isEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF10182D))
            .border(1.dp, Border, RoundedCornerShape(20.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (title == "История") Icons.Default.History else Icons.Default.BookmarkBorder, contentDescription = null, tint = accent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(9.dp))
            Text(if (pages.isEmpty()) "Пока здесь пусто" else "Ничего не найдено", color = Paper, fontWeight = FontWeight.SemiBold)
            Text(if (title == "История") "Посещённые сайты появятся здесь." else "Добавляй страницы в закладки через меню.", color = Muted, fontSize = 12.sp)
        }
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(visiblePages, key = { it.url }) { page ->
                val host = runCatching { java.net.URI(page.url).host?.removePrefix("www.") ?: page.url }.getOrDefault(page.url)
                Surface(Modifier.fillMaxWidth(), color = Color.Transparent, shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Border.copy(alpha = .85f))) {
                    Row(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF182944), Color(0xFF10182B))))
                        .clickable { onOpen(page.url) }.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = .13f))
                            .border(1.dp, accent.copy(alpha = .25f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Public, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(page.title.ifBlank { host }, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(host, color = accent.copy(alpha = .9f), fontSize = 10.sp, maxLines = 1)
                            Text(java.text.SimpleDateFormat("dd MMM · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(page.time)),
                                color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        IconButton(onClick = { onRemove(page) }) { Icon(Icons.Default.Close, contentDescription = "Удалить", tint = Muted, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Данные хранятся на этом устройстве. Инкогнито не добавляет страницы в историю.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
}
@Composable private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val settingIcon = when {
        title.contains("трекер", ignoreCase = true) || title.contains("защит", ignoreCase = true) -> Icons.Default.Shield
        title.contains("тёмн", ignoreCase = true) || title.contains("ночн", ignoreCase = true) -> Icons.Default.DarkMode
        title.contains("анимац", ignoreCase = true) -> Icons.Default.AutoAwesome
        title.contains("компьютер", ignoreCase = true) -> Icons.Default.DesktopWindows
        else -> Icons.Default.Tune
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B2A48), Color(0xFF10172B))))
            .border(1.dp, Border, RoundedCornerShape(18.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Orange.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
            Icon(settingIcon, contentDescription = null, tint = Orange, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(11.dp))
        Text(title, color = Paper, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Orange, uncheckedThumbColor = Muted, uncheckedTrackColor = Color(0xFF293754)))
    }
}
@Composable
private fun DownloadsPanel(downloads: List<DownloadRecord>, onRemove: (Long) -> Unit, onCancel: (Long) -> Unit, onOpen: (DownloadRecord) -> Unit) {
    var filter by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Все") }
    val categories = listOf("Все", "Изображения", "Видео", "Аудио", "Документы", "Архивы и приложения", "Другие")
    PanelTitle("Загрузки", "Менеджер загруженных файлов")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFF172641), Color(0xFF11182D))))
        .border(1.dp, Orange.copy(alpha = .35f), RoundedCornerShape(17.dp)).padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Orange)
        Spacer(Modifier.width(9.dp))
        BasicTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.weight(1f), singleLine = true,
            textStyle = TextStyle(color = Paper, fontSize = 14.sp),
            decorationBox = { inner -> Box { if (filter.isEmpty()) Text("Найти загруженный файл", color = Muted, fontSize = 13.sp); inner() } })
        if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Очистить", tint = Muted, modifier = Modifier.size(17.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(categories) { item ->
            val selected = category == item
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) Orange.copy(alpha = .20f) else Color(0xFF17233A))
                .border(1.dp, if (selected) Orange.copy(alpha = .75f) else Border, RoundedCornerShape(50))
                .clickable { category = item }.padding(horizontal = 11.dp, vertical = 8.dp)) {
                Text(item, color = if (selected) Orange else Paper, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    val visible = downloads.filter {
        (category == "Все" || downloadCategory(it.name) == category) &&
            (it.name.contains(filter, true) || it.status.contains(filter, true))
    }.sortedByDescending { it.id }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${visible.size} ФАЙЛОВ", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text("${downloads.count { it.status == "Скачивается" }} активных", color = Orange, fontSize = 10.sp)
    }
    Spacer(Modifier.height(8.dp))
    if (visible.isEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF10182D))
            .border(1.dp, Border, RoundedCornerShape(20.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Download, contentDescription = null, tint = Orange, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(9.dp))
            Text(if (downloads.isEmpty()) "Пока ничего не скачано" else "Файлы не найдены", color = Paper, fontWeight = FontWeight.SemiBold)
            Text("Измени категорию или поисковый запрос.", color = Muted, fontSize = 12.sp)
        }
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(visible, key = { it.id }) { item ->
                val completed = item.savedUri != null
                val shape = RoundedCornerShape(18.dp)
                Surface(Modifier.fillMaxWidth(), color = Color.Transparent, shape = shape, border = BorderStroke(1.dp, Border)) {
                    Row(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF182944), Color(0xFF10182B))))
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Orange.copy(alpha = .14f))
                            .border(1.dp, Orange.copy(alpha = .28f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            val fileIcon = when (downloadCategory(item.name)) {
                                "Изображения" -> Icons.Default.Image
                                "Видео" -> Icons.Default.Movie
                                "Аудио" -> Icons.Default.MusicNote
                                "Документы" -> Icons.Default.Description
                                "Архивы и приложения" -> Icons.Default.FolderZip
                                else -> Icons.Default.InsertDriveFile
                            }
                            Icon(fileIcon, contentDescription = downloadCategory(item.name), tint = Orange, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                            Text("${downloadCategory(item.name)} · ${item.status}", color = if (completed) Color(0xFF62E6C2) else Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                            if (item.status == "Скачивается") {
                                if (item.totalBytes > 0L) {
                                    val fraction = (item.downloadedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = Orange, trackColor = Color(0xFF283650))
                                    Text("${(fraction * 100).toInt()}% · ${formatDownloadSize(item.downloadedBytes)} / ${formatDownloadSize(item.totalBytes)}", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = Orange, trackColor = Color(0xFF283650))
                                    Text("${formatDownloadSize(item.downloadedBytes)} загружено", color = Muted, fontSize = 9.sp)
                                }
                            } else if (item.downloadedBytes > 0L) {
                                Text(formatDownloadSize(item.downloadedBytes), color = Muted, fontSize = 9.sp)
                            }
                        }
                        if (completed) IconButton(onClick = { onOpen(item) }) { Icon(Icons.Default.OpenInNew, contentDescription = "Открыть файл", tint = Orange) }
                        if (item.status == "Скачивается") {
                            IconButton(onClick = { onCancel(item.id) }) {
                                Icon(Icons.Default.Close, contentDescription = "Отменить загрузку", tint = Color(0xFFFF8A80))
                            }
                        }
                        IconButton(onClick = { onRemove(item.id) }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить запись", tint = Muted, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Категории определяются по расширению файла. История хранится на устройстве; незавершённые загрузки после перезапуска отмечаются как прерванные. При отмене частичный файл может остаться в выбранной папке.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
private fun SecretTesterPanel(
    runtime: GeckoRuntime,
    sessionCount: Int,
    permissionCount: Int,
    downloadCount: Int,
    privateMode: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<String>() }
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "не определена" }
            .getOrDefault("не определена")
    }
    val totalMemoryMb = Runtime.getRuntime().totalMemory() / (1024L * 1024L)
    val freeMemoryMb = Runtime.getRuntime().freeMemory() / (1024L * 1024L)
    val runtimeName = runtime.javaClass.simpleName.ifBlank { "GeckoRuntime" }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DARKNES · INTERNAL", color = Color(0xFF38D9E8), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
                Text("Секретная панель тестировщика", color = Paper, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Внутренняя диагностика браузера", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Закрыть панель", tint = Paper) }
        }
        Spacer(Modifier.height(15.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF111D32),
            shape = RoundedCornerShape(17.dp),
            border = BorderStroke(1.dp, Color(0xFF38D9E8).copy(alpha = .32f))
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("СОСТОЯНИЕ СЕАНСА", color = Color(0xFF38D9E8), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(9.dp))
                val rows = listOf(
                    "Режим" to if (privateMode) "Инкогнито" else "Обычный",
                    "Движок" to runtimeName,
                    "Android" to "${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}",
                    "Устройство" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    "Версия приложения" to appVersion,
                    "Сессии браузера" to sessionCount.toString(),
                    "Разрешения сайтов" to permissionCount.toString(),
                    "Записи загрузок" to downloadCount.toString(),
                    "Память процесса" to "${totalMemoryMb} МБ выделено · ${freeMemoryMb} МБ свободно"
                )
                rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text(value, color = Paper, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                val checks = listOf(
                    "Контекст приложения" to runCatching { context.packageName.isNotBlank() }.getOrDefault(false),
                    "Экземпляр GeckoRuntime" to runtime.javaClass.name.contains("GeckoRuntime", ignoreCase = true),
                    "Есть активные сессии браузера" to (sessionCount > 0),
                    "Свободная память процесса не менее 16 МБ" to (freeMemoryMb >= 16L)
                )
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                logs.add(0, "Проверка $timestamp")
                checks.forEach { (name, passed) -> logs.add(1, "${if (passed) "OK" else "ПРОВЕРИТЬ"} · $name") }
                while (logs.size > 30) logs.removeAt(logs.lastIndex)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38D9E8), contentColor = Color(0xFF07111D)),
            shape = RoundedCornerShape(13.dp)
        ) {
            Icon(Icons.Default.MonitorHeart, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Запустить локальную диагностику", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ЖУРНАЛ ДИАГНОСТИКИ", color = Color(0xFF38D9E8), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            if (logs.isNotEmpty()) TextButton(onClick = { logs.clear() }) { Text("Очистить", color = Muted, fontSize = 10.sp) }
        }
        if (logs.isEmpty()) Text("Пока нет записей. Запусти локальную диагностику.", color = Muted, fontSize = 11.sp)
        else logs.forEach { line ->
            Text(line, color = if (line.contains("OK")) Color(0xFF62E6C2) else Paper.copy(alpha = .86f), fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("Панель не отправляет диагностические данные и не собирает историю или адреса посещённых страниц. Проверка локальная и не заменяет тестирование сети, разрешений и реального устройства.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun TesterPanel(onDownload: (String) -> Unit) {
    var versions by remember { mutableStateOf<List<PublishedVersion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            versions = fetchPublishedVersions()
        } catch (problem: Exception) {
            error = "Не удалось загрузить список релизов. Проверь подключение к интернету."
        } finally {
            loading = false
        }
    }

    PanelTitle("Darknes Tor Tester", "Публичный выбор версий браузера")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF151E37),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Violet.copy(alpha = .65f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1.5 Beta Release", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("СТАРТОВАЯ", color = Violet, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text("Группы вкладок, режим сайтов для компьютера и офлайн-чтение сохранённых страниц.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Text("Если эта версия уже установлена, повторная установка не требуется.", color = Peach, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("ОПУБЛИКОВАННЫЕ ВЕРСИИ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(8.dp))
    when {
        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Violet, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Проверяем доступные релизы…", color = Muted, fontSize = 13.sp)
        }
        error.isNotBlank() -> Text(error, color = Peach, fontSize = 13.sp)
        versions.isEmpty() -> Text("Пока опубликована только стартовая версия 1.5 Beta Release. Новые версии появятся здесь после публикации APK в GitHub Releases.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(versions) { version ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF141D33),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Text(version.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        if (version.notes.isNotBlank()) Text(version.notes, color = Muted, fontSize = 11.sp, maxLines = 3, modifier = Modifier.padding(top = 5.dp))
                        Spacer(Modifier.height(8.dp))
                        if (version.apkUrl != null) {
                            Text("Скачать APK", color = Violet, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onDownload(version.apkUrl) }.padding(vertical = 5.dp))
                        } else {
                            Text("APK для этой версии не приложен", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Выбор версии открывает загрузку APK. Установка подтверждается Android. Для обновления поверх текущей версии подпись APK должна совпадать.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable private fun SettingsPanel(engine: String, setEngine: (String)->Unit, trackers:Boolean, setTrackers:(Boolean)->Unit, dark:Boolean, setDark:(Boolean)->Unit, anim:Boolean, setAnim:(Boolean)->Unit, blur:Float, setBlur:(Float)->Unit, accentChoice:String, setAccentChoice:(String)->Unit, desktopMode:Boolean, setDesktopMode:(Boolean)->Unit, incognitoAccent:String, setIncognitoAccent:(String)->Unit, homeLayout:String, setHomeLayout:(String)->Unit, language:String, setLanguage:(String)->Unit, wallpaperUri:String, chooseWallpaper:()->Unit, clearWallpaper:()->Unit, importConfig:()->Unit, showBranding:Boolean, setShowBranding:(Boolean)->Unit, showShortcuts:Boolean, setShowShortcuts:(Boolean)->Unit, showBookmarks:Boolean, setShowBookmarks:(Boolean)->Unit, customSites:List<SavedPage>, addCustomSite:(String,String)->Unit, moveCustomSite:(Int,Int)->Unit, removeCustomSite:(SavedPage)->Unit, themePacks:List<ThemePack>, loadingThemePacks:Boolean, refreshThemePacks:()->Unit, applyThemePack:(ThemePack)->Unit, exportConfig:()->Unit) {
    var customSiteName by remember { mutableStateOf("") }
    var customSiteUrl by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    PanelTitle(uiLabel(language, "Настройки", "Settings"), uiLabel(language, "Персонализация и управление Darknes Tor", "Personalization and Darknes Tor controls"))
    val engines = listOf("DuckDuckGo", "Google", "Bing", "Brave")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF1A2B49), Color(0xFF10172B))))
        .border(1.dp, Orange.copy(alpha = .38f), RoundedCornerShape(20.dp)).padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Orange.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.TravelExplore, contentDescription = null, tint = Orange, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(uiLabel(language, "ПОИСКОВАЯ СИСТЕМА", "SEARCH ENGINE"), color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
                Text(engine, color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF62E6C2), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(13.dp))
        engines.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val selected = engine == option
                    Surface(Modifier.weight(1f).clickable { setEngine(option) }, color = if (selected) Orange.copy(alpha = .18f) else Color(0xFF10182B),
                        shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, if (selected) Orange else Border)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null, tint = if (selected) Orange else Muted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(option, color = if (selected) Paper else Muted, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(7.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(uiLabel(language, "ПРИВАТНОСТЬ И ОТОБРАЖЕНИЕ", "PRIVACY & DISPLAY"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    SettingSwitch(uiLabel(language, "Блокировать трекеры", "Block trackers"), trackers, setTrackers)
    SettingSwitch(uiLabel(language, "Тёмная схема сайтов", "Dark website theme"), dark, setDark)
    SettingSwitch(uiLabel(language, "Анимации интерфейса", "Interface animations"), anim, setAnim)
    SettingSwitch(uiLabel(language, "Версия сайтов для компьютера", "Desktop website mode"), desktopMode, setDesktopMode)
    Text("Режим компьютера меняет User-Agent и viewport GeckoView. Отдельные сайты могут отображаться иначе.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    Spacer(Modifier.height(14.dp))
    Spacer(Modifier.height(15.dp))
    Text(uiLabel(language, "ОФОРМЛЕНИЕ ОБЫЧНОГО РЕЖИМА", "NORMAL MODE APPEARANCE"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    Text(uiLabel(language, "Акцент обычного режима", "Normal mode accent"), color = Paper, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp, bottom = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("cyan" to "Неон", "blue" to "Синий", "purple" to "Лиловый", "green" to "Зелёный").forEach { (key, label) -> FilterChip(selected = accentChoice == key, onClick = { setAccentChoice(key) }, label = { Text(label, fontSize = 9.sp) }) }
    }
    Text(uiLabel(language, "Акцент инкогнито", "Incognito accent"), color = Paper, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("purple" to "Фиолетовый", "cyan" to "Неон", "blue" to "Синий", "green" to "Зелёный").forEach { (key, label) -> FilterChip(selected = incognitoAccent == key, onClick = { setIncognitoAccent(key) }, label = { Text(label, fontSize = 9.sp) }) }
    }
    Spacer(Modifier.height(14.dp))
    Text(uiLabel(language, "ФОНОВОЕ ИЗОБРАЖЕНИЕ", "WALLPAPER IMAGE"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    Text(if (wallpaperUri.isBlank()) uiLabel(language, "Своё фото ещё не выбрано", "No custom image selected") else uiLabel(language, "Пользовательское фото установлено", "Custom wallpaper is set"), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 7.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = chooseWallpaper, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(5.dp)); Text(uiLabel(language, "Выбрать фото", "Choose photo"), fontSize = 11.sp) }
        OutlinedButton(onClick = clearWallpaper, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(uiLabel(language, "Убрать фото", "Remove photo"), fontSize = 11.sp) }
    }
    Text(uiLabel(language, "Размытие фона: ${blur.toInt()} px", "Wallpaper blur: ${blur.toInt()} px"), color = Paper, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    Slider(value = blur, onValueChange = setBlur, valueRange = 0f..32f)
    Spacer(Modifier.height(12.dp))
    Text(uiLabel(language, "ЭЛЕМЕНТЫ СТАРТОВОЙ СТРАНИЦЫ", "HOME SCREEN ELEMENTS"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    SettingSwitch(uiLabel(language, "3D-сцена и логотип", "3D scene and logo"), showBranding, setShowBranding)
    SettingSwitch(uiLabel(language, "Быстрые сайты", "Quick sites"), showShortcuts, setShowShortcuts)
    SettingSwitch(uiLabel(language, "Показывать закладки", "Show bookmarks"), showBookmarks, setShowBookmarks)
    Text(uiLabel(language, "РАСКЛАДКА СТАРТОВОЙ СТРАНИЦЫ", "HOME SCREEN LAYOUT"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("grid" to "Сетка", "list" to "Список", "compact" to "Компактная").forEach { (key, label) -> FilterChip(selected = homeLayout == key, onClick = { setHomeLayout(key) }, label = { Text(label, fontSize = 10.sp) }) } }
    Spacer(Modifier.height(12.dp))
    Text(uiLabel(language, "ЯЗЫК ИНТЕРФЕЙСА", "INTERFACE LANGUAGE"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = language == "ru", onClick = { setLanguage("ru") }, label = { Text("Русский") }); FilterChip(selected = language == "en", onClick = { setLanguage("en") }, label = { Text("English") }) }
    Text(uiLabel(language, "Выбор языка сохраняется. Перевод остальных экранов будет расширяться.", "Your language choice is saved. Translation of the remaining screens will be expanded."), color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    Spacer(Modifier.height(14.dp))
    Text(uiLabel(language, "КАТАЛОГ ПОЛЬЗОВАТЕЛЬСКИХ GUI", "CUSTOM GUI CATALOG"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    Text(uiLabel(language, "Загрузи готовые пакеты оформления Darknes из онлайн-каталога. Пакет меняет только разрешённые параметры интерфейса.", "Load Darknes appearance packs from the online catalog. Packs only change approved interface settings."), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    OutlinedButton(onClick = refreshThemePacks, enabled = !loadingThemePacks, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(7.dp)); Text(if (loadingThemePacks) uiLabel(language, "Загрузка каталога…", "Loading catalog…") else uiLabel(language, "Загрузить каталог тем", "Load theme catalog")) }
    themePacks.forEach { pack ->
        Surface(Modifier.fillMaxWidth().padding(top = 6.dp).clickable { applyThemePack(pack) }, color = Color(0xFF121C32), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Border)) {
            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(when(pack.accent) { "blue" -> Color(0xFF55A8FF); "purple" -> Color(0xFFB28AFF); "green" -> Color(0xFF65D69A); else -> Orange }))
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(pack.name, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text("${pack.homeLayout} · ${pack.language.uppercase()}", color = Muted, fontSize = 9.sp) }
                Text("УСТАНОВИТЬ", color = Orange, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(uiLabel(language, "МОИ БЫСТРЫЕ САЙТЫ", "MY QUICK SITES"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(value = customSiteName, onValueChange = { customSiteName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(uiLabel(language, "Название сайта", "Site name")) })
    OutlinedTextField(value = customSiteUrl, onValueChange = { customSiteUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(uiLabel(language, "Адрес сайта", "Website address")) }, placeholder = { Text("example.com") })
    Button(onClick = { addCustomSite(customSiteName, customSiteUrl); if (customSiteName.isNotBlank() && customSiteUrl.isNotBlank()) { customSiteName = ""; customSiteUrl = "" } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(uiLabel(language, "Добавить на стартовую страницу", "Add to home screen")) }
    customSites.forEachIndexed { index, site ->
        Row(Modifier.fillMaxWidth().padding(top = 5.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF121C32)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(site.title, color = Paper, fontSize = 11.sp, maxLines = 1); Text(site.url, color = Muted, fontSize = 9.sp, maxLines = 1) }
            IconButton(onClick = { moveCustomSite(index, -1) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, null, tint = Orange) }
            IconButton(onClick = { moveCustomSite(index, 1) }, enabled = index < customSites.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Orange) }
            IconButton(onClick = { removeCustomSite(site) }) { Icon(Icons.Default.DeleteOutline, null, tint = Peach) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(uiLabel(language, "ТЕМЫ И КОНФИГУРАЦИИ", "THEMES & CONFIGURATIONS"), color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    Text("Импорт JSON-конфигурации Darknes Studio. Применяются только безопасные параметры оформления.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    OutlinedButton(onClick = importConfig, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(7.dp)); Text(uiLabel(language, "Импортировать конфигурацию", "Import configuration")) }
    OutlinedButton(onClick = exportConfig, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(7.dp)); Text(uiLabel(language, "Экспортировать мою тему", "Export my theme")) }
    Text("Поддерживаются accent, incognitoAccent, homeLayout, language, blur и wallpaperUri. Скрипты и исполняемый код не запускаются.", color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
    Text("Дополнительные параметры защиты находятся в Центре безопасности.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
    Spacer(Modifier.height(22.dp))
    }
}
@Composable private fun PrivacyPanel(trackers:Boolean, setTrackers:(Boolean)->Unit, ad:Boolean, setAd:(Boolean)->Unit, dark:Boolean, setDark:(Boolean)->Unit, night:Boolean, setNight:(Boolean)->Unit, workspaces:List<String>, workspaceProtection:Map<String, Boolean>, setWorkspaceProtection:(String, Boolean)->Unit, sitePermissions:List<GeckoSession.PermissionDelegate.ContentPermission>, refreshSitePermissions:()->Unit, revokeSitePermission:(GeckoSession.PermissionDelegate.ContentPermission)->Unit, savedDecisions:Map<String, Boolean>, audit:List<PermissionAuditEntry>, clearDecision:(String)->Unit, clearAllDecisions:()->Unit, clearAudit:()->Unit, openAndroidSettings:()->Unit) {
    PanelTitle("Центр безопасности", "Защита браузера и контроль доступа")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF1A2D4B), Color(0xFF10172B))))
        .border(1.dp, Orange.copy(alpha = .42f), RoundedCornerShape(21.dp)).padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF62E6C2).copy(alpha = .14f))
                .border(1.dp, Color(0xFF62E6C2).copy(alpha = .35f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF62E6C2), modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("ЗАЩИТА БРАУЗЕРА", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                Text("Контроль активен", color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text("LOCAL", color = Color(0xFF62E6C2), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text("Настройки ниже управляют режимами защиты интерфейса и GeckoView. Они не заменяют системные разрешения Android.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
    Spacer(Modifier.height(14.dp))
    Text("ЗАЩИТА ОТ ОТСЛЕЖИВАНИЯ", color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    SettingSwitch("Блокировать известные трекеры", trackers, setTrackers)
    SettingSwitch("Усиленная защита от отслеживания", ad, setAd)
    Spacer(Modifier.height(14.dp))
    Text("ЗАЩИТА РАБОЧИХ ПРОСТРАНСТВ", color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    Text("Для каждого пространства можно отдельно включить или отключить защиту от отслеживания. Если настройка не задана, используется общий переключатель выше.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    Spacer(Modifier.height(7.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF111A2E))
        .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp)) {
        workspaces.distinct().forEachIndexed { index, name ->
            val override = workspaceProtection[name]
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (override == null) "Общая настройка: " + if (trackers) "защита включена" else "защита выключена" else if (override) "Индивидуальная защита включена" else "Индивидуальная защита выключена", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = override ?: trackers, onCheckedChange = { setWorkspaceProtection(name, it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Orange))
            }
            if (index != workspaces.distinct().lastIndex) Divider(color = Border.copy(alpha = .55f))
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("РАЗРЕШЕНИЯ САЙТОВ", color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Text("Доступы, сохранённые движком браузера", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = refreshSitePermissions) {
            Icon(Icons.Default.Refresh, contentDescription = "Обновить разрешения", tint = Orange)
        }
    }
    if (sitePermissions.isEmpty()) {
        Text("Сохранённых разрешений пока нет. Когда сайт запросит доступ и ты разрешишь его, запись появится здесь.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF111A2E)).padding(13.dp))
    } else {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color(0xFF111A2E))
            .border(1.dp, Border, RoundedCornerShape(17.dp)).padding(horizontal = 12.dp)) {
            sitePermissions.forEachIndexed { index, permission ->
                val host = runCatching { Uri.parse(permission.uri).host ?: permission.uri }.getOrDefault(permission.uri)
                val kind = when (permission.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> "Геолокация"
                    GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "Уведомления"
                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "Постоянное хранилище"
                    GeckoSession.PermissionDelegate.PERMISSION_XR -> "XR / VR"
                    GeckoSession.PermissionDelegate.PERMISSION_TRACKING -> "Исключение защиты от трекеров"
                    else -> "Разрешение сайта"
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(host, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("$kind · " + if (permission.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) "Разрешено" else "Запрещено", color = Muted, fontSize = 10.sp)
                    }
                    IconButton(onClick = { revokeSitePermission(permission) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Отозвать разрешение", tint = Peach)
                    }
                }
                if (index != sitePermissions.lastIndex) Divider(color = Border.copy(alpha = .55f))
            }
        }
    }
    Spacer(Modifier.height(13.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("ЗАПОМНЕННЫЕ РЕШЕНИЯ", color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Text("Сохранённые разрешения и запреты сайтов", color = Muted, fontSize = 10.sp)
        }
        if (savedDecisions.isNotEmpty()) TextButton(onClick = clearAllDecisions) { Text("Сбросить всё", color = Peach, fontSize = 10.sp) }
    }
    if (savedDecisions.isEmpty()) Text("Сохранённых решений нет.", color = Muted, fontSize = 11.sp)
    else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111A2E)).border(1.dp, Border, RoundedCornerShape(16.dp)).padding(horizontal = 11.dp)) {
        savedDecisions.toList().sortedBy { it.key }.forEachIndexed { index, entry ->
            val host = entry.key.substringBefore("|")
            val kind = when (entry.key.substringAfter("|", "")) {
                GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION.toString() -> "Геолокация"
                GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION.toString() -> "Уведомления"
                else -> "Разрешение сайта"
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(host, color = Paper, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(kind + " · " + if (entry.value) "Разрешено" else "Запрещено", color = if (entry.value) Color(0xFF62E6C2) else Peach, fontSize = 10.sp)
                }
                IconButton(onClick = { clearDecision(entry.key) }) { Icon(Icons.Default.Refresh, contentDescription = "Сбросить решение сайта", tint = Orange) }
            }
            if (index != savedDecisions.size - 1) Divider(color = Border.copy(alpha = .5f))
        }
    }
    Spacer(Modifier.height(13.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("ИСТОРИЯ ЗАПРОСОВ", color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (audit.isNotEmpty()) TextButton(onClick = clearAudit) { Text("Очистить", color = Peach, fontSize = 10.sp) }
    }
    if (audit.isEmpty()) Text("История появится после первого запроса сайта.", color = Muted, fontSize = 11.sp)
    else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111A2E)).border(1.dp, Border, RoundedCornerShape(16.dp)).padding(horizontal = 11.dp)) {
        audit.take(15).forEachIndexed { index, entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.host, color = Paper, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(entry.permission + " · " + entry.decision, color = Muted, fontSize = 10.sp, maxLines = 2)
                }
                Text(java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.time)), color = Muted, fontSize = 9.sp)
            }
            if (index != audit.take(15).lastIndex) Divider(color = Border.copy(alpha = .5f))
        }
    }
    Spacer(Modifier.height(13.dp))
    Text("ПРИВАТНОСТЬ И ОТОБРАЖЕНИЕ", color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    SettingSwitch("Тёмная схема сайтов", dark, setDark)
    SettingSwitch("Ночной режим страниц", night, setNight)
    Spacer(Modifier.height(14.dp))
    Text("РАЗРЕШЕНИЯ УСТРОЙСТВА", color = Orange, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    val privacyContext = LocalContext.current
    val permissions = listOf(
        Triple(Icons.Default.CameraAlt, "Камера", "Android спросит доступ при необходимости"),
        Triple(Icons.Default.Mic, "Микрофон", "Доступ контролируется системой Android"),
        Triple(Icons.Default.LocationOn, "Геолокация", "Сайт должен отдельно запросить местоположение"),
        Triple(Icons.Default.Notifications, "Уведомления", "Управляются настройками приложения Android")
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF111A2E))
        .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp)) {
        permissions.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Orange.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                    Icon(item.first, contentDescription = null, tint = Orange, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.second, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val runtimePermission = when (item.second) {
                        "Камера" -> android.Manifest.permission.CAMERA
                        "Микрофон" -> android.Manifest.permission.RECORD_AUDIO
                        else -> null
                    }
                    val isGranted = runtimePermission?.let {
                        androidx.core.content.ContextCompat.checkSelfPermission(privacyContext, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    Text(
                        when (isGranted) {
                            true -> "Разрешено Android · изменить в настройках"
                            false -> "Запрещено Android · изменить в настройках"
                            null -> item.third
                        },
                        color = when (isGranted) { true -> Color(0xFF62E6C2); false -> Peach; null -> Muted },
                        fontSize = 10.sp, lineHeight = 14.sp
                    )
                }
                Icon(Icons.Default.Lock, contentDescription = "Контроль Android", tint = Muted, modifier = Modifier.size(15.dp))
            }
            if (index != permissions.lastIndex) Divider(color = Border.copy(alpha = .55f))
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Фактический статус камеры и микрофона определяется Android. Изменять системные разрешения можно в настройках приложения.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    Spacer(Modifier.height(12.dp))
    Button(onClick = openAndroidSettings, modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink), shape = RoundedCornerShape(16.dp)) {
        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Открыть разрешения Android", fontWeight = FontWeight.Bold)
    }
}
@Composable private fun ReaderPanel(source:String, content:String, loading:Boolean, error:String, onRetry:()->Unit) {
    PanelTitle("Режим чтения", "Текст страницы без лишнего оформления")
    Text(source, color = Muted, fontSize = 10.sp, maxLines = 2)
    Spacer(Modifier.height(10.dp))
    when {
        loading -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(color = Orange, modifier = Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Подготавливаем страницу…", color = Muted, fontSize = 12.sp) }
        error.isNotBlank() -> Column { Text(error, color = Peach, fontSize = 13.sp); TextButton(onClick = onRetry) { Text("Повторить", color = Orange) } }
        else -> Text(content, color = Paper, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()))
    }
    Spacer(Modifier.height(8.dp))
    Text("Текст извлекается через внешний сервис чтения страниц. Не отправляй туда страницы с личными данными.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
}



@Composable
private fun OfflinePagesPanel(
    offlinePages: List<OfflinePage>,
    onOpen: (OfflinePage) -> Unit,
    onRemove: (OfflinePage) -> Unit
) {
    PanelTitle("Офлайн-страницы", "Сохранённый текст доступен без подключения")
    if (offlinePages.isEmpty()) {
        Text("Пока нет сохранённых страниц. Открой сайт → Меню → «Сохранить офлайн».", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(offlinePages, key = { it.url }) { page ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF141D33),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(page) }.padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(page.title, color = Paper, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(page.url, color = Muted, fontSize = 10.sp, maxLines = 1)
                            Text("${page.content.length} символов · только текст", color = Peach, fontSize = 10.sp)
                        }
                        IconButton(onClick = { onRemove(page) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить", tint = Muted)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Сохраняется извлечённый текст, а не полный сайт: изображения, видео и интерактивные элементы офлайн недоступны.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
}

@Composable
private fun OfflinePageViewerPanel(page: OfflinePage?, onBack: () -> Unit) {
    PanelTitle(page?.title ?: "Офлайн-просмотр", "Сохранённая копия текста")
    TextButton(onClick = onBack) { Text("← К списку страниц", color = Orange) }
    if (page == null) {
        Text("Страница не найдена.", color = Muted)
    } else {
        Text(page.url, color = Muted, fontSize = 10.sp, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Text(
            page.content,
            color = Paper,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
        )
    }
}

@Composable private fun ExtensionsPanel(ad:Boolean, setAd:(Boolean)->Unit, night:Boolean, setNight:(Boolean)->Unit, install:()->Unit) {
    PanelTitle("Расширения", "Встроенные инструменты браузера")
    SettingSwitch("Усиленная защита от рекламы и трекеров", ad, setAd)
    SettingSwitch("Ночной режим страниц", night, setNight)
    Text("Менеджер паролей пока не подключён — переключатель не показываем.", color=Muted, fontSize=12.sp, lineHeight=17.sp, modifier=Modifier.padding(top=8.dp))
    Spacer(Modifier.height(18.dp))
    Button(onClick=install, modifier=Modifier.fillMaxWidth().height(52.dp), colors=ButtonDefaults.buttonColors(containerColor=Orange, contentColor=Ink), shape=RoundedCornerShape(18.dp)) { Text("Установить из файла", fontWeight=FontWeight.Bold) }
}
@Composable private fun AccountPanel(onLogin:()->Unit) {
    PanelTitle("Аккаунт", "Darknes ID")
    Text("Вы без входа", color=Paper, fontSize=20.sp, fontWeight=FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    Text("Браузер работает без аккаунта. Вход в Darknes понадобится для синхронизации вкладок и закладок.", color=Muted, fontSize=14.sp, lineHeight=21.sp)
    Spacer(Modifier.height(24.dp))
    Button(onClick=onLogin, modifier=Modifier.fillMaxWidth().height(52.dp), colors=ButtonDefaults.buttonColors(containerColor=Orange, contentColor=Ink), shape=RoundedCornerShape(18.dp)) { Text("Войти в Darknes", fontWeight=FontWeight.Bold) }
}
@Composable private fun ChatPanel(onOpenAi:()->Unit) {
    PanelTitle("Darknes AI", "Приватный ИИ-помощник")
    Surface(Modifier.fillMaxWidth(), color = Color(0xFF141D33), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(16.dp)) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Orange, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text("Общайся с ИИ, задавай вопросы, проси объяснить или кратко пересказать текст.", color = Paper, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("Чат открывается через Duck.ai. Это внешний сервис DuckDuckGo; для использования нужен интернет. Его модели и обработка запросов регулируются политикой DuckDuckGo.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenAi, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink), shape = RoundedCornerShape(17.dp)) {
                Icon(Icons.Default.Chat, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть ИИ-чат", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TabsPanel(
    titles: List<String>, urls: List<String>, groups: List<String>, groupNames: List<String>, pinned: List<Boolean>, workspaceWallpapers: Map<String, String>, workspaceTracking: Map<String, Boolean>, chooseWorkspaceWallpaper: (String) -> Unit, activeIndex: Int,
    incognito: Boolean, setIncognito: (Boolean) -> Unit, addTab: () -> Unit,
    closeTab: (Int) -> Unit, selectTab: (Int) -> Unit, activateWorkspace: (String) -> Unit, cycleGroup: (Int) -> Unit, moveTab: (Int, Int) -> Unit,
    setTabGroup: (Int, String) -> Unit, togglePinned: (Int) -> Unit, saveWorkspace: (String, String, Boolean) -> Unit, createGroup: (String) -> Unit, renameGroup: (String, String) -> Unit, deleteGroup: (String) -> Unit,
    previews: List<Bitmap?>, capturePreview: () -> Unit,
    hasRecentlyClosed: Boolean, restoreRecentlyClosed: () -> Unit, done: () -> Unit
) {
    val tabAccent = if (incognito) Violet else Orange
    var tabGridColumns by remember { mutableIntStateOf(2) }
    var draggingTabIndex by remember { mutableIntStateOf(-1) }
    var dragPointerRoot by remember { mutableStateOf(Offset.Zero) }
    val groupDropBounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var selectedGroup by remember { mutableStateOf(groups.getOrNull(activeIndex) ?: "Все") }
    val availableGroups = remember(groups.toList(), groupNames) { listOf("Все") + (groupNames + groups).filter { it.isNotBlank() }.distinct() }
    var manageGroupsOpen by remember { mutableStateOf(false) }
    var workspaceDialogOpen by remember { mutableStateOf(false) }
    var workspaceGroup by remember { mutableStateOf(groupNames.firstOrNull() ?: "Основная") }
    var workspaceWallpaperDraft by remember { mutableStateOf("") }
    var workspaceTrackingDraft by remember { mutableStateOf(true) }
    LaunchedEffect(workspaceGroup, workspaceWallpapers.toMap(), workspaceTracking.toMap()) {
        workspaceWallpaperDraft = workspaceWallpapers[workspaceGroup].orEmpty()
        workspaceTrackingDraft = workspaceTracking[workspaceGroup] ?: true
    }
    var newGroupName by remember { mutableStateOf("") }
    var editingGroup by remember { mutableStateOf<String?>(null) }
    var editingGroupName by remember { mutableStateOf("") }
    val groupColorPrefs = remember { androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("darknes_tabs", android.content.Context.MODE_PRIVATE) }
    val groupColors = remember { mutableStateMapOf<String, Long>().apply {
        runCatching { val json = org.json.JSONObject(groupColorPrefs.getString("group_colors", "{}") ?: "{}"); json.keys().forEach { key -> put(key, json.optLong(key)) } }
    } }
    val palette = listOf(0xFF35D6E8L, 0xFF8B7CFFL, 0xFFFFA45BL, 0xFF63D69BL, 0xFFFF6F91L)
    LaunchedEffect(groupColors.toMap()) { val json = org.json.JSONObject(); groupColors.forEach { (key, value) -> json.put(key, value) }; groupColorPrefs.edit().putString("group_colors", json.toString()).apply() }
    val visibleIndices = remember(titles.size, groups.toList(), pinned, selectedGroup) {
        titles.indices.filter { selectedGroup == "Все" || groups.getOrNull(it) == selectedGroup }
            .sortedWith(compareByDescending<Int> { pinned.getOrNull(it) == true }.thenBy { it })
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Вкладки", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = (-.5).sp)
                Text("ТВОЁ ПРОСТРАНСТВО · DARKNES", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                Text("${titles.size} вкладок · ${groups.distinct().size} групп", color = tabAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
            }
            TextButton(onClick = { tabGridColumns = if (tabGridColumns == 2) 3 else 2 }) {
                Icon(Icons.Default.GridView, contentDescription = "Изменить плотность сетки", tint = tabAccent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (tabGridColumns == 2) "2×" else "3×", color = tabAccent, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(tabAccent.copy(alpha = .25f), Violet.copy(alpha = .16f))))
                .border(1.dp, tabAccent.copy(alpha = .45f), RoundedCornerShape(14.dp))
                .clickable { addTab() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = "Новая вкладка", tint = tabAccent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (hasRecentlyClosed) {
                TextButton(onClick = restoreRecentlyClosed) {
                    Icon(Icons.Default.Restore, contentDescription = null, tint = tabAccent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Вернуть", color = tabAccent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                }
            }
            IconButton(onClick = { workspaceGroup = groups.getOrNull(activeIndex) ?: groupNames.firstOrNull() ?: "Основная"; workspaceDialogOpen = true }) {
                Icon(Icons.Default.Tune, contentDescription = "Настройки рабочего пространства", tint = tabAccent)
            }
            IconButton(onClick = { manageGroupsOpen = true }) { Icon(Icons.Default.FolderSpecial, contentDescription = "Управление группами", tint = tabAccent) }
            if (!incognito) IconButton(onClick = capturePreview) { Icon(Icons.Default.PhotoCamera, contentDescription = "Снять миниатюру текущей страницы", tint = tabAccent) }
            TextButton(onClick = done) { Text("Готово", color = Paper, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF101A30), Color(0xFF17172F))))
            .border(1.dp, Border.copy(alpha = .75f), RoundedCornerShape(18.dp)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !incognito, onClick = { setIncognito(false) }, label = { Text("Обычные") },
                modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Orange.copy(alpha = .2f), selectedLabelColor = Paper, labelColor = Muted))
            FilterChip(selected = incognito, onClick = { setIncognito(true) }, label = { Text("Секретные") },
                modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Violet.copy(alpha = .22f), selectedLabelColor = Color(0xFFE4DCFF), labelColor = Muted))
        }
        Spacer(Modifier.height(12.dp))
        Text("РАБОЧИЕ ПРОСТРАНСТВА", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.25.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            groupNames.forEachIndexed { groupIndex, group ->
                val groupTabs = groups.indices.filter { groups.getOrNull(it) == group }
                val groupPreview = groupTabs.firstOrNull()?.let { previews.getOrNull(it) }
                val groupTint = Color(groupColors[group] ?: palette[groupIndex % palette.size])
                val isWorkspaceActive = selectedGroup == group
                Column(
                    Modifier.width(142.dp).clip(RoundedCornerShape(17.dp))
                        .background(Brush.linearGradient(listOf(groupTint.copy(alpha = if (isWorkspaceActive) .30f else .16f), Color(0xFF10182B))))
                        .border(if (isWorkspaceActive) 2.dp else 1.dp, groupTint.copy(alpha = if (isWorkspaceActive) .95f else .38f), RoundedCornerShape(17.dp))
                        .clickable { selectedGroup = group; activateWorkspace(group) }
                        .padding(8.dp).animateContentSize()
                ) {
                    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(11.dp))
                        .background(Brush.linearGradient(listOf(groupTint.copy(alpha = .28f), Color(0xFF0A1222))))) {
                        if (groupPreview != null && !incognito) {
                            Image(bitmap = groupPreview.asImageBitmap(), contentDescription = "Предпросмотр пространства $group",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = groupTint.copy(alpha = .85f),
                                modifier = Modifier.align(Alignment.Center).size(25.dp))
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(5.dp).clip(CircleShape)
                            .background(Color(0xCC080F1D)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(groupTabs.size.toString(), color = Paper, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(group, color = Paper, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(if (isWorkspaceActive) "ОТКРЫТО" else "ПЕРЕКЛЮЧИТЬ",
                        color = groupTint, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .55.sp)
                }
            }
            Column(Modifier.width(86.dp).height(103.dp).clip(RoundedCornerShape(17.dp))
                .background(Color(0xFF10182B)).border(1.dp, Border, RoundedCornerShape(17.dp))
                .clickable { selectedGroup = "Все" }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Apps, tint = Muted, contentDescription = null, modifier = Modifier.size(23.dp))
                Spacer(Modifier.height(5.dp))
                Text("Все", color = Paper, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(titles.size.toString(), color = Muted, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(13.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            availableGroups.forEach { group ->
                val selected = selectedGroup == group || (draggingTabIndex >= 0 && groupDropBounds[group]?.contains(dragPointerRoot) == true)
                FilterChip(
                    selected = selected,
                    onClick = { selectedGroup = group; if (group != "Все") activateWorkspace(group) },
                    modifier = Modifier.onGloballyPositioned { groupDropBounds[group] = it.boundsInRoot() },
                    label = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Text(group, maxLines = 1); if (group != "Все") Text(groups.count { it == group }.toString(), fontSize = 10.sp, color = tabAccent.copy(alpha = .85f), fontWeight = FontWeight.Bold) } },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(groupColors[group] ?: palette[groupNames.indexOf(group).coerceAtLeast(0) % palette.size]).copy(alpha = .24f),
                        selectedLabelColor = Paper,
                        labelColor = Muted
                    )
                )
            }
        }
        if (workspaceDialogOpen) {
            AlertDialog(
                onDismissRequest = { workspaceDialogOpen = false },
                title = { Text("Рабочее пространство", color = Paper) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Профиль хранится отдельно для каждой группы.", color = Muted, fontSize = 11.sp)
                        var groupMenu by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { groupMenu = true }) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(7.dp)); Text(workspaceGroup) }
                            DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                                groupNames.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { workspaceGroup = name; groupMenu = false }) }
                            }
                        }
                        OutlinedTextField(value = workspaceWallpaperDraft, onValueChange = { workspaceWallpaperDraft = it.take(500) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("URI обоев изображения") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { chooseWorkspaceWallpaper(workspaceGroup) }) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(5.dp)); Text("Выбрать фото") }
                            TextButton(onClick = { workspaceWallpaperDraft = "" }) { Text("Сбросить") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Защита от отслеживания", color = Paper, fontWeight = FontWeight.SemiBold)
                                Text("Настройка защиты GeckoView для группы", color = Muted, fontSize = 10.sp)
                            }
                            Switch(checked = workspaceTrackingDraft, onCheckedChange = { workspaceTrackingDraft = it })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { saveWorkspace(workspaceGroup, workspaceWallpaperDraft.trim(), workspaceTrackingDraft); workspaceDialogOpen = false }) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { workspaceDialogOpen = false }) { Text("Отмена") } },
                containerColor = Color(0xFF151D31), titleContentColor = Paper, textContentColor = Paper
            )
        }
        if (manageGroupsOpen) {
            AlertDialog(
                onDismissRequest = { manageGroupsOpen = false; editingGroup = null },
                title = { Text("Группы вкладок", color = Paper) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = newGroupName, onValueChange = { newGroupName = it.take(24) }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Новая группа") })
                            IconButton(onClick = { val name = newGroupName.trim(); if (name.isNotBlank() && name !in groupNames) { createGroup(name); newGroupName = "" } }) { Icon(Icons.Default.AddCircle, contentDescription = "Создать группу", tint = tabAccent) }
                        }
                        groupNames.forEach { group ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(groupColors[group] ?: palette[groupNames.indexOf(group) % palette.size])))
                                if (editingGroup == group) {
                                    OutlinedTextField(value = editingGroupName, onValueChange = { editingGroupName = it.take(24) }, modifier = Modifier.weight(1f), singleLine = true)
                                    IconButton(onClick = { val next = editingGroupName.trim(); if (next.isNotBlank() && next !in groupNames) renameGroup(group, next); editingGroup = null }) { Icon(Icons.Default.Check, contentDescription = "Сохранить") }
                                } else {
                                    Text(group, color = Paper, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { val current = palette.indexOf(groupColors[group] ?: palette[groupNames.indexOf(group) % palette.size]); groupColors[group] = palette[(current + 1).mod(palette.size)] }) { Icon(Icons.Default.Palette, contentDescription = "Изменить цвет", tint = tabAccent) }
                                    IconButton(onClick = { editingGroup = group; editingGroupName = group }) { Icon(Icons.Default.Edit, contentDescription = "Переименовать", tint = Muted) }
                                    if (group != "Основная") IconButton(onClick = { deleteGroup(group); groupColors.remove(group); if (selectedGroup == group) selectedGroup = "Все" }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить", tint = Color(0xFFFF7188)) }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { manageGroupsOpen = false; editingGroup = null }) { Text("Готово") } },
                containerColor = Color(0xFF151D31),
                titleContentColor = Paper,
                textContentColor = Paper
            )
        }
        Spacer(Modifier.height(12.dp))
        if (titles.isEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF10182D))
                .border(1.dp, Border, RoundedCornerShape(22.dp)).padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Tab, contentDescription = null, tint = tabAccent, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(9.dp))
                    Text("Нет открытых вкладок", color = Paper, fontWeight = FontWeight.SemiBold)
                    Text("Создай новую вкладку, чтобы продолжить", color = Muted, fontSize = 11.sp)
                }
            }
        } else if (visibleIndices.isEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF10182D))
                .border(1.dp, Border, RoundedCornerShape(20.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
                Text("В этой группе пока нет вкладок", color = Muted, fontSize = 12.sp)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(tabGridColumns), modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                .animateContentSize(spring(dampingRatio = .82f, stiffness = 360f)),
                contentPadding = PaddingValues(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visibleIndices.size, key = { position -> visibleIndices[position] }) { visiblePosition ->
                    val index = visibleIndices[visiblePosition]
                    val isActive = index == activeIndex
                    val pageUrl = urls.getOrNull(index).orEmpty()
                    val host = runCatching { java.net.URI(pageUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
                    val cardShape = RoundedCornerShape(21.dp)
                    val cardColors = if (incognito) listOf(Color(0xFF29245A), Color(0xFF11172D)) else listOf(Color(0xFF17334A), Color(0xFF0B1428))
                    var dragOffset by remember(index) { mutableStateOf(Offset.Zero) }
                    val dragScale by animateFloatAsState(if (dragOffset != Offset.Zero) 1.035f else 1f, label = "tab-drag-scale")
                    var groupMenuExpanded by remember(index) { mutableStateOf(false) }
                    var cardOrigin by remember(index) { mutableStateOf(Offset.Zero) }
                    val previewBitmap = previews.getOrNull(index)
                    Box(Modifier.fillMaxWidth().aspectRatio(if (tabGridColumns == 3) .66f else .76f).graphicsLayer {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = dragScale
                            scaleY = dragScale
                        }.clip(cardShape).background(Brush.linearGradient(cardColors))
                        .border(if (isActive) 2.dp else 1.dp, if (isActive) tabAccent else Border, cardShape)
                        .onGloballyPositioned { cardOrigin = it.positionInRoot() }
                        .pointerInput(index, visibleIndices, availableGroups) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    draggingTabIndex = index
                                    dragPointerRoot = cardOrigin + start
                                    dragOffset = Offset.Zero
                                },
                                onDragEnd = {
                                    val targetGroup = groupDropBounds.entries.firstOrNull { it.value.contains(dragPointerRoot) }?.key
                                    val horizontalStep = if (dragOffset.x > 90.dp.toPx()) 1 else if (dragOffset.x < -90.dp.toPx()) -1 else 0
                                    val verticalStep = if (dragOffset.y > 150.dp.toPx()) 2 else if (dragOffset.y < -150.dp.toPx()) -2 else 0
                                    val targetPosition = (visiblePosition + horizontalStep + verticalStep).coerceIn(0, visibleIndices.lastIndex)
                                    val targetIndex = visibleIndices.getOrNull(targetPosition)
                                    dragOffset = Offset.Zero
                                    draggingTabIndex = -1
                                    if (targetGroup != null && targetGroup != "Все" && targetGroup != groups.getOrNull(index)) setTabGroup(index, targetGroup)
                                    else if (targetIndex != null && targetIndex != index) moveTab(index, targetIndex)
                                },
                                onDragCancel = { dragOffset = Offset.Zero; draggingTabIndex = -1 },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                    dragPointerRoot += amount
                                }
                            )
                        }
                        .clickable { selectTab(index) }.animateContentSize().padding(13.dp)) {
                        Column(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.fillMaxWidth().height(if (tabGridColumns == 3) 76.dp else 94.dp).clip(RoundedCornerShape(14.dp))
                                    .background(Brush.linearGradient(cardColors))
                            ) {
                                if (previewBitmap != null && !incognito) {
                                    Image(bitmap = previewBitmap.asImageBitmap(), contentDescription = "Миниатюра страницы", contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                } else {
                                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(if (incognito) Icons.Default.VisibilityOff else Icons.Default.Public, contentDescription = null,
                                            tint = tabAccent.copy(alpha = .62f), modifier = Modifier.size(if (tabGridColumns == 3) 25.dp else 31.dp))
                                        Text(if (pageUrl.isBlank()) "НОВАЯ ВКЛАДКА" else host.take(20).uppercase(),
                                            color = Paper.copy(alpha = .52f), fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                                    }
                                }
                                Column(Modifier.fillMaxSize().padding(9.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f).height(17.dp).clip(RoundedCornerShape(6.dp))
                                            .background(Color(0x553D5473)).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                                            Text(host.ifBlank { "darknes://start" }, color = Paper.copy(alpha = .72f), fontSize = 7.sp, maxLines = 1)
                                        }
                                        Spacer(Modifier.width(5.dp))
                                        Box(Modifier.size(17.dp).clip(CircleShape).background(Color(0x554A6080)), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Close, null, tint = Paper.copy(alpha = .8f), modifier = Modifier.size(10.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(9.dp))
                                    Box(Modifier.fillMaxWidth(.58f).height(7.dp).clip(CircleShape).background(tabAccent.copy(alpha = .75f)))
                                    Spacer(Modifier.height(6.dp))
                                    Box(Modifier.fillMaxWidth(.88f).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = .20f)))
                                    Spacer(Modifier.height(5.dp))
                                    Box(Modifier.fillMaxWidth(.70f).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = .13f)))
                                    Spacer(Modifier.weight(1f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        repeat(3) { Box(Modifier.weight(1f).height(15.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = .08f))) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tabAccent.copy(alpha = .17f))
                                    .border(1.dp, tabAccent.copy(alpha = .28f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Icon(if (incognito) Icons.Default.VisibilityOff else Icons.Default.Public, contentDescription = null, tint = tabAccent, modifier = Modifier.size(19.dp))
                                }
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("ВКЛАДКА ${index + 1}", color = Muted, fontSize = 8.sp, letterSpacing = .8.sp, fontWeight = FontWeight.Bold)
                                    if (isActive) Text("АКТИВНА", color = tabAccent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(titles[index].ifBlank { "Новая вкладка" }, color = Paper, fontWeight = FontWeight.Bold, fontSize = if (tabGridColumns == 3) 11.sp else 13.sp, maxLines = 2)
                            Spacer(Modifier.height(5.dp))
                            Text(host.ifBlank { if (pageUrl.isBlank()) "Стартовая страница" else pageUrl }, color = Paper.copy(alpha = .62f), fontSize = 9.sp, maxLines = 2)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = { togglePinned(index) }, enabled = !incognito, modifier = Modifier.size(27.dp)) {
                                    Icon(Icons.Default.PushPin,
                                        contentDescription = if (pinned.getOrNull(index) == true) "Открепить вкладку" else "Закрепить вкладку",
                                        tint = if (pinned.getOrNull(index) == true) tabAccent else Muted,
                                        modifier = Modifier.size(15.dp))
                                }
                                Row(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(Color(0x332B4268))
                                    .clickable { groupMenuExpanded = true }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = tabAccent, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(groups.getOrNull(index) ?: "Основная", color = Paper.copy(alpha = .82f), fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.Cached, contentDescription = "Изменить группу", tint = tabAccent, modifier = Modifier.size(12.dp))
                                    DropdownMenu(expanded = groupMenuExpanded, onDismissRequest = { groupMenuExpanded = false }) {
                                        availableGroups.filter { it != "Все" }.forEach { group -> DropdownMenuItem(text = { Text(group) }, onClick = { setTabGroup(index, group); groupMenuExpanded = false }) }
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    IconButton(onClick = { moveTab(index, (index - 1).coerceAtLeast(0)) }, enabled = index > 0,
                                        modifier = Modifier.size(23.dp)) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Переместить выше", tint = if (index > 0) tabAccent else Muted, modifier = Modifier.size(17.dp))
                                    }
                                    IconButton(onClick = { moveTab(index, (index + 1).coerceAtMost(titles.lastIndex)) }, enabled = index < titles.lastIndex,
                                        modifier = Modifier.size(23.dp)) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Переместить ниже", tint = if (index < titles.lastIndex) tabAccent else Muted, modifier = Modifier.size(17.dp))
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { closeTab(index) }, modifier = Modifier.align(Alignment.TopEnd).size(29.dp)
                            .clip(CircleShape).background(Color(0x66200000))) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть вкладку", tint = Paper, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
        Text("Переключай плотность сетки кнопкой 2×/3×. Нажми карточку для перехода, используй × для закрытия, удерживай её для сортировки и переноса между группами.", color = Muted,
            fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 7.dp))
    }
}
