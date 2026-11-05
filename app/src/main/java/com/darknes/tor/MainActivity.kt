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
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

private val Ink = Color(0xFF050404)
private val Paper = Color(0xFFF6F1EC)
private val Orange = Color(0xFFFF8A1F)
private val Peach = Color(0xFFFFC27A)
private val Violet = Color(0xFF9D7BFF)
private val Muted = Color(0xFF8B8580)
private val Glass = Color(0xD91A1715)
private val Border = Color(0x35FFF4E8)

class MainActivity : ComponentActivity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    private lateinit var saveDownloadLauncher: ActivityResultLauncher<String>
    private var pendingDownload: WebResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saveDownloadLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
            val response = pendingDownload
            pendingDownload = null
            if (destination == null || response == null) {
                response?.body?.close()
                Toast.makeText(this, "Скачивание отменено", Toast.LENGTH_SHORT).show()
            } else {
                Thread {
                    try {
                        val body = response.body ?: throw IllegalStateException("Нет данных файла")
                        val output = contentResolver.openOutputStream(destination)
                            ?: throw IllegalStateException("Не удалось открыть место сохранения")
                        body.use { input -> output.use { target -> input.copyTo(target) } }
                        runOnUiThread { Toast.makeText(this, "Файл сохранён", Toast.LENGTH_LONG).show() }
                    } catch (error: Exception) {
                        response.body?.close()
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
            DarknesApp(runtime, ::requestDownload)
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
            Brush.verticalGradient(listOf(Color(0xFF100A07), Ink, Color(0xFF080605)))
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
private fun DarknesApp(runtime: GeckoRuntime, onDownload: (WebResponse) -> Unit) {
    var launchAuthenticated by remember { mutableStateOf(false) }
    if (!launchAuthenticated) {
        DarknesLaunchGate(onAuthenticated = { launchAuthenticated = true })
        return
    }
    val sessions = remember { mutableStateListOf(GeckoSession().also { it.open(runtime) }) }
    val tabTitles = remember { mutableStateListOf("Новая вкладка") }
    val tabUrls = remember { mutableStateListOf("") }
    var activeTab by remember { mutableIntStateOf(0) }
    val session = sessions[activeTab.coerceIn(0, sessions.lastIndex)]
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    fun addTab() {
        sessions.add(GeckoSession().also { it.open(runtime) })
        tabTitles.add("Новая вкладка")
        tabUrls.add("")
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
    var query by remember { mutableStateOf("") }
    var islandOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }
    var incognito by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf("DuckDuckGo") }
    val onLoad: (String) -> Unit = { session.loadUri(resolveAddress(it, engine)) }
    var blur by remember { mutableFloatStateOf(8f) }
    var animations by remember { mutableStateOf(true) }
    var trackers by remember { mutableStateOf(true) }
    var darkSites by remember { mutableStateOf(true) }
    var adBlock by remember { mutableStateOf(true) }
    var nightPages by remember { mutableStateOf(true) }
    var model by remember { mutableStateOf("Darknes AI") }
    var chatInput by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showSearchResults by remember { mutableStateOf(false) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var activeSearchText by remember { mutableStateOf("") }
    var searchSource by remember { mutableStateOf(engine) }
    var tabCount by remember { mutableIntStateOf(1) }
    var toast by remember { mutableStateOf("") }
    var testerTapCount by remember { mutableIntStateOf(0) }
    var testerLastTap by remember { mutableLongStateOf(0L) }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val accent = if (incognito) Violet else Orange
    LaunchedEffect(trackers, sessions.size) {
        sessions.forEach { it.settings.setUseTrackingProtection(trackers) }
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
    val transition = updateTransition(targetState = panel, label = "panels")
    DisposableEffect(session) {
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                onDownload(response)
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
                }
            }
        })
        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tabTitles[activeTab] = title?.takeIf { it.isNotBlank() } ?: "Без названия"
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
            key(session) {
                AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(session) } }, modifier = Modifier.fillMaxSize())
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
                        Modifier.fillMaxWidth().fillMaxHeight(if (panel == "Вкладки") .94f else .78f)
                            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                            .background(Glass).border(1.dp, Border, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                            .clickable(enabled = false) {}.padding(20.dp)
                    ) {
                        when (panel) {
                            "Меню" -> MenuPanel(accent) { action ->
                                panel = when(action) {
                                    "Новая вкладка" -> { addTab(); loaded = false; "" }
                                    "Инкогнито" -> { incognito = true; addTab(); loaded = false; "" }
                                    else -> action
                                }
                            }
                            "Настройки" -> SettingsPanel(engine, { engine = it }, trackers, { trackers = it }, darkSites, { darkSites = it }, animations, { animations = it }, blur, { blur = it })
                            "Тестер" -> TesterPanel { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure { toast = "Не удалось открыть загрузку APK" }
                            }
                            "Расширения" -> ExtensionsPanel(adBlock, { adBlock = it }, nightPages, { nightPages = it }) { toast = "Установка расширений появится позже" }
                            "Аккаунт" -> DarknesIdPanel()
                            "ИИ-чат" -> ChatPanel(model, { model = it }, chatInput, { chatInput = it }, sent, { sent = it })
                            "Вкладки" -> TabsPanel(tabTitles.toList(), tabUrls.toList(), activeTab, incognito, { incognito = it }, { addTab(); loaded = false }, { index -> closeTab(index); loaded = false }, { index -> activeTab = index; loaded = tabUrls.getOrNull(index).orEmpty().isNotBlank(); query = tabUrls.getOrNull(index).orEmpty(); panel = "" }, { panel = "" })
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
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF30251C)).clickable { panel = "Вкладки" }, contentAlignment = Alignment.Center) {
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
        Surface(color = Color(0xFF171311), shape = RoundedCornerShape(18.dp),
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
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF151210))
                        .border(1.dp, Border, RoundedCornerShape(18.dp)).clickable { onOpenResult(result.url) }.padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(25.dp).clip(CircleShape).background(Color(0xFF30251C)), contentAlignment = Alignment.Center) {
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
        Spacer(Modifier.height(22.dp))
        HomeSearchBar(
            value = searchText,
            onValueChange = onSearchTextChange,
            engine = engine,
            onEngineChange = onEngineChange,
            accent = accent,
            onSearch = onSearch
        )
        Spacer(Modifier.height(24.dp))
        val sites = listOf("Y" to "YouTube", "D" to "DuckDuckGo", "Gh" to "GitHub", "W" to "Wikipedia", "T" to "Telegram", "R" to "Reddit", "Tw" to "Twitch")
        LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 12.dp), userScrollEnabled = false) {
            items(sites) { (letter, name) ->
                Column(Modifier.padding(5.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF29211B), Color(0xFF141110)))).border(1.dp, Border, RoundedCornerShape(18.dp)).clickable { onShortcut(when(name) { "YouTube" -> "https://youtube.com"; "DuckDuckGo" -> "https://duckduckgo.com"; "GitHub" -> "https://github.com"; "Wikipedia" -> "https://wikipedia.org"; "Telegram" -> "https://telegram.org"; "Reddit" -> "https://reddit.com"; else -> "https://twitch.tv" }) }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(letter, color = Orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(name, color = Paper, fontSize = 10.sp, maxLines = 1)
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
            .background(Color(0xFF171311))
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
            Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF29211B))
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
        Icons.Default.VisibilityOff to "Инкогнито",
        Icons.Default.Extension to "Расширения",
        Icons.Default.AutoAwesome to "ИИ-чат",
        Icons.Default.Settings to "Настройки",
        Icons.Default.Person to "Аккаунт"
    )
    LazyVerticalGrid(columns = GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { (icon, name) ->
            Column(Modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF25201C)).border(1.dp, Border, RoundedCornerShape(20.dp)).clickable { onAction(name) }.padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = name, tint = accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(8.dp))
                Text(name, color = Paper, fontSize = 11.sp)
            }
        }
    }
}
@Composable private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Paper, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Orange))
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
        color = Color(0xFF241B16),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Violet.copy(alpha = .65f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1.2 Alpha Release", color = Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("СТАРТОВАЯ", color = Violet, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text("Первая публичная тестовая версия Darknes Tor.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
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
        versions.isEmpty() -> Text("Пока опубликована только стартовая версия 1.2 Alpha Release. Новые версии появятся здесь после публикации APK в GitHub Releases.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(versions) { version ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF211D1A),
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

@Composable private fun SettingsPanel(engine: String, setEngine: (String)->Unit, trackers:Boolean, setTrackers:(Boolean)->Unit, dark:Boolean, setDark:(Boolean)->Unit, anim:Boolean, setAnim:(Boolean)->Unit, blur:Float, setBlur:(Float)->Unit) {
    PanelTitle("Настройки", "Настрой Darknes Tor под себя")
    Text("Поисковая система · DuckDuckGo", color = Muted, fontSize = 13.sp)
    Text("Единственная поисковая система браузера — DuckDuckGo.", color = Peach, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
    SettingSwitch("Блокировать трекеры", trackers, setTrackers)
    SettingSwitch("Тёмный режим сайтов", dark, setDark)
    SettingSwitch("Анимации", anim, setAnim)
    Text("Размытие фона · ${blur.toInt()} px", color = Paper, modifier = Modifier.padding(top=12.dp))
    Slider(value=blur, onValueChange=setBlur, valueRange=0f..24f, colors=SliderDefaults.colors(thumbColor=Orange, activeTrackColor=Orange))
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
@Composable private fun ChatPanel(model:String, setModel:(String)->Unit, input:String, setInput:(String)->Unit, sent:String, setSent:(String)->Unit) {
    PanelTitle("ИИ-чат", "Выбери помощника")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf("Darknes AI","Claude","GPT","Gemini").forEach { FilterChip(selected=model==it,onClick={setModel(it)},label={Text(it)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Orange,selectedLabelColor=Ink)) }
    }
    Spacer(Modifier.height(16.dp))
    Column(Modifier.heightIn(min=120.dp,max=300.dp).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(sent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) { Text(sent, color=Ink, modifier=Modifier.clip(RoundedCornerShape(18.dp)).background(Orange).padding(13.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.Start) { Text("Это макет чата. Ответы подключатся к выбранной модели в приложении.", color=Paper, modifier=Modifier.fillMaxWidth(.88f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF292522)).padding(13.dp), lineHeight=20.sp) }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF292522)).padding(8.dp), verticalAlignment=Alignment.CenterVertically) {
        BasicTextField(input,setInput,Modifier.weight(1f).padding(10.dp),textStyle=TextStyle(color=Paper,fontSize=14.sp),decorationBox={inner->Box{if(input.isEmpty()) Text("Спросить · $model",color=Muted);inner()}})
        Box(Modifier.size(42.dp).background(Orange,CircleShape).clickable { if(input.isNotBlank()) { setSent(input); setInput("") } },contentAlignment=Alignment.Center) { Icon(Icons.Default.Send, contentDescription = "Отправить", tint = Ink, modifier = Modifier.size(19.dp)) }
    }
}
@Composable
private fun TabsPanel(
    titles: List<String>,
    urls: List<String>,
    activeIndex: Int,
    incognito: Boolean,
    setIncognito: (Boolean) -> Unit,
    addTab: () -> Unit,
    closeTab: (Int) -> Unit,
    selectTab: (Int) -> Unit,
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF171311)).padding(5.dp),
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(titles.size) { index ->
                val isActive = index == activeIndex
                val pageUrl = urls.getOrNull(index).orEmpty()
                val host = runCatching { java.net.URI(pageUrl).host.orEmpty().removePrefix("www.") }
                    .getOrDefault("")
                val cardShape = RoundedCornerShape(21.dp)
                val cardColors = if (incognito) {
                    listOf(Color(0xFF392A60), Color(0xFF191426))
                } else {
                    listOf(Color(0xFF382419), Color(0xFF17110E))
                }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(.91f)
                        .clip(cardShape)
                        .background(Brush.linearGradient(cardColors))
                        .border(
                            if (isActive) 2.dp else 1.dp,
                            if (isActive) tabAccent else Border,
                            cardShape
                        )
                        .clickable { selectTab(index) }
                        .animateContentSize()
                        .padding(13.dp)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(31.dp).clip(RoundedCornerShape(10.dp))
                                    .background(tabAccent.copy(alpha = .16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (incognito) Icons.Default.VisibilityOff else Icons.Default.Public,
                                    contentDescription = null,
                                    tint = tabAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(7.dp))
                            if (isActive) {
                                Text(
                                    "АКТИВНА",
                                    color = tabAccent,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = .7.sp
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            titles[index].ifBlank { "Вкладка ${index + 1}" },
                            color = Paper,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            host.ifBlank {
                                if (pageUrl.isBlank()) "Стартовая страница" else pageUrl
                            },
                            color = Paper.copy(alpha = .62f),
                            fontSize = 10.sp,
                            maxLines = 2
                        )
                    }
                    IconButton(
                        onClick = { closeTab(index) },
                        modifier = Modifier.align(Alignment.TopEnd).size(34.dp)
                            .clip(CircleShape).background(Color(0x55200000))
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть вкладку",
                            tint = Paper,
                            modifier = Modifier.size(17.dp)
                        )
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
