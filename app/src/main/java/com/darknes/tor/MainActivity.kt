package com.darknes.tor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

private val Night = Color(0xFF090D18)
private val Panel = Color(0xFF151D30)
private val Accent = Color(0xFF8CB9FF)

class MainActivity : ComponentActivity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = GeckoRuntime.create(this)
        session = GeckoSession()
        session.open(runtime)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Night,
                    surface = Panel,
                    primary = Accent
                )
            ) {
                BrowserPrototype(
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
private fun BrowserPrototype(
    session: GeckoSession,
    onLoad: (String) -> Unit
) {
    var address by remember { mutableStateOf("") }
    var showAi by remember { mutableStateOf(false) }
    var tabCount by remember { mutableIntStateOf(1) }

    Column(Modifier.fillMaxSize().background(Night)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("DARKNES", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.width(5.dp))
            Text("TOR", color = Accent, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAi = !showAi }) {
                Text("AI", color = Accent)
            }
            IconButton(onClick = { tabCount++ }) {
                Icon(Icons.Default.Tab, contentDescription = "Новая вкладка", tint = Color.White)
            }
            Text("$tabCount", color = Color.White)
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .background(Panel, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = Accent)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 14.sp
                ),
                decorationBox = { inner ->
                    if (address.isEmpty()) {
                        Text("Поиск или адрес сайта", color = Color.Gray, fontSize = 14.sp)
                    }
                    inner()
                }
            )
            TextButton(onClick = { if (address.isNotBlank()) onLoad(address) }) {
                Text("Перейти", color = Accent)
            }
        }

        if (showAi) {
            Surface(
                color = Panel,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Darknes AI", color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Экран ИИ подготовлен. Для реальных ответов потребуется защищённый сервер Darknes AI и выбранный провайдер модели.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context -> GeckoView(context).apply { setSession(session) } },
                modifier = Modifier.fillMaxSize()
            )
            if (tabCount == 1 && address.isBlank()) {
                Column(
                    Modifier.align(Alignment.Center).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("DARKNES TOR", color = Color.White, fontSize = 28.sp)
                    Text("ТВОЙ ИНТЕРНЕТ. ТВОИ ПРАВИЛА.", color = Accent, fontSize = 11.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Стартовый каркас браузера", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Panel).padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {}) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.ArrowForward, null, tint = Color.White)
            }
            IconButton(onClick = { onLoad("https://www.google.com") }) {
                Icon(Icons.Default.Home, null, tint = Color.White)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, null, tint = Color.White)
            }
        }
    }
}
