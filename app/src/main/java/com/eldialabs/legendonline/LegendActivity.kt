package com.eldialabs.legendonline

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import rs.ruffle.PlayerActivity
import kotlin.concurrent.thread

private const val TAG = "LegendOnline"
private const val MAX_RETRIES = 5
private const val RETRY_DELAY_MS = 2_000L

/** Script que lê o endereço do game.jsp no iframe da página do servidor. */
private const val FRAME_SCRIPT = """(function () {
    var frame = document.getElementById("home_playFrame")
        || document.querySelector("iframe[src*='/client/game.jsp']");
    return frame ? frame.src : "";
})()"""

private const val SESSION_SCRIPT =
    "document.cookie.split('; ').some(function (c) { return c.indexOf('oas_user=') === 0; })"

private val Gold = Color(0xFFF0C674)
private val Panel = Color(0xFF221D2C)
private val Background = Color(0xFF17141F)
private val Accent = Color(0xFFB5651D)

/**
 * Tela única do app: lista de servidores, login oficial quando necessário e
 * abertura do jogo. Um só WebView, invisível na lista e visível no login,
 * resolve o endereço do jogo com a sessão do portal.
 */
class LegendActivity : ComponentActivity() {
    private enum class Screen { SERVERS, LOGIN }

    private var screen by mutableStateOf(Screen.SERVERS)
    private var region by mutableStateOf("lobr")
    private val servers = mutableStateListOf<Server>()
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var loadFailed by mutableStateOf(false)
    private var pending: Server? = null
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        region = prefs().getString("region", "lobr") ?: "lobr"
        CookieManager.getInstance().setAcceptCookie(true)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT
            // O portal só tem layout de desktop: encaixa a página na largura da tela.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = PortalClient()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background)) {
                Box(Modifier.fillMaxSize().background(Background).safeDrawingPadding()) {
                    when (screen) {
                        Screen.SERVERS -> ServersScreen()
                        Screen.LOGIN -> LoginScreen()
                    }
                }
            }
        }
        loadServers()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("legend", Context.MODE_PRIVATE)

    private fun recentIds(): List<Int> =
        prefs().getString("recent/$region", "")!!.split(',').mapNotNull { it.toIntOrNull() }

    private fun remember(server: Server) {
        val ids = (listOf(server.id) + recentIds().filter { it != server.id }).take(5)
        prefs().edit().putString("recent/$region", ids.joinToString(",")).apply()
    }

    /**
     * Baixa a lista. Falhas de rede (ex.: logo após ligar o aparelho ou trocar de
     * rede) são tentadas de novo sozinhas algumas vezes, com intervalo crescente.
     */
    private fun loadServers(attempt: Int = 0) {
        prefs().edit().putString("region", region).apply()
        servers.clear()
        loadFailed = false
        status = if (attempt == 0) "Baixando lista de servidores…" else "Sem conexão; tentando de novo…"
        val wanted = region
        thread(name = "servers-$wanted") {
            val result = runCatching { fetchServers(wanted) }
            runOnUiThread {
                if (wanted != region) return@runOnUiThread
                result.onSuccess {
                    servers.addAll(it)
                    status = "${it.size} servidores. Toque em um para jogar."
                }.onFailure {
                    Log.e(TAG, "lista de servidores falhou (tentativa ${attempt + 1})", it)
                    if (it is java.io.IOException && attempt < MAX_RETRIES) {
                        window.decorView.postDelayed({
                            if (wanted == region && servers.isEmpty()) loadServers(attempt + 1)
                        }, RETRY_DELAY_MS * (attempt + 1))
                    } else {
                        loadFailed = true
                        status = "Não foi possível obter a lista: ${it.message}"
                    }
                }
            }
        }
    }

    private fun play(server: Server) {
        Log.i(TAG, "abrindo servidor ${server.id} (${server.label})")
        pending = server
        busy = true
        status = "${server.label}: preparando o jogo…"
        webView.loadUrl(playUrl(region, server.id))
    }

    private fun cancelLogin() {
        pending = null
        busy = false
        webView.loadUrl("about:blank")
        screen = Screen.SERVERS
        status = "Login cancelado."
    }

    private fun launchGame(server: Server, frameUrl: String) {
        val start = try {
            startFromGameFrame(frameUrl)
        } catch (e: PortalException) {
            fail(server, e.message ?: "endereço de jogo inválido")
            return
        }
        remember(server)
        busy = false
        status = "${server.label}: jogo aberto."
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                data = Uri.parse(start.movie)
                putExtra(PlayerActivity.EXTRA_FLASHVARS, start.flashvars)
                putExtra(PlayerActivity.EXTRA_TITLE, server.label)
            }
        )
    }

    private fun fail(server: Server, message: String) {
        Log.e(TAG, "falha ao abrir ${server.label}: $message")
        busy = false
        status = "${server.label}: $message"
    }

    /** Decide o próximo passo a cada página do portal carregada no WebView. */
    private inner class PortalClient : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val server = pending ?: return
            val uri = Uri.parse(url)
            val portalHost = Uri.parse(portalUrl(region)).host
            when {
                uri.host != portalHost -> Unit // login social ou recursos externos
                uri.path.orEmpty().contains("login") -> {
                    if (screen != Screen.LOGIN) {
                        Log.i(TAG, "sem sessão; mostrando login oficial")
                        screen = Screen.LOGIN
                    }
                }
                uri.path.orEmpty().startsWith("/serverlist/s") ->
                    view.evaluateJavascript(FRAME_SCRIPT) { result ->
                        val src = result.trim('"').replace("\\u0026", "&")
                        if (pending !== server) return@evaluateJavascript
                        pending = null
                        view.loadUrl("about:blank")
                        screen = Screen.SERVERS
                        if (src.isEmpty() || src == "null") {
                            fail(server, "o portal não entregou o jogo (servidor em manutenção?)")
                        } else {
                            launchGame(server, src)
                        }
                    }
                screen == Screen.LOGIN ->
                    // O portal saiu do formulário: confirma a sessão e volta ao servidor.
                    view.evaluateJavascript(SESSION_SCRIPT) { hasSession ->
                        if (hasSession == "true" && pending === server) {
                            Log.i(TAG, "sessão confirmada; abrindo ${server.label}")
                            CookieManager.getInstance().flush()
                            view.loadUrl(playUrl(region, server.id))
                        }
                    }
            }
        }
    }

    @Composable
    private fun ServersScreen() {
        var filter by remember { mutableStateOf("") }
        var regionMenu by remember { mutableStateOf(false) }
        val recent = recentIds().mapNotNull { id -> servers.firstOrNull { it.id == id } }
        val needle = filter.trim().lowercase()
        val shown = servers.filter {
            needle.isEmpty() || it.label.lowercase().contains(needle) || it.id.toString() == needle
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Legend Online",
                    color = Gold,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { regionMenu = true }) {
                        Text("${REGIONS[region]} ▾", color = Color.White)
                    }
                    DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                        REGIONS.forEach { (code, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                regionMenu = false
                                if (code != region) {
                                    region = code
                                    loadServers()
                                }
                            })
                        }
                    }
                }
                TextButton(onClick = {
                    CookieManager.getInstance().removeAllCookies(null)
                    status = "Você saiu da conta. O login será pedido ao jogar."
                }) { Text("Sair", color = Color.White) }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Buscar por número ou nome") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).height(18.dp), color = Gold)
                }
                Text(status, color = Color(0xFFA59D8C), fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (loadFailed) {
                    TextButton(onClick = { loadServers() }) { Text("Tentar de novo", color = Gold) }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (recent.isNotEmpty() && needle.isEmpty()) {
                    item { Section("Jogados recentemente") }
                    items(recent, key = { "r${it.id}" }) { ServerRow(it) }
                    item { Section("Todos os servidores") }
                }
                items(shown, key = { it.id }) { ServerRow(it) }
            }
        }
    }

    @Composable
    private fun Section(title: String) {
        Text(title, color = Gold, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    }

    @Composable
    private fun ServerRow(server: Server) {
        Text(
            server.label,
            color = Color(0xFFE9E2D0),
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .clickable(enabled = !busy) { play(server) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = Background)
    }

    @Composable
    private fun LoginScreen() {
        BackHandler { cancelLogin() }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { cancelLogin() },
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White),
                ) { Text("Voltar") }
                Text(
                    "Entre para jogar em ${pending?.label ?: ""}",
                    color = Gold,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
