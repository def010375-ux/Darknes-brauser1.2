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

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        // Dynamic Island-style address capsule
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
            Surface(
                color = Glass,
                shape = RoundedCornerShape(if (islandOpen) 28.dp else 30.dp),
                border = BorderStroke(1.dp, Border),
                shadowElevation = 18.dp,
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
                    Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (incognito) Icons.Default.VisibilityOff else Icons.Default.Search, contentDescription = if (incognito) "Инкогнито" else "Поиск", tint = accent, modifier = Modifier.size(21.dp))
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
                        Box(Modifier.size(42.dp).background(accent, CircleShape).clickable {
                            if (query.isNotBlank()) runSearch(query)
                            keyboard?.hide(); islandOpen = false
                        }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowForward, contentDescription = "Перейти", tint = Ink, modifier = Modifier.size(21.dp)) }
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
                        session.loadUri(url)
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
                        Modifier.fillMaxWidth().fillMaxHeight(if (panel == "Вкладки" || panel == "Меню" || panel == "Безопасность" || panel == "Чтение") .92f else .78f)
                            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                            .background(Glass).border(1.dp, Border, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                            .clickable(enabled = false) {}.padding(20.dp)
                    ) {
                        when (panel) {
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
                            "Тестер" -> TesterPanel { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure { toast = "Не удалось открыть загрузку APK" }
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
            AnimatedVisibility(toast.isNotEmpty(), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)) {
                Surface(color = Glass, shape = CircleShape, border = BorderStroke(1.dp, Border), shadowElevation = 14.dp) {
                    Text(toast, color = Paper, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), fontSize = 13.sp)
                }
            }
            }
        }

        // Floating bottom glass navigation, above Android gesture/navigation area
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)
                .height(62.dp).clip(RoundedCornerShape(26.dp)).background(Glass)
                .border(1.dp, Border, RoundedCornerShape(26.dp)).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            NavCircle(Icons.Default.ArrowBack, accent, enabled = canGoBack) { if (canGoBack) session.goBack() }
            NavCircle(Icons.Default.ArrowForward, accent, enabled = canGoForward) { if (canGoForward) session.goForward() }
            NavCircle(Icons.Default.Refresh, accent, enabled = tabUrls.getOrNull(activeTab).orEmpty().isNotBlank()) {
                if (tabUrls.getOrNull(activeTab).orEmpty().isNotBlank()) session.reload()
            }
            NavCircle(Icons.Default.AutoAwesome, accent) { panel = "ИИ-чат" }
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF1A3554)).clickable { panel = "Вкладки" }, contentAlignment = Alignment.Center) {
                Text("${sessions.size}", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        BlackHoleScene(
            iconBurst = 0f,
            modifier = Modifier.width(270.dp).height(205.dp).blur(blur.dp),
            accent = accent,
            showOrbitIcons = false,
            showParticles = false
        )
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
            style = TextStyle(brush = Brush.linearGradient(listOf(Paper, Peach)), fontSize = 39.sp, fontWeight = FontWeight.Bold)
        )
        Text("Поиск без входа и слежки", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Surface(
            color = Color(0x33204468),
            shape = RoundedCornerShape(50.dp),
            border = BorderStroke(1.dp, Border.copy(alpha = .9f))
        ) {
            Row(
                Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Orange, Violet)))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "DARKNES TOR  ·  1.5 BETA",
                    color = Color(0xFFBFDFFF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.15.sp
                )
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
        Text("БЫСТРЫЙ ДОСТУП", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp,
            modifier = Modifier.align(Alignment.Start).padding(start = 22.dp, bottom = 9.dp))
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
            Text("ТВОИ ЗАКЛАДКИ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.align(Alignment.Start).padding(start = 22.dp, top = 18.dp, bottom = 8.dp))
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
        Icons.Default.Person to "Аккаунт",
        Icons.Default.Shield to "Безопасность",
        Icons.Default.Translate to "Перевести страницу",
        Icons.Default.MenuBook to "Режим чтения",
        Icons.Default.ViewColumn to "Разделить экран",
        Icons.Default.Download to "Сохранить офлайн",
        Icons.Default.Bookmarks to "Офлайн-страницы"
    )
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        items(items) { (icon, name) ->
            Surface(
                modifier = Modifier.fillMaxWidth().height(66.dp).clickable { onAction(name) },
                color = Color(0xCC111F35),
                shape = RoundedCornerShape(19.dp),
                border = BorderStroke(1.dp, Border)
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(Color(0x553DDFFF), Color(0x553F6DFF)))),
                        contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = name, tint = accent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(name, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
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
    PanelTitle(title, subtitle)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0D192B))
            .border(1.dp, Border, RoundedCornerShape(16.dp)).padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Orange)
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            value = filter, onValueChange = { filter = it },
            modifier = Modifier.weight(1f), singleLine = true,
            textStyle = TextStyle(color = Paper, fontSize = 14.sp),
            decorationBox = { inner -> Box { if (filter.isEmpty()) Text("Найти сохранённый сайт", color = Muted, fontSize = 13.sp); inner() } }
        )
    }
    Spacer(Modifier.height(12.dp))
    val visiblePages = pages.filter { it.title.contains(filter, true) || it.url.contains(filter, true) }
    if (visiblePages.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D192B))
                .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(if (title == "История") Icons.Default.History else Icons.Default.BookmarkBorder,
                contentDescription = null, tint = Orange, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(if (pages.isEmpty()) "Пока здесь пусто" else "Ничего не найдено", color = Paper, fontWeight = FontWeight.SemiBold)
            Text(if (title == "История") "Открытые сайты появятся здесь." else "Добавляй страницы через меню браузера.", color = Muted, fontSize = 12.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visiblePages, key = { it.url }) { page ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF111E32),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Row(
                        Modifier.clickable { onOpen(page.url) }.padding(start = 13.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = Orange, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(page.title, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(page.url, color = Muted, fontSize = 10.sp, maxLines = 1)
                        }
                        IconButton(onClick = { onRemove(page) }) {
                            Icon(Icons.Default.Close, contentDescription = "Удалить", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
    if (onClear != null && pages.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onClear) { Text("Очистить историю", color = Orange) }
    }
    Spacer(Modifier.height(8.dp))
    Text("Данные хранятся на этом устройстве. Режим инкогнито не добавляет страницы в историю.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Paper, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Orange))
    }
}
@Composable
private fun DownloadsPanel(downloads: List<DownloadRecord>, onRemove: (Long) -> Unit, onOpen: (DownloadRecord) -> Unit) {
    PanelTitle("Загрузки", "Менеджер загруженных файлов")
    if (downloads.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0D192B)).border(1.dp, Border, RoundedCornerShape(20.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = Orange, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text("Пока ничего не скачано", color = Paper, fontWeight = FontWeight.SemiBold)
            Text("Сохранённые файлы появятся здесь.", color = Muted, fontSize = 12.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(downloads, key = { it.id }) { item ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF111E32),
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Orange, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                            Text(item.status, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            if (item.status == "Скачивается") {
                                if (item.totalBytes > 0L) {
                                    val fraction = (item.downloadedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = Orange)
                                    Text("${(fraction * 100).toInt()}% · ${item.downloadedBytes / 1024} KB / ${item.totalBytes / 1024} KB", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = Orange)
                                    Text("${item.downloadedBytes / 1024} KB", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                                }
                            }
                        }
                        if (item.savedUri != null) {
                            TextButton(onClick = { onOpen(item) }) { Text("Открыть", color = Orange, fontSize = 12.sp) }
                        }
                        IconButton(onClick = { onRemove(item.id) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить запись", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("История загрузок сохраняется на устройстве. Незавершённые загрузки после перезапуска отмечаются как прерванные.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
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
        color = Color(0xFF13243C),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Violet.copy(alpha = .65f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1.5 Beta", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("В РАЗРАБОТКЕ", color = Violet, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text("Новая футуристическая оболочка, обновлённая иконка и переработанный интерфейс. Версия ещё не опубликована.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Text("Это подготовленная версия проекта, а не опубликованный релиз.", color = Peach, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
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
        versions.isEmpty() -> Text("Пока нет опубликованных APK. Darknes Tor 1.5 Beta находится в разработке; готовые сборки появятся здесь после публикации в GitHub Releases.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(versions) { version ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF111E32),
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

@Composable private fun SettingsPanel(engine: String, setEngine: (String)->Unit, trackers:Boolean, setTrackers:(Boolean)->Unit, dark:Boolean, setDark:(Boolean)->Unit, anim:Boolean, setAnim:(Boolean)->Unit, blur:Float, setBlur:(Float)->Unit, accentChoice:String, setAccentChoice:(String)->Unit, desktopMode:Boolean, setDesktopMode:(Boolean)->Unit) {
    PanelTitle("Настройки", "Настрой Darknes Tor под себя")
    Text("Поисковая система · DuckDuckGo", color = Muted, fontSize = 13.sp)
    Text("Единственная поисковая система браузера — DuckDuckGo.", color = Peach, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
    SettingSwitch("Блокировать трекеры", trackers, setTrackers)
    SettingSwitch("Тёмный режим сайтов", dark, setDark)
    SettingSwitch("Анимации", anim, setAnim)
    SettingSwitch("Версия сайтов для компьютера", desktopMode, setDesktopMode)
    Text("Режим меняет User-Agent и viewport GeckoView для всех вкладок. Некоторые сайты могут отображаться иначе.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    Text("Размытие фона · ${blur.toInt()} px", color = Paper, modifier = Modifier.padding(top=12.dp))
    Slider(value=blur, onValueChange=setBlur, valueRange=0f..24f, colors=SliderDefaults.colors(thumbColor=Orange, activeTrackColor=Orange))
    Text("Цвет акцента", color = Paper, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf("orange" to "Оранжевый", "blue" to "Синий", "purple" to "Фиолетовый", "green" to "Зелёный").forEach { (key, label) ->
            FilterChip(selected = accentChoice == key, onClick = { setAccentChoice(key) }, label = { Text(label, fontSize = 10.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Orange.copy(alpha = .22f), selectedLabelColor = Peach, labelColor = Muted))
        }
    }
    Text("Дополнительные параметры защиты находятся в Центре безопасности.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
}
@Composable private fun PrivacyPanel(trackers:Boolean, setTrackers:(Boolean)->Unit, ad:Boolean, setAd:(Boolean)->Unit, dark:Boolean, setDark:(Boolean)->Unit, night:Boolean, setNight:(Boolean)->Unit, openAndroidSettings:()->Unit) {
    PanelTitle("Центр безопасности", "Контроль защиты и разрешений сайтов")
    Surface(Modifier.fillMaxWidth(), color = Color(0xFF111E32), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = Orange, modifier = Modifier.size(25.dp)); Spacer(Modifier.width(9.dp))
            Column { Text("Защита GeckoView", color = Paper, fontWeight = FontWeight.SemiBold); Text("Enhanced Tracking Protection", color = Muted, fontSize = 10.sp) }
        }
    }
    SettingSwitch("Блокировать известные трекеры", trackers, setTrackers)
    SettingSwitch("Усиленная защита от отслеживания", ad, setAd)
    SettingSwitch("Тёмная схема сайтов", dark, setDark)
    SettingSwitch("Ночной режим страниц", night, setNight)
    Spacer(Modifier.height(10.dp))
    Text("Камера, микрофон, геолокация и уведомления управляются разрешениями Android и запросами самих сайтов.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(12.dp))
    Button(onClick = openAndroidSettings, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink), shape = RoundedCornerShape(16.dp)) { Text("Разрешения приложения Android", fontWeight = FontWeight.Bold) }
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
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Вкладки", color = Paper, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("${titles.size} открыто", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            TextButton(onClick = addTab) {
                Icon(Icons.Default.Add, contentDescription = null, tint = tabAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Новая", color = tabAccent)
            }
            TextButton(onClick = done) { Text("Готово", color = Paper) }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D192B)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = !incognito,
                onClick = { setIncognito(false) },
                label = { Text("Обычные") },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Orange.copy(alpha = .18f),
                    selectedLabelColor = Peach,
                    labelColor = Muted
                )
            )
            FilterChip(
                selected = incognito,
                onClick = { setIncognito(true) },
                label = { Text("Инкогнито") },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Violet.copy(alpha = .2f),
                    selectedLabelColor = Color(0xFFD9CCFF),
                    labelColor = Muted
                )
            )
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(titles.size) { index ->
                val isActive = index == activeIndex
                val pageUrl = urls.getOrNull(index).orEmpty()
                val host = runCatching { java.net.URI(pageUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
                Surface(
                    modifier = Modifier.fillMaxWidth().height(112.dp).clickable { selectTab(index) },
                    color = if (isActive) Color(0xDD17334C) else Color(0xCC101D31),
                    shape = RoundedCornerShape(23.dp),
                    border = BorderStroke(if (isActive) 2.dp else 1.dp, if (isActive) tabAccent else Border),
                    shadowElevation = if (isActive) 12.dp else 4.dp
                ) {
                    Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(5.dp).fillMaxHeight().clip(CircleShape)
                            .background(if (isActive) tabAccent else tabAccent.copy(alpha = .28f)))
                        Spacer(Modifier.width(13.dp))
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(tabAccent.copy(alpha = .25f), Color(0x331B3E67)))),
                            contentAlignment = Alignment.Center) {
                            Icon(if (incognito) Icons.Default.VisibilityOff else Icons.Default.Public,
                                contentDescription = null, tint = tabAccent, modifier = Modifier.size(25.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            if (isActive) Text("АКТИВНАЯ ВКЛАДКА", color = tabAccent, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Text(titles[index].ifBlank { "Вкладка ${index + 1}" }, color = Paper,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                            Text(host.ifBlank { if (pageUrl.isBlank()) "Стартовая страница" else pageUrl },
                                color = Muted, fontSize = 10.sp, maxLines = 1)
                            Text("Группа: ${groups.getOrNull(index) ?: "Основная"} · изменить",
                                color = tabAccent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { cycleGroup(index) }.padding(top = 5.dp))
                        }
                        IconButton(onClick = { closeTab(index) }, modifier = Modifier.size(38.dp)
                            .clip(CircleShape).background(Color(0x441D3653))) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть вкладку",
                                tint = Paper, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Text(
            "Нажми на карточку, чтобы переключиться. Закрыть вкладку можно крестиком.",
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
