package com.darknes.tor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

private val Night = Color(0xFF090B10)
private val Panel = Color(0xFF151922)
private val PanelRaised = Color(0xFF1D2430)
private val Accent = Color(0xFFB8C7FF)
private val AccentBright = Color(0xFF8FA8FF)
private val Muted = Color(0xFF9299A8)

class MainActivity : ComponentActivity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 11, 16)
        window.navigationBarColor = android.graphics.Color.rgb(9, 11, 16)
        runtime = GeckoRuntime.create(this)
        session = GeckoSession()
        session.open(runtime)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Night,
                    surface = Panel,
                    primary = Accent,
                    onPrimary = Night,
                    onSurface = Color.White
                )
            ) {
                DarknesBrowser(
                    session = session,
                    onLoad = { address -> session.loadUri(normalizeAddress(address)) }
                )
            }
        }
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}

private fun normalizeAddress(input: String): String {
    val value = input.trim()
    if (value.startsWith("https://") || value.startsWith("http://")) return value
    return if (value.contains(".") && !value.contains(" ")) {
        "https://$value"
    } else {
        "https://www.google.com/search?q=" +
            java.net.URLEncoder.encode(value, "UTF-8")
    }
}

@Composable
private fun DarknesBrowser(
    session: GeckoSession,
    onLoad: (String) -> Unit
) {
    var address by remember { mutableStateOf("") }
    var showAi by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var tabCount by remember { mutableIntStateOf(1) }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Night)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(AccentBright, Color(0xFF667DEB)))),
                contentAlignment = Alignment.Center
            ) {
                Text("D", color = Night, fontSize = 21.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("DARKNES", color = Color.White, fontSize = 16.sp, letterSpacing = 2.sp)
                Text("BROWSER", color = Muted, fontSize = 9.sp, letterSpacing = 2.5.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(
                color = Panel,
                shape = CircleShape,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { showAi = !showAi }
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("AI", color = Accent, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(7.dp))
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreHoriz, "Меню", tint = Color.White)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(PanelRaised)
                ) {
                    DropdownMenuItem(
                        text = { Text("Обновить страницу", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, tint = Accent) },
                        onClick = {
                            showMenu = false
                            session.reload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Новая вкладка", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Tab, null, tint = Accent) },
                        onClick = {
                            showMenu = false
                            tabCount += 1
                        }
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(PanelRaised)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (address.isNotBlank()) onLoad(address)
                        keyboard?.hide()
                    }
                ),
                decorationBox = { inner ->
                    Box {
                        if (address.isEmpty()) {
                            Text("Поиск или адрес сайта", color = Muted, fontSize = 14.sp)
                        }
                        inner()
                    }
                }
            )
            if (address.isNotEmpty()) {
                IconButton(onClick = { address = "" }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Очистить", tint = Muted, modifier = Modifier.size(17.dp))
                }
            }
            TextButton(
                onClick = {
                    if (address.isNotBlank()) onLoad(address)
                    keyboard?.hide()
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("GO", color = Accent, fontSize = 12.sp)
            }
        }

        AnimatedVisibility(
            visible = showAi,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 })
        ) {
            Surface(
                color = Panel,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .animateContentSize()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Darknes AI", color = Color.White, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showAi = false }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, "Закрыть", tint = Muted)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Умный помощник Darknes",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Панель интерфейса готова. Для отправки запросов и получения ответов потребуется подключить сервер Darknes AI.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                factory = { context -> GeckoView(context).apply { setSession(session) } },
                modifier = Modifier.fillMaxSize()
            )
            if (tabCount == 1 && address.isBlank()) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(66.dp)
                            .clip(RoundedCornerShape(23.dp))
                            .background(Brush.linearGradient(listOf(AccentBright, Color(0xFF596FD5)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("D", color = Night, fontSize = 39.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Добро пожаловать", color = Color.White, fontSize = 23.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Всё нужное для твоего интернета", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(22.dp))
                    Surface(
                        color = Panel,
                        shape = CircleShape,
                        modifier = Modifier.clickable {
                            address = "https://www.google.com"
                            onLoad(address)
                        }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, null, tint = Accent, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Начать поиск", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Panel)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { session.navigationController.goBack() }) {
                Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White)
            }
            IconButton(onClick = { session.navigationController.goForward() }) {
                Icon(Icons.Default.ArrowForward, "Вперёд", tint = Color.White)
            }
            IconButton(onClick = { onLoad("https://www.google.com") }) {
                Icon(Icons.Default.Home, "Домой", tint = Color.White)
            }
            Surface(
                color = PanelRaised,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.clickable { showMenu = true }
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Tab, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("$tabCount", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}
