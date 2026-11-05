package com.darknes.tor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        runtime = GeckoRuntime.create(this)
        session = GeckoSession().also { it.open(runtime) }
        setContent { MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Glass, primary = Orange)) {
            DarknesApp(runtime)
        } }
    }
    override fun onDestroy() { if (::session.isInitialized) session.close(); super.onDestroy() }
}

private fun resolveAddress(raw: String, engine: String): String {
    val s = raw.trim()
    if (s.startsWith("https://") || s.startsWith("http://")) return s
    if (s.contains(".") && !s.contains(" ")) return "https://$s"
    val q = java.net.URLEncoder.encode(s, "UTF-8")
    return if (engine == "Google") "https://www.google.com/search?q=$q" else "https://yandex.ru/search/?text=$q"
}

@Composable
private fun DarknesApp(runtime: GeckoRuntime) {
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
    var engine by remember { mutableStateOf("Яндекс") }
    val onLoad: (String) -> Unit = { session.loadUri(resolveAddress(it, engine)) }
    var blur by remember { mutableFloatStateOf(8f) }
    var animations by remember { mutableStateOf(true) }
    var trackers by remember { mutableStateOf(true) }
    var darkSites by remember { mutableStateOf(true) }
    var adBlock by remember { mutableStateOf(true) }
    var passwords by remember { mutableStateOf(false) }
    var nightPages by remember { mutableStateOf(true) }
    var model by remember { mutableStateOf("Darknes AI") }
    var chatInput by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var tabCount by remember { mutableIntStateOf(1) }
    var toast by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = if (incognito) Violet else Orange
    val scope = rememberCoroutineScope()
    val transition = updateTransition(targetState = panel, label = "panels")
    DisposableEffect(session) {
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
                        Text(if (incognito) "Инкогнито" else "Darknes Tor", color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (incognito) "◉" else "⌕", color = accent, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f),
                            singleLine = true, textStyle = TextStyle(color = Paper, fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                if (query.isNotBlank()) { onLoad(resolveAddress(query, engine)); loaded = true }
                                keyboard?.hide(); islandOpen = false
                            }),
                            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Поиск или адрес", color = Muted, fontSize = 14.sp); inner() } }
                        )
                        Box(Modifier.size(42.dp).background(accent, CircleShape).clickable {
                            if (query.isNotBlank()) { onLoad(resolveAddress(query, engine)); loaded = true }
                            keyboard?.hide(); islandOpen = false
                        }, contentAlignment = Alignment.Center) { Text("➜", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(session) {
                AndroidView(factory = { ctx -> GeckoView(ctx).apply { setSession(session) } }, modifier = Modifier.fillMaxSize())
            }
            if (!loaded) HomeScreen(accent, blur, animations) { link ->
                query = link
                onLoad(link)
                loaded = true
            }
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
                            "Расширения" -> ExtensionsPanel(adBlock, { adBlock = it }, passwords, { passwords = it }, nightPages, { nightPages = it }) { toast = "Установка расширений появится позже" }
                            "Аккаунт" -> AccountPanel { toast = "Вход в Darknes появится позже" }
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
            NavCircle("‹", accent, enabled = canGoBack) { if (canGoBack) session.goBack() }
            NavCircle("›", accent, enabled = canGoForward) { if (canGoForward) session.goForward() }
            NavCircle("✧", accent) { panel = "ИИ-чат" }
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF30251C)).clickable { panel = "Вкладки" }, contentAlignment = Alignment.Center) {
                Text("${sessions.size}", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            NavCircle("•••", accent) { panel = "Меню" }
        }
    }
}

@Composable
private fun HomeScreen(accent: Color, blur: Float, animations: Boolean, onShortcut: (String) -> Unit) {
    val infinite = rememberInfiniteTransition(label = "orbit")
    val angle by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(40000, easing = LinearEasing), RepeatMode.Restart), label = "orbitAngle")
    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Canvas(Modifier.size(width = 270.dp, height = 205.dp).blur(blur.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawCircle(accent.copy(alpha = .26f), radius = 65.dp.toPx(), center = Offset(cx, cy))
            rotate(angle, Offset(cx, cy)) {
                drawOval(Brush.sweepGradient(listOf(accent.copy(alpha=.1f), Peach, accent, Color(0xFFFFD88C), accent)), topLeft = Offset(cx-112.dp.toPx(), cy-36.dp.toPx()), size = androidx.compose.ui.geometry.Size(224.dp.toPx(), 72.dp.toPx()), style = Stroke(width = 13.dp.toPx()))
                drawOval(Brush.sweepGradient(listOf(Color.Transparent, accent, Peach, accent)), topLeft = Offset(cx-112.dp.toPx(), cy-36.dp.toPx()), size = androidx.compose.ui.geometry.Size(224.dp.toPx(), 72.dp.toPx()), style = Stroke(width = 3.dp.toPx()))
            }
            drawCircle(Color.Black, 36.dp.toPx(), Offset(cx, cy))
            drawCircle(accent.copy(alpha=.75f), 38.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
        }
        Spacer(Modifier.height(4.dp))
        Text("Darknes Tor", style = TextStyle(brush = Brush.linearGradient(listOf(Paper, Peach)), fontSize = 39.sp, fontWeight = FontWeight.Bold))
        Text("Поиск без входа и слежки", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        val sites = listOf("Y" to "YouTube", "G" to "Google", "Я" to "Яндекс", "Gh" to "GitHub", "W" to "Wikipedia", "T" to "Telegram", "R" to "Reddit", "Tw" to "Twitch")
        LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 12.dp), userScrollEnabled = false) {
            items(sites) { (letter, name) ->
                Column(Modifier.padding(5.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF29211B), Color(0xFF141110)))).border(1.dp, Border, RoundedCornerShape(18.dp)).clickable { onShortcut(when(name) { "YouTube" -> "https://youtube.com"; "Google" -> "https://google.com"; "Яндекс" -> "https://ya.ru"; "GitHub" -> "https://github.com"; "Wikipedia" -> "https://wikipedia.org"; "Telegram" -> "https://telegram.org"; "Reddit" -> "https://reddit.com"; else -> "https://twitch.tv" }) }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(letter, color = Orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(name, color = Paper, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable private fun NavCircle(label: String, accent: Color, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable(enabled = enabled) { action() }, contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Paper else Muted.copy(alpha = .35f), fontSize = if (label.length > 1) 14.sp else 25.sp, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun PanelTitle(title: String, subtitle: String = "") {
    Text(title, color = Paper, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    if (subtitle.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) }
    Spacer(Modifier.height(20.dp))
}
@Composable private fun MenuPanel(accent: Color, onAction: (String) -> Unit) {
    PanelTitle("Меню")
    val items = listOf("＋" to "Новая вкладка", "◉" to "Инкогнито", "▦" to "Расширения", "✧" to "ИИ-чат", "⚙" to "Настройки", "◎" to "Аккаунт")
    LazyVerticalGrid(columns = GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { (symbol, name) ->
            Column(Modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF25201C)).border(1.dp, Border, RoundedCornerShape(20.dp)).clickable { onAction(name) }.padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(symbol, color = accent, fontSize = 26.sp)
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
@Composable private fun SettingsPanel(engine: String, setEngine: (String)->Unit, trackers:Boolean, setTrackers:(Boolean)->Unit, dark:Boolean, setDark:(Boolean)->Unit, anim:Boolean, setAnim:(Boolean)->Unit, blur:Float, setBlur:(Float)->Unit) {
    PanelTitle("Настройки", "Настрой Darknes Tor под себя")
    Text("Поисковая система", color = Muted, fontSize = 13.sp)
    Row(Modifier.padding(vertical = 10.dp)) { listOf("Яндекс","Google").forEach { name -> FilterChip(selected = engine==name, onClick={setEngine(name)}, label={Text(name)}, colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Orange, selectedLabelColor=Ink)) } }
    SettingSwitch("Блокировать трекеры", trackers, setTrackers)
    SettingSwitch("Тёмный режим сайтов", dark, setDark)
    SettingSwitch("Анимации", anim, setAnim)
    Text("Размытие фона · ${blur.toInt()} px", color = Paper, modifier = Modifier.padding(top=12.dp))
    Slider(value=blur, onValueChange=setBlur, valueRange=0f..24f, colors=SliderDefaults.colors(thumbColor=Orange, activeTrackColor=Orange))
}
@Composable private fun ExtensionsPanel(ad:Boolean, setAd:(Boolean)->Unit, pass:Boolean, setPass:(Boolean)->Unit, night:Boolean, setNight:(Boolean)->Unit, install:()->Unit) {
    PanelTitle("Расширения", "Дополнительные возможности")
    SettingSwitch("Блокировщик рекламы", ad, setAd)
    SettingSwitch("Менеджер паролей", pass, setPass)
    SettingSwitch("Ночной режим страниц", night, setNight)
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
        Box(Modifier.size(42.dp).background(Orange,CircleShape).clickable { if(input.isNotBlank()) { setSent(input); setInput("") } },contentAlignment=Alignment.Center) { Text("➤",color=Ink) }
    }
}
@Composable private fun TabsPanel(titles:List<String>, urls:List<String>, activeIndex:Int, incognito:Boolean, setIncognito:(Boolean)->Unit, addTab:()->Unit, closeTab:(Int)->Unit, selectTab:(Int)->Unit, done:()->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        PanelTitle("Вкладки")
        Spacer(Modifier.weight(1f))
        TextButton(onClick=addTab) { Text("＋ Новая", color=Orange) }
        TextButton(onClick=done) { Text("Готово", color=Orange) }
    }
    Row(verticalAlignment=Alignment.CenterVertically) {
        FilterChip(selected=!incognito,onClick={setIncognito(false)},label={Text("Обычные")})
        Spacer(Modifier.width(6.dp))
        FilterChip(selected=incognito,onClick={setIncognito(true)},label={Text("Инкогнито")})
    }
    LazyVerticalGrid(columns=GridCells.Fixed(2),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        items(titles.size) { index ->
            Column(Modifier.aspectRatio(.75f).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(if(incognito) listOf(Color(0xFF503A87),Color(0xFF211A35)) else listOf(Color(0xFF9C4B12),Color(0xFF342014)))).border(2.dp,if(index==activeIndex) if(incognito)Violet else Orange else Border,RoundedCornerShape(20.dp)).clickable { selectTab(index) }.padding(12.dp),verticalArrangement=Arrangement.Bottom) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { Text("×",color=Paper,modifier=Modifier.clickable { closeTab(index) }.padding(4.dp)) }
                Spacer(Modifier.weight(1f))
                Text(titles[index].ifBlank { "Вкладка ${index+1}" },color=Paper,fontWeight=FontWeight.Bold,maxLines=2)
                Text(urls.getOrNull(index).orEmpty().ifBlank { if(incognito) "Инкогнито" else "Стартовая страница" },color=Paper.copy(alpha=.65f),fontSize=10.sp,maxLines=2)
            }
        }
    }
}
