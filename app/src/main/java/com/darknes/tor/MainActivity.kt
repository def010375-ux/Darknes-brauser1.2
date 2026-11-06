package com.darknes.tor

import android.os.Bundle
import android.content.Intent
import android.os.SystemClock
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONArray
import java.net.URLEncoder
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

private val Ink = Color(0xFF050914)
private val Paper = Color(0xFFEAF6FF)
private val Orange = Color(0xFF42DFFF)
private val Peach = Color(0xFFB9F4FF)
private val Violet = Color(0xFF9A83FF)
private val Muted = Color(0xFF8498B5)
private val Glass = Color(0xE6101B2D)
private val Border = Color(0x5549DFFF)

private data class DownloadRecord(val id: Long, val name: String, val status: String, val savedUri: String? = null, val downloadedBytes: Long = 0L, val totalBytes: Long = 0L)

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


private data class LinkRiskReport(val host: String, val warnings: List<String>) {
    val isSuspicious: Boolean get() = warnings.isNotEmpty()
}

private fun analyzeLinkRisk(raw: String): LinkRiskReport? {
    val candidate = raw.trim()
    if (candidate.isBlank()) return null
    val normalized = if (candidate.contains("://")) candidate else "https://$candidate"
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return LinkRiskReport("неизвестен", listOf("Не удалось разобрать адрес"))
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return LinkRiskReport("неизвестен", listOf("В адресе отсутствует корректное доменное имя"))
    val warnings = mutableListOf<String>()
    if (scheme != "https") warnings += "Соединение не использует HTTPS"
    if (candidate.contains("@")) warnings += "В адресе есть символ @ или данные пользователя — возможна маскировка настоящего адреса"
    if (host.startsWith("xn--") || host.split('.').any { it.startsWith("xn--") }) warnings += "Домен использует Punycode — проверь похожие символы и подмену букв"
    if (host.matches(Regex("^\\\\d{1,3}(\\\\.\\\\d{1,3}){3}$"))) warnings += "Используется IP-адрес вместо обычного домена"
    if (host.split('.').size >= 5) warnings += "Необычно много поддоменов"
    val suspiciousWords = listOf("login", "verify", "secure", "account", "wallet", "password", "update", "bonus", "free", "support")
    if (suspiciousWords.any { it in host }) warnings += "В домене встречаются слова, часто используемые в приманках (login/verify/secure и т. п.)"
    if (host.contains("--")) warnings += "В домене есть двойной дефис"
    return LinkRiskReport(host, warnings.distinct())
}

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


private data class BrowserTabSnapshot(val title: String, val url: String)

private fun readBrowserTabs(raw: String?): List<BrowserTabSnapshot> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val url = item.optString("url")
        if (url.isNotBlank() && !url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        BrowserTabSnapshot(item.optString("title").ifBlank { "Новая вкладка" }, url)
    }.take(30).ifEmpty { listOf(BrowserTabSnapshot("Новая вкладка", "")) }
}.getOrDefault(listOf(BrowserTabSnapshot("Новая вкладка", "")))

private fun encodeBrowserTabs(titles: List<String>, urls: List<String>): String = JSONArray().apply {
    titles.indices.take(30).forEach { index ->
        val url = urls.getOrNull(index).orEmpty()
        if (url.isBlank() || url.startsWith("https://") || url.startsWith("http://")) {
            put(org.json.JSONObject().put("title", titles.getOrNull(index).orEmpty().ifBlank { "Новая вкладка" }).put("url", url))
        }
    }
}.toString()

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
                updateDownload(recordId, "Скачивается")
                Thread {
                    try {
                        val body = response.body ?: throw IllegalStateException("Нет данных файла")
                        val output = contentResolver.openOutputStream(destination)
                            ?: throw IllegalStateException("Не удалось открыть место сохранения")
                        val totalBytes = response.headers.entries.firstOrNull { it.key.equals("content-length", ignoreCase = true) }?.value?.toLongOrNull() ?: 0L
                        var copiedBytes = 0L
                        var lastReportedBytes = 0L
                        body.use { input -> output.use { target ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
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
                        updateDownload(recordId, "Сохранено", destination.toString(), copiedBytes, totalBytes)
                        runOnUiThread { Toast.makeText(this, "Файл сохранён", Toast.LENGTH_LONG).show() }
                    } catch (error: Exception) {
                        response.body?.close()
                        updateDownload(recordId, "Ошибка")
                        runOnUiThread { Toast.makeText(this, "Не удалось скачать файл: ${error.message}", Toast.LENGTH_LONG).show() }
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
            DarknesApp(runtime, ::requestDownload, downloadRecords.toList(), ::removeDownload)
        } }
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
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}

private data class SearchResult(val title: String, val url: String, val snippet: String, val displayUrl: String)
private data class PublishedVersion(val name: String, val tag: String, val notes: String, val apkUrl: String?, val apkSizeBytes: Long?, val apkDigest: String?)

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
            var apkSizeBytes: Long? = null
            var apkDigest: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    apkSizeBytes = asset.optLong("size", -1L).takeIf { it >= 0L }
                    apkDigest = asset.optString("digest").takeIf { it.startsWith("sha256:") }
                    if (apkUrl != null) break
                }
            }
            PublishedVersion(
                name = release.optString("name").ifBlank { release.optString("tag_name") },
                tag = release.optString("tag_name"),
                notes = release.optString("body").take(500),
                apkUrl = apkUrl,
                apkSizeBytes = apkSizeBytes,
                apkDigest = apkDigest
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
    if (s.startsWith("https://") || s.startsWith("http://")) return s
    if (s.contains(".") && !s.contains(" ")) return "https://$s"
    val q = java.net.URLEncoder.encode(s, "UTF-8")
    return "https://duckduckgo.com/?q=$q"
}


@Composable
private fun DarknesLaunchGate(onAuthenticated: () -> Unit) {
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
        showLogin = true
    }

    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0A1425), Ink, Color(0xFF050914)))
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
                border = BorderStroke(1.dp, Color(0x55FF9A43)),
                shadowElevation = 22.dp
            ) {
                Column(Modifier.padding(18.dp)) {
                    DarknesIdPanel(onAuthenticated = onAuthenticated)
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
    LaunchedEffect(Unit) {
        arrival.animateTo(1f, tween(1150, easing = FastOutSlowInEasing))
    }
    val infinite = rememberInfiniteTransition(label = "black-hole")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing)),
        label = "disk-rotation"
    )
    val particleFlow by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1750, easing = LinearEasing)),
        label = "particle-flow"
    )
    Box(modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            val progress = arrival.value
            alpha = progress
            scaleX = 0.42f + progress * 0.58f
            scaleY = 0.42f + progress * 0.58f
            translationY = -105.dp.toPx() * (1f - progress)
        }) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val unit = minOf(size.width, size.height)
            val rx = unit * 0.43f
            val ry = unit * 0.19f

            drawOval(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.40f), accent.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = unit * 0.48f
                ),
                topLeft = Offset(cx - rx * 1.22f, cy - ry * 2.15f),
                size = androidx.compose.ui.geometry.Size(rx * 2.44f, ry * 4.3f)
            )

            // A tilted, perspective-compressed accretion disk, seen from the side.
            rotate(-13f, pivot = Offset(cx, cy)) {
                rotate(rotation, pivot = Offset(cx, cy)) {
                    drawOval(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color.Transparent, accent.copy(alpha = 0.9f), accent,
                                Color(0xFFFFF0D0), accent.copy(alpha = 0.85f), Color.Transparent
                            ),
                            center = Offset(cx, cy)
                        ),
                        topLeft = Offset(cx - rx, cy - ry),
                        size = androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f),
                        style = Stroke(width = unit * 0.105f)
                    )
                    drawOval(
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, Color(0xFFFFE0A8), accent, Color.Transparent),
                            start = Offset(cx - rx, cy - ry),
                            end = Offset(cx + rx, cy + ry)
                        ),
                        topLeft = Offset(cx - rx * 0.83f, cy - ry * 0.67f),
                        size = androidx.compose.ui.geometry.Size(rx * 1.66f, ry * 1.34f),
                        style = Stroke(width = unit * 0.035f)
                    )
                    if (showParticles) for (i in 0 until 46) {
                        val lane = (i % 9) / 9f
                        val angle = (i * 0.71f + rotation * Math.PI.toFloat() / 180f * (1.15f + lane))
                        val radius = (0.22f + ((i * 37) % 100) / 145f) * (1f - particleFlow * 0.12f)
                        val px = cx + kotlin.math.cos(angle.toDouble()).toFloat() * rx * radius
                        val py = cy + kotlin.math.sin(angle.toDouble()).toFloat() * ry * radius
                        val particleSize = unit * (0.0035f + (i % 4) * 0.0012f)
                        drawCircle(
                            color = if (i % 4 == 0) Color(0xFFFFE2B2) else accent,
                            radius = particleSize,
                            center = Offset(px, py),
                            alpha = 0.5f + (i % 5) * 0.1f
                        )
                    }
                }
            }

            // Dark event horizon and the bright gravitational-lensing rim.
            drawOval(
                brush = Brush.radialGradient(
                    listOf(Color.Black, Color(0xFF020202), Color(0xFF120904)),
                    center = Offset(cx, cy),
                    radius = unit * 0.18f
                ),
                topLeft = Offset(cx - unit * 0.17f, cy - unit * 0.105f),
                size = androidx.compose.ui.geometry.Size(unit * 0.34f, unit * 0.21f)
            )
            drawOval(
                color = accent.copy(alpha = 0.8f),
                topLeft = Offset(cx - unit * 0.174f, cy - unit * 0.108f),
                size = androidx.compose.ui.geometry.Size(unit * 0.348f, unit * 0.216f),
                style = Stroke(width = unit * 0.006f)
            )

            // Infalling sparks spiral toward the event horizon.
            if (showParticles) for (i in 0 until 30) {
                val t = (i / 30f + particleFlow) % 1f
                val angle = i * 2.4f + t * 8.5f
                val radius = unit * (0.43f - t * 0.29f)
                val px = cx + kotlin.math.cos(angle.toDouble()).toFloat() * radius
                val py = cy + kotlin.math.sin(angle.toDouble()).toFloat() * radius * 0.34f
                drawCircle(
                    color = accent.copy(alpha = 0.95f),
                    radius = unit * (0.003f + (1f - t) * 0.003f),
                    center = Offset(px, py),
                    alpha = 0.8f - t * 0.45f
                )
            }
        }

        val bubble = Modifier.size(39.dp).clip(CircleShape)
            .background(Color(0xE62A1B12))
            .border(1.dp, accent.copy(alpha = 0.55f), CircleShape)
        @Composable fun BurstIcon(
            icon: ImageVector,
            description: String,
            travelX: Dp,
            travelY: Dp
        ) {
            Box(
                Modifier.align(Alignment.Center)
                    .offset(x = travelX * iconBurst, y = travelY * iconBurst)
                    .then(bubble)
                    .graphicsLayer {
                        val spread = 0.18f + iconBurst * 0.82f
                        scaleX = spread
                        scaleY = spread
                        alpha = iconBurst
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = description, tint = Peach, modifier = Modifier.size(19.dp))
            }
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
private fun DarknesApp(runtime: GeckoRuntime, onDownload: (WebResponse) -> Unit, downloads: List<DownloadRecord>, onRemoveDownload: (Long) -> Unit) {
    var launchAuthenticated by remember { mutableStateOf(false) }
    if (!launchAuthenticated) {
        DarknesLaunchGate(onAuthenticated = { launchAuthenticated = true })
        return
    }
    val context = LocalContext.current
    val tabsPrefs = remember(context) { context.getSharedPreferences("darknes_tabs", android.content.Context.MODE_PRIVATE) }
    val restoredTabs = remember(tabsPrefs) {
        readBrowserTabs(tabsPrefs.getString("tabs", null))
    }
    var incognito by remember { mutableStateOf(false) }
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
    val tabTitles = remember { mutableStateListOf<String>().apply { addAll(restoredTabs.map { it.title }) } }
    val tabUrls = remember { mutableStateListOf<String>().apply { addAll(restoredTabs.map { it.url }) } }
    val tabGroups = remember(tabsPrefs) {
        val stored = runCatching {
            val array = JSONArray(tabsPrefs.getString("groups", "[]") ?: "[]")
            (0 until array.length()).map { array.optString(it, "Основная") }
        }.getOrDefault(emptyList())
        mutableStateListOf<String>().apply {
            addAll(restoredTabs.indices.map { stored.getOrNull(it)?.takeIf { group -> group.isNotBlank() } ?: "Основная" })
        }
    }
    var activeTab by remember { mutableIntStateOf(tabsPrefs.getInt("active", 0).coerceIn(0, restoredTabs.lastIndex)) }
    val session = sessions[activeTab.coerceIn(0, sessions.lastIndex)]
    val splitSession = remember(runtime) { GeckoSession().also { it.open(runtime) } }
    var splitScreen by remember { mutableStateOf(false) }
    DisposableEffect(splitSession) {
        onDispose { splitSession.close() }
    }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    fun addTab() {
        if (incognito) privateModeUsed = true
        sessions.add(GeckoSession().also { it.open(runtime) })
        tabTitles.add("Новая вкладка")
        tabUrls.add("")
        tabGroups.add("Основная")
        activeTab = sessions.lastIndex
        canGoBack = false
        canGoForward = false
    }
    fun closeTab(index: Int) {
        if (sessions.size > 1) {
            sessions[index].close()
            sessions.removeAt(index)
            tabTitles.removeAt(index)
            tabUrls.removeAt(index)
            tabGroups.removeAt(index)
            activeTab = when {
                index < activeTab -> activeTab - 1
                index == activeTab -> (index - 1).coerceAtLeast(0)
                else -> activeTab
            }
        } else {
            sessions[0].purgeHistory()
            sessions[0].loadUri("about:blank")
            tabTitles[0] = "Новая вкладка"
            tabUrls[0] = ""
            canGoBack = false
            canGoForward = false
        }
    }
    val settingsPrefs = remember(context) { context.getSharedPreferences("darknes_settings", android.content.Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf(tabUrls.getOrNull(activeTab).orEmpty()) }
    var islandOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf("DuckDuckGo") }
    val onLoad: (String) -> Unit = { session.loadUri(resolveAddress(it, engine)) }
    fun requestSafeNavigation(raw: String) {
        val resolved = resolveAddress(raw, engine)
        val report = analyzeLinkRisk(resolved)
        if (report?.isSuspicious == true) {
            loaded = false
            pendingRiskUrl = raw
            pendingRiskReport = report
        } else {
            onLoad(raw)
        }
    }
    var blur by remember { mutableFloatStateOf(settingsPrefs.getFloat("blur", 8f).coerceIn(0f, 24f)) }
    var animations by remember { mutableStateOf(settingsPrefs.getBoolean("animations", true)) }
    var trackers by remember { mutableStateOf(settingsPrefs.getBoolean("trackers", true)) }
    var darkSites by remember { mutableStateOf(settingsPrefs.getBoolean("darkSites", true)) }
    var adBlock by remember { mutableStateOf(settingsPrefs.getBoolean("adBlock", true)) }
    var nightPages by remember { mutableStateOf(settingsPrefs.getBoolean("nightPages", true)) }
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
    var pendingRiskUrl by remember { mutableStateOf<String?>(null) }
    var pendingRiskReport by remember { mutableStateOf<LinkRiskReport?>(null) }
    var readerContent by remember { mutableStateOf("") }
    var readerSource by remember { mutableStateOf("") }
    var readerLoading by remember { mutableStateOf(false) }
    var readerError by remember { mutableStateOf("") }
    var accentChoice by remember { mutableStateOf(settingsPrefs.getString("accent", "orange") ?: "orange") }
    var testerTapCount by remember { mutableIntStateOf(0) }
    var testerLastTap by remember { mutableLongStateOf(0L) }
    val keyboard = LocalSoftwareKeyboardController.current
    val savedPrefs = remember(context) { context.getSharedPreferences("darknes_browser_data", android.content.Context.MODE_PRIVATE) }
    val history = remember(savedPrefs) {
        mutableStateListOf<SavedPage>().apply { addAll(readSavedPages(savedPrefs.getString("history", null))) }
    }
    val bookmarks = remember(savedPrefs) {
        mutableStateListOf<SavedPage>().apply { addAll(readSavedPages(savedPrefs.getString("bookmarks", null))) }
    }
    var desktopMode by remember { mutableStateOf(settingsPrefs.getBoolean("desktopMode", false)) }
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
    LaunchedEffect(tabTitles.toList(), tabUrls.toList(), tabGroups.toList(), activeTab, incognito, privateModeUsed) {
        // Once private browsing has been used in this app session, do not overwrite
        // the last normal-tab snapshot with URLs that may have been opened privately.
        if (!incognito && !privateModeUsed) {
            tabsPrefs.edit()
                .putString("tabs", encodeBrowserTabs(tabTitles.toList(), tabUrls.toList()))
                .putString("groups", JSONArray(tabGroups.toList()).toString())
                .putInt("active", activeTab.coerceIn(0, (tabTitles.size - 1).coerceAtLeast(0)))
                .apply()
        }
    }
    LaunchedEffect(blur, animations, trackers, darkSites, adBlock, nightPages, accentChoice, desktopMode) {
        settingsPrefs.edit()
            .putBoolean("desktopMode", desktopMode)
            .putString("accent", accentChoice)
            .putFloat("blur", blur)
            .putBoolean("animations", animations)
            .putBoolean("trackers", trackers)
            .putBoolean("darkSites", darkSites)
            .putBoolean("adBlock", adBlock)
            .putBoolean("nightPages", nightPages)
            .apply()
    }
    val accent = if (incognito) Violet else when (accentChoice) {
        "blue" -> Color(0xFF55A8FF)
        "purple" -> Color(0xFFB28AFF)
        "green" -> Color(0xFF65D69A)
        else -> Orange
    }
    LaunchedEffect(trackers, sessions.size) {
        sessions.forEach { it.settings.setUseTrackingProtection(trackers) }
        splitSession.settings.setUseTrackingProtection(trackers)
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
        if (text.startsWith("https://") || text.startsWith("http://") || (text.contains(".") && !text.contains(" "))) {
            showSearchResults = false
            loaded = true
            requestSafeNavigation(text)
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
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                onDownload(response)
            }
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tabTitles[activeTab] = title?.takeIf { it.isNotBlank() } ?: "Без названия"
            }
        })
        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) { canGoBack = value }
            override fun onCanGoForward(session: GeckoSession, value: Boolean) { canGoForward = value }
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                loaded = !url.isNullOrBlank() && url != "about:blank"
                if (!url.isNullOrBlank()) {
                    query = url
                    tabUrls[activeTab] = url
                    if (url == "about:blank") tabTitles[activeTab] = "Новая вкладка"
                    if (!incognito && (url.startsWith("https://") || url.startsWith("http://"))) {
                        val pageTitle = tabTitles.getOrNull(activeTab)?.takeIf { it.isNotBlank() && it != "Новая вкладка" } ?: url
                        val existing = history.indexOfFirst { it.url == url }
                        if (existing >= 0) history.removeAt(existing)
                        history.add(0, SavedPage(pageTitle, url))
                        while (history.size > 500) history.removeAt(history.lastIndex)
                    }
                }
            }
        })
        onDispose {
            session.setNavigationDelegate(null)
            session.setContentDelegate(null)
        }
    }
    BackHandler(enabled = panel.isNotEmpty() || islandOpen || incognito) {
        when {
            panel.isNotEmpty() -> panel = ""
            islandOpen -> islandOpen = false
            incognito -> incognito = false
        }
    }
    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) { kotlinx.coroutines.delay(2100); toast = "" }
    }

    pendingRiskReport?.let { report ->
        AlertDialog(
            onDismissRequest = { pendingRiskUrl = null; pendingRiskReport = null; loaded = false },
            containerColor = Color(0xFF14243A),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFFFC46B), modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Подозрительная ссылка", color = Paper, fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column {
                    Text("Darknes Defense обнаружил признаки риска для адреса:", color = Muted, fontSize = 12.sp)
                    Text(report.host, color = Paper, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
                    report.warnings.take(4).forEach { warning ->
                        Text("• $warning", color = Color(0xFFFFC46B), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    Text("Это не подтверждение вредоносности, но лучше проверить адрес перед переходом.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 9.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pendingRiskUrl
                    pendingRiskUrl = null
                    pendingRiskReport = null
                    if (!target.isNullOrBlank()) onLoad(target)
                }) { Text("Всё равно открыть", color = Color(0xFFFFC46B)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRiskUrl = null; pendingRiskReport = null; loaded = false }) {
                    Text("Отмена", color = Paper)
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        // Futuristic expandable address and search capsule
        val secureAddress = query.trim().startsWith("https://", ignoreCase = true)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
            Surface(
                color = Glass,
                shape = RoundedCornerShape(if (islandOpen) 25.dp else 30.dp),
                border = BorderStroke(1.dp, if (islandOpen && secureAddress) Color(0x8865DDA5) else Border),
                shadowElevation = if (islandOpen) 20.dp else 14.dp,
                modifier = Modifier
                    .widthIn(min = if (islandOpen) 280.dp else 178.dp, max = if (islandOpen) 520.dp else 178.dp)
                    .fillMaxWidth(if (islandOpen) 0.94f else 0.48f)
                    .height(if (islandOpen) 58.dp else 38.dp)
                    .clickable { if (!islandOpen) islandOpen = true }
                    .animateContentSize(if (animations) spring(dampingRatio = 0.72f, stiffness = 380f) else snap())
            ) {
                if (!islandOpen) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(accent, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (incognito) "Инкогнито · Darknes Tor" else "Darknes Tor",
                            color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium,
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
                                } else {
                                    islandOpen = true
                                }
                            }
                        )
                    }
                } else {
                    Row(Modifier.fillMaxSize().padding(start = 13.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(
                            targetState = secureAddress,
                            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                            label = "address-security"
                        ) { secure ->
                            Icon(
                                if (incognito) Icons.Default.VisibilityOff else if (secure) Icons.Default.Lock else Icons.Default.Search,
                                contentDescription = if (incognito) "Инкогнито" else if (secure) "HTTPS адрес" else "Поиск или адрес",
                                tint = if (secure && !incognito) Color(0xFF65DDA5) else accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(9.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = TextStyle(color = Paper, fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                if (query.isNotBlank()) runSearch(query)
                                keyboard?.hide()
                                islandOpen = false
                            }),
                            decorationBox = { inner ->
                                Box {
                                    if (query.isEmpty()) Text("Поиск или адрес", color = Muted, fontSize = 13.sp)
                                    inner()
                                }
                            }
                        )
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить адрес", tint = Muted, modifier = Modifier.size(16.dp))
                            }
                        }
                        Box(
                            Modifier.size(39.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(accent, Violet)))
                                .clickable {
                                    if (query.isNotBlank()) runSearch(query)
                                    keyboard?.hide()
                                    islandOpen = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Перейти", tint = Ink, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (splitScreen) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    key(session) {
                        AndroidView(
                            factory = { ctx -> GeckoView(ctx).apply { setSession(session) } },
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
                    AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(session) } }, modifier = Modifier.fillMaxSize())
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
                        requestSafeNavigation(url)
                    }
                )
            } else if (!loaded) HomeScreen(
                accent = accent,
                blur = blur,
                animations = animations,
                searchText = query,
                onSearchTextChange = { query = it },
                engine = engine,
                onEngineChange = { engine = it },
                bookmarks = bookmarks.toList(),
                onSecretSettings = { panel = "Настройки" },
                onSearch = {
                    runSearch(query)
                    keyboard?.hide()
                },
                onShortcut = { link ->
                    query = link
                    requestSafeNavigation(link)
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
                        Modifier.fillMaxWidth().fillMaxHeight(if (panel == "Вкладки" || panel == "Меню" || panel == "Безопасность" || panel == "Чтение") .92f else .78f)
                            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                            .background(Glass).border(1.dp, Border, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                            .clickable(enabled = false) {}.padding(20.dp)
                    ) {
                        AnimatedContent(
                            targetState = panel,
                            transitionSpec = {
                                if (targetState.isNotEmpty() && initialState.isNotEmpty()) {
                                    (fadeIn(tween(190)) + slideInHorizontally(tween(220)) { it / 9 } + scaleIn(initialScale = .985f)) togetherWith
                                        (fadeOut(tween(110)) + slideOutHorizontally(tween(150)) { -it / 12 })
                                } else {
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(100))
                                }
                            },
                            label = "darknes-panel-transition"
                        ) { targetPanel ->
                            Column(Modifier.fillMaxWidth().animateContentSize()) {
                                when (targetPanel) {
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
                            "Настройки" -> SettingsPanel(engine, { engine = it }, trackers, { trackers = it }, darkSites, { darkSites = it }, animations, { animations = it }, blur, { blur = it }, accentChoice, { accentChoice = it }, desktopMode, { desktopMode = it })
                            "Безопасность" -> PrivacyPanel(trackers, { trackers = it }, adBlock, { adBlock = it }, darkSites, { darkSites = it }, nightPages, { nightPages = it }) {
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
                            "Тестер", "Обновления" -> TesterPanel { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure { toast = "Не удалось открыть загрузку APK" }
                            }
                            "Уведомления" -> NotificationsPanel {
                                runCatching {
                                    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    context.startActivity(intent)
                                }.onFailure { toast = "Не удалось открыть настройки уведомлений" }
                            }
                            "Расширения" -> ExtensionsPanel(adBlock, { adBlock = it }, nightPages, { nightPages = it }) { toast = "Установка расширений появится позже" }
                            "Аккаунт" -> DarknesIdPanel()
                            "ИИ-чат" -> ChatPanel {
                                panel = ""
                                query = "https://duck.ai"
                                session.loadUri("https://duck.ai")
                                loaded = true
                                showSearchResults = false
                            }
                            "Загрузки" -> DownloadsPanel(downloads, onRemoveDownload) { item ->
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
                            "Вкладки" -> TabsPanel(tabTitles.toList(), tabUrls.toList(), tabGroups.toList(), activeTab, incognito, { value -> if (value) privateModeUsed = true; incognito = value }, { addTab(); loaded = false }, { index -> closeTab(index); loaded = false }, { index -> activeTab = index; loaded = tabUrls.getOrNull(index).orEmpty().isNotBlank(); query = tabUrls.getOrNull(index).orEmpty(); panel = "" }, { index -> tabGroups[index] = when (tabGroups[index]) { "Основная" -> "Работа"; "Работа" -> "Личное"; else -> "Основная" } }, { panel = "" })
                                }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(toast.isNotEmpty(), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)) {
                Surface(color = Glass, shape = CircleShape, border = BorderStroke(1.dp, Border), shadowElevation = 14.dp) {
                    Text(toast, color = Paper, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), fontSize = 13.sp)
                }
            }
            }
        }

        // Floating glass navigation dock
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
                .height(64.dp).clip(RoundedCornerShape(25.dp)).background(Glass)
                .border(1.dp, Border, RoundedCornerShape(25.dp)).padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            NavCircle(Icons.Default.ArrowBack, accent, enabled = canGoBack) { if (canGoBack) session.goBack() }
            NavCircle(Icons.Default.ArrowForward, accent, enabled = canGoForward) { if (canGoForward) session.goForward() }
            NavCircle(Icons.Default.Refresh, accent, enabled = tabUrls.getOrNull(activeTab).orEmpty().isNotBlank()) {
                if (tabUrls.getOrNull(activeTab).orEmpty().isNotBlank()) session.reload()
            }
            NavCircle(Icons.Default.Home, accent) {
                query = ""
                loaded = false
                showSearchResults = false
                panel = ""
            }
            NavCircle(Icons.Default.AutoAwesome, accent) { panel = "ИИ-чат" }
            Surface(
                modifier = Modifier.size(39.dp).clickable { panel = "Вкладки" },
                shape = RoundedCornerShape(13.dp),
                color = Color(0xFF1A3554),
                border = BorderStroke(1.dp, if (panel == "Вкладки") accent else Border)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${sessions.size}", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            NavCircle(Icons.Default.MoreHoriz, accent) { panel = "Меню" }
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
        Surface(color = Color(0xFF0D192B), shape = RoundedCornerShape(18.dp),
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
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D192B))
                        .border(1.dp, Border, RoundedCornerShape(18.dp)).clickable { onOpenResult(result.url) }.padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(25.dp).clip(CircleShape).background(Color(0xFF1A3554)), contentAlignment = Alignment.Center) {
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
private fun HomeScreen(
    accent: Color,
    blur: Float,
    animations: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    engine: String,
    onEngineChange: (String) -> Unit,
    bookmarks: List<SavedPage>,
    onSecretSettings: () -> Unit,
    onSearch: () -> Unit,
    onShortcut: (String) -> Unit
) {
    var titleTapCount by remember { mutableIntStateOf(0) }
    var lastTitleTapAt by remember { mutableLongStateOf(0L) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }

    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(currentTime))
    val date = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale("ru")).format(java.util.Date(currentTime))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale("ru")) else it.toString() }

    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))

        // Personal dashboard clock card
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            color = Color(0xCC101D31),
            shape = RoundedCornerShape(23.dp),
            border = BorderStroke(1.dp, Border.copy(alpha = .9f)),
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(date.uppercase(java.util.Locale("ru")), color = Muted, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Text(clock, style = TextStyle(
                        brush = Brush.linearGradient(listOf(Paper, accent, Violet)),
                        fontSize = 34.sp, fontWeight = FontWeight.Bold
                    ))
                    Text("ТВОЁ ПРОСТРАНСТВО В СЕТИ", color = Muted, fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
                }
                Box(
                    Modifier.size(62.dp).clip(RoundedCornerShape(19.dp))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = .22f), Violet.copy(alpha = .2f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = accent, modifier = Modifier.size(29.dp))
                }
            }
        }

        Spacer(Modifier.height(9.dp))
        BlackHoleScene(
            iconBurst = 0f,
            modifier = Modifier.width(270.dp).height(190.dp).blur(blur.dp),
            accent = accent,
            showOrbitIcons = false,
            showParticles = false
        )
        Spacer(Modifier.height(2.dp))
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
            style = TextStyle(brush = Brush.linearGradient(listOf(Paper, Peach)), fontSize = 39.sp, fontWeight = FontWeight.Bold)
        )
        Text("Поиск без входа и слежки", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Surface(
            color = Color(0x33204468),
            shape = RoundedCornerShape(50.dp),
            border = BorderStroke(1.dp, Border.copy(alpha = .9f))
        ) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Orange, Violet))))
                Spacer(Modifier.width(8.dp))
                Text("DARKNES TOR  ·  1.5 BETA", color = Color(0xFFBFDFFF),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.15.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        HomeSearchBar(
            value = searchText,
            onValueChange = onSearchTextChange,
            engine = engine,
            onEngineChange = onEngineChange,
            accent = accent,
            onSearch = onSearch
        )

        Spacer(Modifier.height(24.dp))
        val sites = listOf(
            Triple(Icons.Default.PlayCircle, "YouTube", "https://youtube.com"),
            Triple(Icons.Default.Search, "DuckDuckGo", "https://duckduckgo.com"),
            Triple(Icons.Default.Code, "GitHub", "https://github.com"),
            Triple(Icons.Default.MenuBook, "Wikipedia", "https://wikipedia.org"),
            Triple(Icons.Default.Send, "Telegram", "https://telegram.org"),
            Triple(Icons.Default.Forum, "Reddit", "https://reddit.com"),
            Triple(Icons.Default.LiveTv, "Twitch", "https://twitch.tv")
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("БЫСТРЫЙ ДОСТУП", color = Muted, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.weight(1f))
            Text("7 САЙТОВ", color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            sites.forEach { (icon, name, url) ->
                Surface(
                    modifier = Modifier.width(126.dp).height(82.dp).clickable { onShortcut(url) },
                    color = Color(0xCC111F35),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Border),
                    shadowElevation = 8.dp
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(39.dp).clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(Color(0x553DDFFF), Color(0x553F6DFF)))),
                            contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = name, tint = Orange, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                        Text(name, color = Paper, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        if (bookmarks.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ТВОИ ЗАКЛАДКИ", color = Muted, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                Text("${bookmarks.size} СОХРАНЕНО", color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                bookmarks.take(8).forEach { page ->
                    Surface(
                        modifier = Modifier.width(190.dp).height(68.dp).clickable { onShortcut(page.url) },
                        color = Color(0xCC111F35),
                        shape = RoundedCornerShape(19.dp),
                        border = BorderStroke(1.dp, Border)
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(37.dp).clip(RoundedCornerShape(13.dp))
                                .background(Color(0x333DDFFF)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Bookmark, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(page.title, color = Paper, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(runCatching { java.net.URI(page.url).host.orEmpty().removePrefix("www.") }.getOrDefault(page.url),
                                    color = Muted, fontSize = 9.sp, maxLines = 1)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                color = Color(0x88101D31),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Border.copy(alpha = .7f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Здесь будут твои закладки", color = Paper, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("Сохраняй интересные страницы, чтобы вернуться к ним позже.", color = Muted, fontSize = 9.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}
@Composable
private fun HomeSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    engine: String,
    onEngineChange: (String) -> Unit,
    accent: Color,
    onSearch: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF0D192B))
            .border(1.dp, accent.copy(alpha = .42f), RoundedCornerShape(24.dp))
            .padding(horizontal = 15.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = "Поиск", tint = accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = Paper, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSearch()
                    keyboard?.hide()
                }),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text("Найти в интернете", color = Muted, fontSize = 15.sp)
                        inner()
                    }
                }
            )
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить", tint = Muted)
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(accent).clickable {
                    onSearch()
                    keyboard?.hide()
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowForward, contentDescription = "Искать", tint = Ink)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF14243D))
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Поиск через", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text("DuckDuckGo", color = Peach, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable private fun NavCircle(icon: ImageVector, accent: Color, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(enabled = enabled) { action() }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (enabled) Paper else Muted.copy(alpha = .35f), modifier = Modifier.size(22.dp))
    }
}
@Composable private fun PanelTitle(title: String, subtitle: String = "") {
    Text(title, color = Paper, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    if (subtitle.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) }
    Spacer(Modifier.height(20.dp))
}
@Composable
private fun MenuPanel(accent: Color, onAction: (String) -> Unit) {
    val sections = listOf(
        "ВКЛАДКИ И НАВИГАЦИЯ" to listOf(
            Icons.Default.Add to "Новая вкладка",
            Icons.Default.VisibilityOff to "Инкогнито",
            Icons.Default.ViewColumn to "Разделить экран",
            Icons.Default.Translate to "Перевести страницу",
            Icons.Default.MenuBook to "Режим чтения"
        ),
        "БИБЛИОТЕКА" to listOf(
            Icons.Default.Bookmark to "Добавить закладку",
            Icons.Default.Bookmarks to "Закладки",
            Icons.Default.History to "История",
            Icons.Default.Download to "Загрузки",
            Icons.Default.Download to "Сохранить офлайн",
            Icons.Default.Bookmarks to "Офлайн-страницы"
        ),
        "ИНСТРУМЕНТЫ" to listOf(
            Icons.Default.Extension to "Расширения",
            Icons.Default.AutoAwesome to "ИИ-чат"
        ),
        "DARKNES" to listOf(
            Icons.Default.Settings to "Настройки",
            Icons.Default.NotificationsActive to "Уведомления",
            Icons.Default.SystemUpdateAlt to "Обновления",
            Icons.Default.Person to "Аккаунт",
            Icons.Default.Shield to "Безопасность"
        )
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 590.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                PanelTitle("Центр управления", "Инструменты и разделы Darknes Tor")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xCC142640),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(accent.copy(alpha = .32f), Violet.copy(alpha = .22f)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = accent, modifier = Modifier.size(25.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("DARKNES TOR", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
                            Text("Быстрый доступ ко всем функциям", color = Muted, fontSize = 10.sp)
                        }
                        Text("1.5 BETA", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    }
                }
            }
        }
        sections.forEach { (sectionTitle, entries) ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(3.dp).height(13.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(9.dp))
                    Text(sectionTitle, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.35.sp)
                }
            }
            items(entries) { (icon, name) ->
                Surface(
                    modifier = Modifier.fillMaxWidth().height(62.dp).clickable { onAction(name) },
                    color = Color(0xCC101D31),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Border.copy(alpha = .8f))
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(39.dp).clip(RoundedCornerShape(13.dp))
                                .background(Brush.linearGradient(listOf(accent.copy(alpha = .20f), Violet.copy(alpha = .13f)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = name, tint = accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Text(name, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}
@Composable
private fun SavedPagesPanel(
    title: String,
    subtitle: String,
    pages: List<SavedPage>,
    onOpen: (String) -> Unit,
    onRemove: (SavedPage) -> Unit,
    onClear: (() -> Unit)? = null
) {
    var filter by remember(title) { mutableStateOf("") }
    val isHistory = title == "История"
    val visiblePages = pages.filter { it.title.contains(filter, true) || it.url.contains(filter, true) }

    PanelTitle(title, subtitle)

    if (isHistory) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xCC142640),
            shape = RoundedCornerShape(21.dp),
            border = BorderStroke(1.dp, Border)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(Orange.copy(alpha = .25f), Violet.copy(alpha = .18f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = Orange, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Журнал посещений", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Сайтов сохранено: ${pages.size}", color = Muted, fontSize = 10.sp)
                }
                Text("LOCAL", color = Orange, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
            .background(Color(0xFF0D192B))
            .border(1.dp, Border, RoundedCornerShape(17.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(color = Paper, fontSize = 14.sp),
            decorationBox = { inner ->
                Box {
                    if (filter.isEmpty()) Text(
                        if (isHistory) "Поиск по истории" else "Найти закладку",
                        color = Muted, fontSize = 13.sp
                    )
                    inner()
                }
            }
        )
        if (filter.isNotEmpty()) {
            Icon(
                Icons.Default.Close, contentDescription = "Очистить поиск",
                tint = Muted, modifier = Modifier.size(18.dp).clickable { filter = "" }
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    if (visiblePages.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp))
                .background(Color(0xCC101D31))
                .border(1.dp, Border, RoundedCornerShape(21.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Orange.copy(alpha = .20f), Violet.copy(alpha = .16f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isHistory) Icons.Default.History else Icons.Default.BookmarkBorder,
                    contentDescription = null, tint = Orange, modifier = Modifier.size(29.dp)
                )
            }
            Spacer(Modifier.height(11.dp))
            Text(
                if (pages.isEmpty()) if (isHistory) "История пока пуста" else "Закладок пока нет" else "Ничего не найдено",
                color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
            Text(
                if (isHistory) "Посещённые сайты появятся здесь." else "Сохраняй нужные страницы через меню браузера.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(visiblePages, key = { it.url }) { page ->
                val host = runCatching { java.net.URI(page.url).host.orEmpty().removePrefix("www.") }
                    .getOrDefault("")
                val visitedAt = runCatching {
                    java.text.SimpleDateFormat("dd.MM.yyyy  HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(page.time))
                }.getOrDefault("")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xCC101D31),
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp, Border.copy(alpha = .85f))
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(page.url) }
                            .padding(start = 12.dp, top = 11.dp, bottom = 11.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(Orange.copy(alpha = .20f), Violet.copy(alpha = .14f)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isHistory) Icons.Default.Language else Icons.Default.Bookmark,
                                contentDescription = null, tint = Orange, modifier = Modifier.size(21.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(page.title, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(host.ifBlank { page.url }, color = Muted, fontSize = 10.sp, maxLines = 1)
                            Text(if (isHistory) visitedAt else page.url, color = Orange.copy(alpha = .85f), fontSize = 9.sp, maxLines = 1)
                        }
                        IconButton(onClick = { onRemove(page) }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Удалить", tint = Muted, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }

    if (onClear != null && pages.isNotEmpty()) {
        Spacer(Modifier.height(9.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onClear() },
            color = Color(0x331B2C46),
            shape = RoundedCornerShape(15.dp),
            border = BorderStroke(1.dp, Border.copy(alpha = .65f))
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Peach, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text("Очистить историю", color = Peach, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Данные хранятся на этом устройстве. Режим инкогнито не добавляет страницы в историю.",
        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
    )
}
@Composable private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Paper, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Orange))
    }
}
@Composable
private fun DownloadsPanel(
    downloads: List<DownloadRecord>,
    onRemove: (Long) -> Unit,
    onOpen: (DownloadRecord) -> Unit
) {
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
        return String.format(java.util.Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }

    val activeCount = downloads.count { it.status == "Скачивается" || it.status == "Ожидает сохранения" }
    val savedCount = downloads.count { it.status == "Сохранено" }

    PanelTitle("Загрузки", "Менеджер загруженных файлов")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xCC142640),
        shape = RoundedCornerShape(21.dp),
        border = BorderStroke(1.dp, Border)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(43.dp).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(Orange.copy(alpha = .25f), Violet.copy(alpha = .18f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Downloading, contentDescription = null, tint = Orange, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Файловый центр", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("${downloads.size} записей · $savedCount сохранено", color = Muted, fontSize = 10.sp)
            }
            if (activeCount > 0) {
                Text("$activeCount АКТИВНО", color = Orange, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
            } else {
                Text("LOCAL", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (downloads.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(21.dp))
                .background(Color(0xCC101D31))
                .border(1.dp, Border, RoundedCornerShape(21.dp))
                .padding(25.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(19.dp))
                    .background(Brush.linearGradient(listOf(Orange.copy(alpha = .20f), Violet.copy(alpha = .15f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = Orange, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(11.dp))
            Text("Пока ничего не скачано", color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Сохранённые файлы появятся здесь.", color = Muted, fontSize = 11.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 490.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(downloads, key = { it.id }) { item ->
                val isActive = item.status == "Скачивается"
                val isSaved = item.status == "Сохранено"
                val isError = item.status == "Ошибка" || item.status == "Прервано" || item.status == "Отменено"
                val fraction = if (item.totalBytes > 0L) {
                    (item.downloadedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val statusColor = when {
                    isSaved -> Color(0xFF65DDA5)
                    isError -> Color(0xFFFF9B9B)
                    isActive || item.status == "Ожидает сохранения" -> Orange
                    else -> Muted
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xCC101D31),
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp, if (isActive) Orange.copy(alpha = .65f) else Border.copy(alpha = .85f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(43.dp).clip(RoundedCornerShape(14.dp))
                                    .background(Brush.linearGradient(listOf(statusColor.copy(alpha = .22f), Violet.copy(alpha = .12f)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    when {
                                        isSaved -> Icons.Default.TaskAlt
                                        isError -> Icons.Default.ErrorOutline
                                        isActive -> Icons.Default.Downloading
                                        else -> Icons.Default.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                                Text(item.status.uppercase(java.util.Locale.getDefault()), color = statusColor, fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = .7.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            if (item.savedUri != null) {
                                TextButton(onClick = { onOpen(item) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Orange, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Открыть", color = Orange, fontSize = 11.sp)
                                }
                            }
                            IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить запись", tint = Muted, modifier = Modifier.size(18.dp))
                            }
                        }

                        if (isActive) {
                            Spacer(Modifier.height(11.dp))
                            if (item.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                    color = Orange,
                                    trackColor = Color(0xFF263852)
                                )
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${(fraction * 100).toInt()}%", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text("${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}", color = Muted, fontSize = 10.sp)
                                }
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                    color = Orange,
                                    trackColor = Color(0xFF263852)
                                )
                                Text("${formatBytes(item.downloadedBytes)} получено", color = Muted, fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        } else if (item.downloadedBytes > 0L || item.totalBytes > 0L) {
                            Text(
                                if (item.totalBytes > 0L) "${formatBytes(item.downloadedBytes)} из ${formatBytes(item.totalBytes)}"
                                else formatBytes(item.downloadedBytes),
                                color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 55.dp, top = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "История загрузок хранится на устройстве. После перезапуска незавершённые загрузки отмечаются как прерванные.",
        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
    )
}
@Composable
private fun NotificationsPanel(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val notificationsEnabled = remember(context) {
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        }.getOrDefault(true)
    }
    PanelTitle("Центр уведомлений", "Управление оповещениями Darknes Tor")
    Surface(
        Modifier.fillMaxWidth(),
        color = Color(0xCC10213A),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(Color(0x5542DFFF), Color(0x559A83FF)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Orange, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("СИСТЕМНЫЕ УВЕДОМЛЕНИЯ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(if (notificationsEnabled) "Разрешены" else "Отключены", color = if (notificationsEnabled) Color(0xFF65D69A) else Peach,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Icon(
                    if (notificationsEnabled) Icons.Default.CheckCircle else Icons.Default.NotificationsOff,
                    contentDescription = null,
                    tint = if (notificationsEnabled) Color(0xFF65D69A) else Peach,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                "Разрешение управляется Android. Здесь можно открыть системную страницу уведомлений приложения и изменить его.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(47.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Настроить в Android", fontWeight = FontWeight.Bold)
            }
        }
    }
    Surface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        color = Color(0x99101B2D),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, contentDescription = null, tint = Violet)
                Spacer(Modifier.width(8.dp))
                Text("Уведомления сайтов", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Запросы сайтов на уведомления обрабатываются отдельно через разрешения сайтов в браузере. Системное разрешение приложения не заменяет их.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

@Composable
private fun TesterPanel(onDownload: (String) -> Unit) {
    var versions by remember { mutableStateOf<List<PublishedVersion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = ""
        try {
            versions = fetchPublishedVersions()
        } catch (problem: Exception) {
            error = "Не удалось загрузить список релизов. Проверь подключение к интернету."
        } finally {
            loading = false
        }
    }

    val installedVersion = BuildConfig.VERSION_NAME
    val latestWithApk = versions.firstOrNull { it.apkUrl != null }

    PanelTitle("Центр обновлений", "Проверка опубликованных версий Darknes Tor")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF13243C),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Violet.copy(alpha = .65f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("УСТАНОВЛЕННАЯ ВЕРСИЯ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Text(installedVersion, color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Black)
                }
                Surface(shape = CircleShape, color = Orange.copy(alpha = .13f), border = BorderStroke(1.dp, Border)) {
                    Text("BETA", color = Orange, fontSize = 9.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Проверяем только опубликованные GitHub Releases. Ветка разработки 1.5 Beta не считается готовым обновлением, пока APK не опубликован.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { refreshKey++ },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                border = BorderStroke(1.dp, Border)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "Проверяем…" else "Проверить обновления")
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("ОПУБЛИКОВАННЫЕ ВЕРСИИ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(8.dp))
    when {
        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Violet, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Получаем данные GitHub Releases…", color = Muted, fontSize = 13.sp)
        }
        error.isNotBlank() -> Text(error, color = Peach, fontSize = 13.sp)
        versions.isEmpty() -> Text(
            "Опубликованных релизов пока нет. Когда APK появится в GitHub Releases, он будет показан здесь.",
            color = Muted, fontSize = 13.sp, lineHeight = 19.sp
        )
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp)) {
            items(versions) { version ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF111E32),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (version == latestWithApk) Violet.copy(alpha = .8f) else Border)
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(version.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (version == latestWithApk) Text("APK ДОСТУПЕН", color = Color(0xFF65D69A), fontSize = 8.sp, fontWeight = FontWeight.Black)
                        }
                        Text("Тег: ${version.tag.ifBlank { "без тега" }}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                        if (version.notes.isNotBlank()) Text(version.notes, color = Muted, fontSize = 11.sp, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
                        if (version.apkUrl != null) {
                            val sizeLabel = version.apkSizeBytes?.let { String.format(java.util.Locale.US, "%.1f MB", it / 1048576.0) } ?: "размер не указан"
                            Text("Файл APK · $sizeLabel", color = Peach, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
                            if (version.apkDigest != null) {
                                Text("SHA-256 опубликован GitHub", color = Color(0xFF65D69A), fontSize = 10.sp)
                            } else {
                                Text("Контрольная сумма не опубликована", color = Muted, fontSize = 10.sp)
                            }
                            Text("Скачать APK", color = Violet, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onDownload(version.apkUrl) }.padding(vertical = 8.dp))
                        } else {
                            Text("APK для этой версии не приложен", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Загрузка передаётся Android/браузеру. Установку всегда подтверждаешь в Android вручную. Для обновления поверх установленной версии нужны тот же applicationId и совместимая подпись разработчика. Наличие SHA-256 в GitHub отображается отдельно; приложение пока не проверяет загруженный файл автоматически.",
        color = Muted, fontSize = 10.sp, lineHeight = 15.sp
    )
}

@Composable private fun SettingsPanel(engine: String, setEngine: (String)->Unit, trackers:Boolean, setTrackers:(Boolean)->Unit, dark:Boolean, setDark:(Boolean)->Unit, anim:Boolean, setAnim:(Boolean)->Unit, blur:Float, setBlur:(Float)->Unit, accentChoice:String, setAccentChoice:(String)->Unit, desktopMode:Boolean, setDesktopMode:(Boolean)->Unit) {
    PanelTitle("Настройки", "Настрой Darknes Tor под себя")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xCC111F35),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(Color(0x5542DFFF), Color(0x559A83FF)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Orange, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ПОИСКОВАЯ СИСТЕМА", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("DuckDuckGo", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Orange, modifier = Modifier.size(19.dp))
            }
            Text(
                "Единственная поисковая система браузера. Поиск открывается без отдельного аккаунта.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xCC111F35),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp)) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                Text("ПРИВАТНОСТЬ И ОТОБРАЖЕНИЕ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.05.sp)
            }
            SettingSwitch("Блокировать трекеры", trackers, setTrackers)
            HorizontalDivider(color = Border.copy(alpha = .65f))
            SettingSwitch("Тёмный режим сайтов", dark, setDark)
            HorizontalDivider(color = Border.copy(alpha = .65f))
            SettingSwitch("Анимации интерфейса", anim, setAnim)
            HorizontalDivider(color = Border.copy(alpha = .65f))
            SettingSwitch("Версия сайтов для компьютера", desktopMode, setDesktopMode)
            Text(
                "Режим компьютера меняет User-Agent и viewport GeckoView для всех вкладок. Некоторые сайты могут отображаться иначе.",
                color = Muted, fontSize = 10.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 9.dp)
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xCC111F35),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BlurOn, contentDescription = null, tint = Orange, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text("ВИЗУАЛЬНЫЕ ЭФФЕКТЫ", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            }
            Text("Размытие фона", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
            Text("${blur.toInt()} px", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = blur,
                onValueChange = setBlur,
                valueRange = 0f..24f,
                colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange)
            )
            Text("Цвет акцента", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp, bottom = 9.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf("orange" to "Оранжевый", "blue" to "Синий", "purple" to "Фиолетовый", "green" to "Зелёный").forEach { (key, label) ->
                    FilterChip(
                        selected = accentChoice == key,
                        onClick = { setAccentChoice(key) },
                        label = { Text(label, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Orange.copy(alpha = .22f),
                            selectedLabelColor = Peach,
                            labelColor = Muted
                        )
                    )
                }
            }
        }
    }
    Text(
        "Дополнительные параметры защиты находятся в Центре безопасности.",
        color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable private fun PrivacyPanel(trackers:Boolean, setTrackers:(Boolean)->Unit, ad:Boolean, setAd:(Boolean)->Unit, dark:Boolean, setDark:(Boolean)->Unit, night:Boolean, setNight:(Boolean)->Unit, openAndroidSettings:()->Unit) {
    var linkInput by remember { mutableStateOf("") }
    var linkReport by remember { mutableStateOf<LinkRiskReport?>(null) }
    val protectionAccent = if (trackers && ad) Color(0xFF65D69A) else Color(0xFFFFC46B)
    val protectionLabel = if (trackers && ad) "ЗАЩИТА ВКЛЮЧЕНА" else "ТРЕБУЕТ ВНИМАНИЯ"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        PanelTitle("Центр безопасности", "Управление приватностью и защитой браузера")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF10243A),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, protectionAccent.copy(alpha = .48f)),
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.fillMaxWidth().background(
                    Brush.horizontalGradient(listOf(protectionAccent.copy(alpha = .13f), Color.Transparent))
                ).padding(17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(58.dp).clip(RoundedCornerShape(19.dp))
                        .background(protectionAccent.copy(alpha = .13f))
                        .border(1.dp, protectionAccent.copy(alpha = .4f), RoundedCornerShape(19.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = protectionAccent, modifier = Modifier.size(31.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("DARKNES DEFENSE", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text(protectionLabel, color = protectionAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp,
                        modifier = Modifier.padding(top = 4.dp))
                    Text("Состояние по текущим настройкам браузера", color = Muted, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = protectionAccent, modifier = Modifier.size(23.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            SecurityStatusCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.TrackChanges,
                title = "Трекеры",
                value = if (trackers) "Блокировка" else "Отключено",
                enabled = trackers
            )
            SecurityStatusCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Cookie,
                title = "ETP",
                value = if (ad) "Строгий режим" else "Стандартный",
                enabled = ad
            )
        }

        SecuritySectionTitle("ЗАЩИТА ОТСЛЕЖИВАНИЯ", Icons.Default.Security)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xCC101D31),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Border)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
                SettingSwitch("Блокировать известные трекеры", trackers, setTrackers)
                HorizontalDivider(color = Border.copy(alpha = .55f))
                SettingSwitch("Усиленная защита GeckoView", ad, setAd)
            }
        }
        Text(
            "Усиленная защита включает строгий уровень Enhanced Tracking Protection GeckoView. Это не гарантирует блокировку всей рекламы или всех способов отслеживания.",
            color = Muted, fontSize = 10.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 3.dp)
        )

        SecuritySectionTitle("ВНЕШНИЙ ВИД САЙТОВ", Icons.Default.DarkMode)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xCC101D31),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Border)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
                SettingSwitch("Тёмная схема сайтов", dark, setDark)
                HorizontalDivider(color = Border.copy(alpha = .55f))
                SettingSwitch("Ночной режим страниц", night, setNight)
            }
        }

        SecuritySectionTitle("ПРОВЕРКА ССЫЛОК", Icons.Default.Link)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xCC101D31),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Border)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Локальный анализ адреса", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Проверь подозрительную ссылку до открытия. Адрес анализируется на устройстве и не отправляется на сторонний сервис.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
                OutlinedTextField(
                    value = linkInput,
                    onValueChange = { linkInput = it; linkReport = null },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true,
                    placeholder = { Text("https://example.com", color = Muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Paper, unfocusedTextColor = Paper,
                        focusedBorderColor = Orange, unfocusedBorderColor = Border,
                        cursorColor = Orange, focusedPlaceholderColor = Muted, unfocusedPlaceholderColor = Muted
                    )
                )
                Button(
                    onClick = { linkReport = analyzeLinkRisk(linkInput) },
                    enabled = linkInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
                ) {
                    Icon(Icons.Default.TravelExplore, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Проверить адрес", fontWeight = FontWeight.Bold)
                }
                linkReport?.let { report ->
                    val tint = if (report.isSuspicious) Color(0xFFFFC46B) else Color(0xFF65D69A)
                    HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 10.dp))
                    Text(if (report.isSuspicious) "ЕСТЬ ПРИЗНАКИ РИСКА" else "ЯВНЫХ ПРИЗНАКОВ РИСКА НЕ НАЙДЕНО",
                        color = tint, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    Text("Домен: ${report.host}", color = Paper, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    if (report.warnings.isEmpty()) {
                        Text("Базовые проверки не выявили подозрительных признаков.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        report.warnings.forEach { warning ->
                            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(warning, color = Paper, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
                Text("Важно: это эвристическая проверка, а не база известных фишинговых сайтов. Отсутствие предупреждений не означает, что ссылка безопасна.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }

        SecuritySectionTitle("РАЗРЕШЕНИЯ ANDROID", Icons.Default.PhonelinkLock)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xCC101D31),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Border)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Камера, микрофон, геолокация и уведомления регулируются разрешениями Android и запросами сайтов. Здесь можно открыть системные настройки приложения.",
                    color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = openAndroidSettings,
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть разрешения Android", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SecuritySectionTitle(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 3.dp, top = 3.dp)) {
        Icon(icon, contentDescription = null, tint = Orange, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
    }
}

@Composable
private fun SecurityStatusCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    enabled: Boolean
) {
    val tint = if (enabled) Color(0xFF65D69A) else Color(0xFFFFC46B)
    Surface(
        modifier = modifier,
        color = Color(0xCC101D31),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = .3f))
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(value, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 9.dp))
            Text(if (enabled) "АКТИВНО" else "НЕ АКТИВНО", color = tint, fontSize = 8.sp,
                fontWeight = FontWeight.Black, letterSpacing = .8.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
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
                    color = Color(0xFF111E32),
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
@Composable
private fun DarknesIdPanel(onAuthenticated: (() -> Unit)? = null) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("darknes_id_profile", android.content.Context.MODE_PRIVATE)
    }
    var displayName by remember(prefs) {
        mutableStateOf(prefs.getString("display_name", "Пользователь") ?: "Пользователь")
    }
    var editing by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf(false) }
    val avatarLetter = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "D"

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DARKNES ID", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                Text("Единый профиль экосистемы Darknes", color = Muted, fontSize = 11.sp)
            }
            Surface(shape = CircleShape, color = Orange.copy(alpha = .13f),
                border = BorderStroke(1.dp, Border)) {
                Text("ID", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
            }
        }

        Surface(
            Modifier.fillMaxWidth(),
            color = Color(0xCC10213A),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Border)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(58.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF1B6A83), Color(0xFF5545A2))))
                        .border(1.dp, Orange.copy(alpha = .65f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(avatarLetter, color = Paper, fontSize = 25.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayName.ifBlank { "Пользователь" }, color = Paper,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("Локальный профиль", color = Color(0xFF65D69A),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Darknes ID · не выполнен вход на сервер", color = Muted, fontSize = 9.sp)
                }
                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
            }
        }

        Surface(Modifier.fillMaxWidth(), color = Color(0x99101B2D),
            shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PersonOutline, contentDescription = null, tint = Orange)
                    Spacer(Modifier.width(8.dp))
                    Text("Имя профиля", color = Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editing = !editing; savedMessage = false }) {
                        Text(if (editing) "Отмена" else "Изменить", color = Orange)
                    }
                }
                if (editing) {
                    BasicTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(32); savedMessage = false },
                        singleLine = true,
                        textStyle = TextStyle(color = Paper, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF081426)).border(1.dp, Border, RoundedCornerShape(12.dp))
                            .padding(13.dp)
                    )
                    Button(
                        onClick = {
                            val cleanName = displayName.trim().ifBlank { "Пользователь" }
                            displayName = cleanName
                            prefs.edit().putString("display_name", cleanName).apply()
                            editing = false
                            savedMessage = true
                        },
                        modifier = Modifier.fillMaxWidth().height(45.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Сохранить профиль", fontWeight = FontWeight.Bold) }
                } else if (savedMessage) {
                    Text("Изменения сохранены на этом устройстве.", color = Color(0xFF65D69A), fontSize = 10.sp)
                }
            }
        }

        Surface(Modifier.fillMaxWidth(), color = Color(0x99101B2D),
            shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = Violet)
                    Spacer(Modifier.width(8.dp))
                    Text("Синхронизация", color = Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text("СКОРО", color = Violet, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                Text("Облачный сервер и авторизация Darknes ID ещё не подключены. Закладки и вкладки пока хранятся локально.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 7.dp))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("ЭКОСИСТЕМА DARKNES", color = Muted, fontSize = 9.sp,
                fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            listOf(
                "Darknes Tor" to "Браузер · сейчас",
                "Darknes OS" to "Мобильная оболочка",
                "Darknes Store" to "Магазин приложений",
                "Darknes Cloud" to "Облачное хранилище · скоро"
            ).forEach { item ->
                Surface(Modifier.fillMaxWidth(), color = Color(0x77101D31),
                    shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Border.copy(alpha = .65f))) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = Orange, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.first, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(item.second, color = Muted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }

        if (onAuthenticated != null) {
            Button(
                onClick = onAuthenticated,
                modifier = Modifier.fillMaxWidth().height(49.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink),
                shape = RoundedCornerShape(15.dp)
            ) { Text("Продолжить в Darknes Tor", fontWeight = FontWeight.Bold) }
            Text("Это локальный профиль, а не регистрация или вход в облачный аккаунт.",
                color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}
@Composable private fun ChatPanel(onOpenAi:()->Unit) {
    PanelTitle("Darknes AI", "Приватный ИИ-помощник")
    Surface(Modifier.fillMaxWidth(), color = Color(0xFF111E32), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Border)) {
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
    titles: List<String>,
    urls: List<String>,
    groups: List<String>,
    activeIndex: Int,
    incognito: Boolean,
    setIncognito: (Boolean) -> Unit,
    addTab: () -> Unit,
    closeTab: (Int) -> Unit,
    selectTab: (Int) -> Unit,
    cycleGroup: (Int) -> Unit,
    done: () -> Unit
) {
    val tabAccent = if (incognito) Violet else Orange
    val activeCount = titles.size
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Вкладки", color = Paper, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Твоя рабочая среда · $activeCount открыто", color = Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 3.dp))
            }
            Surface(
                shape = CircleShape,
                color = tabAccent.copy(alpha = .13f),
                border = BorderStroke(1.dp, tabAccent.copy(alpha = .45f))
            ) {
                Text("$activeCount", color = tabAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
            }
            Spacer(Modifier.width(5.dp))
            TextButton(onClick = addTab) {
                Icon(Icons.Default.Add, contentDescription = null, tint = tabAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Новая", color = tabAccent)
            }
            TextButton(onClick = done) { Text("Готово", color = Paper) }
        }

        Spacer(Modifier.height(13.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xCC0D192B),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Border)
        ) {
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !incognito,
                    onClick = { setIncognito(false) },
                    label = { Text("Обычные") },
                    leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Orange.copy(alpha = .18f),
                        selectedLabelColor = Peach,
                        selectedLeadingIconColor = Orange,
                        labelColor = Muted
                    )
                )
                FilterChip(
                    selected = incognito,
                    onClick = { setIncognito(true) },
                    label = { Text("Инкогнито") },
                    leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Violet.copy(alpha = .2f),
                        selectedLabelColor = Color(0xFFD9CCFF),
                        selectedLeadingIconColor = Violet,
                        labelColor = Muted
                    )
                )
            }
        }

        Spacer(Modifier.height(13.dp))
        if (titles.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Color(0xCC101D31)).border(1.dp, Border, RoundedCornerShape(22.dp))
                    .padding(25.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Tab, contentDescription = null, tint = tabAccent, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(9.dp))
                Text("Нет открытых вкладок", color = Paper, fontWeight = FontWeight.Bold)
                Text("Создай новую вкладку, чтобы продолжить.", color = Muted, fontSize = 11.sp)
                TextButton(onClick = addTab) { Text("Создать вкладку", color = tabAccent) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
                contentPadding = PaddingValues(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                items(titles.size, key = { it }) { index ->
                    val isActive = index == activeIndex
                    val pageUrl = urls.getOrNull(index).orEmpty()
                    val host = runCatching { java.net.URI(pageUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
                    val cardAccent = if (incognito) Violet else Orange
                    val animatedBorder by animateColorAsState(
                        targetValue = if (isActive) cardAccent else Border.copy(alpha = .8f),
                        animationSpec = tween(durationMillis = 260),
                        label = "tab-border"
                    )
                    val animatedContainer by animateColorAsState(
                        targetValue = if (isActive) Color(0xFF17334C) else Color(0xCC101D31),
                        animationSpec = tween(durationMillis = 260),
                        label = "tab-container"
                    )
                    val animatedHeight by animateDpAsState(
                        targetValue = if (isActive) 126.dp else 108.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                        label = "tab-height"
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(animatedHeight).clickable { selectTab(index) },
                        color = animatedContainer,
                        shape = RoundedCornerShape(if (isActive) 24.dp else 20.dp),
                        border = BorderStroke(if (isActive) 1.7.dp else 1.dp, animatedBorder),
                        shadowElevation = if (isActive) 13.dp else 3.dp
                    ) {
                        Row(
                            Modifier.fillMaxSize().padding(start = 12.dp, end = 7.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.width(if (isActive) 5.dp else 3.dp).fillMaxHeight().clip(CircleShape)
                                    .background(if (isActive) cardAccent else cardAccent.copy(alpha = .25f))
                            )
                            Spacer(Modifier.width(11.dp))
                            Box(
                                Modifier.size(if (isActive) 49.dp else 44.dp).clip(RoundedCornerShape(15.dp))
                                    .background(Brush.linearGradient(listOf(cardAccent.copy(alpha = .25f), Color(0x331B3E67)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (incognito) Icons.Default.VisibilityOff else Icons.Default.Public,
                                    contentDescription = null,
                                    tint = cardAccent,
                                    modifier = Modifier.size(if (isActive) 25.dp else 22.dp)
                                )
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                AnimatedVisibility(
                                    visible = isActive,
                                    enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
                                    exit = fadeOut(tween(120))
                                ) {
                                    Text("АКТИВНАЯ ВКЛАДКА", color = cardAccent, fontSize = 8.sp,
                                        fontWeight = FontWeight.Black, letterSpacing = 1.1.sp,
                                        modifier = Modifier.padding(bottom = 3.dp))
                                }
                                Text(titles[index].ifBlank { "Вкладка ${index + 1}" }, color = Paper,
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                Text(host.ifBlank { if (pageUrl.isBlank()) "Стартовая страница" else pageUrl },
                                    color = Muted, fontSize = 10.sp, maxLines = 1)
                                Text(
                                    "◈  ${groups.getOrNull(index) ?: "Основная"}  ·  изменить",
                                    color = cardAccent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { cycleGroup(index) }.padding(top = 5.dp)
                                )
                            }
                            IconButton(
                                onClick = { closeTab(index) },
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x441D3653))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть вкладку",
                                    tint = Paper, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Нажми на карточку для переключения. Группу можно менять нажатием на её название.",
            color = Muted, fontSize = 10.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}