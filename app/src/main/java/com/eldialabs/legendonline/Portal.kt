package com.eldialabs.legendonline

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Navegador de desktop: o portal entrega as mesmas páginas que o cliente PC vê. */
const val USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

val REGIONS = linkedMapOf(
    "lobr" to "Brasil",
    "lopl" to "Polska",
    "lode" to "Deutschland",
    "loes" to "España",
    "lonl" to "Nederland",
    "lotr" to "Türkiye",
    "loar" to "العربية",
)

data class Server(val id: Int, val code: String, val name: String) {
    val label: String get() = if (code.isNotEmpty()) "$code — $name" else name
}

class PortalException(message: String) : Exception(message)

fun portalUrl(region: String): String {
    require(region in REGIONS) { "região desconhecida: $region" }
    return "https://$region.creaction-network.com"
}

fun playUrl(region: String, serverId: Int) = "${portalUrl(region)}/serverlist/s$serverId"

fun loginUrl(region: String) = "${portalUrl(region)}/login"

private val SERVER_LINK = Regex(
    """<a\b[^>]*\bhref="/serverlist/s(\d+)"[^>]*>(.*?)</a>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)
private val TAGS = Regex("<[^>]+>")
private val SPACES = Regex("\\s+")

/** Link de "jogar rápido" do portal; não é um servidor real. */
private const val PLACEHOLDER_ID = 9999

/** Extrai os servidores dos links /serverlist/sN, do mais novo ao mais antigo. */
fun parseServers(html: String): List<Server> {
    val found = LinkedHashMap<Int, String>()
    for (match in SERVER_LINK.findAll(html)) {
        val id = match.groupValues[1].toInt()
        val text = decodeEntities(match.groupValues[2].replace(TAGS, " "))
            .replace(SPACES, " ").trim()
        if (text.isNotEmpty() && id != PLACEHOLDER_ID) found.putIfAbsent(id, text)
    }
    return found.map { (id, text) ->
        val colon = text.indexOf(':')
        if (colon < 0) {
            Server(id, "", text)
        } else {
            Server(id, text.substring(0, colon).trim(), text.substring(colon + 1).trim())
        }
    }.sortedByDescending { it.id }
}

private fun decodeEntities(text: String): String {
    val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "atilde" to "ã", "otilde" to "õ", "ccedil" to "ç", "aacute" to "á", "eacute" to "é",
        "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ecirc" to "ê", "ocirc" to "ô",
        "acirc" to "â", "agrave" to "à",
    )
    return Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(text) { m ->
        val entity = m.groupValues[1]
        when {
            entity.startsWith("#x") -> entity.substring(2).toInt(16).toChar().toString()
            entity.startsWith("#") -> entity.substring(1).toInt().toChar().toString()
            else -> named[entity] ?: m.value
        }
    }
}

/** Baixa a lista pública do portal. Bloqueante: chamar fora da thread principal. */
fun fetchServers(region: String): List<Server> {
    val connection = URL("${portalUrl(region)}/serverlist").openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", USER_AGENT)
    connection.setRequestProperty("Accept", "text/html")
    connection.connectTimeout = 30_000
    connection.readTimeout = 30_000
    try {
        if (connection.responseCode != 200) {
            throw PortalException("portal respondeu HTTP ${connection.responseCode}")
        }
        val html = connection.inputStream.bufferedReader().use { it.readText() }
        return parseServers(html).ifEmpty {
            throw PortalException("a página do portal não contém servidores")
        }
    } finally {
        connection.disconnect()
    }
}

/** Chamada do Loading.swf que o game.jsp oficial faria. */
data class GameStart(val movie: String, val flashvars: String, val base: String)

/** Parâmetros que o game.jsp repassa da sua URL para o Loading.swf. */
private val GAME_FRAME_PARAMS = listOf("user", "key", "site", "p2", "rid", "logintype")

/**
 * O portal entrega, já autenticado, um iframe para
 * https://sN.creaction-network.com/client/game.jsp?user=…&key=…&site=…
 * e o game.jsp só carrega Loading.swf com esses valores em FlashVars, depois de
 * exigir o plugin do Flash. Montamos essa chamada diretamente.
 */
fun startFromGameFrame(url: String, now: Long = System.currentTimeMillis()): GameStart {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        throw PortalException("endereço de jogo inválido")
    }
    if (uri.scheme !in setOf("http", "https") || uri.path?.endsWith("/game.jsp") != true) {
        throw PortalException("endereço de jogo inesperado: ${uri.scheme}://${uri.host}${uri.path}")
    }
    val query = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate { pair ->
        val (name, value) = (pair.split('=', limit = 2) + "").take(2)
        URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }
    if (query["user"].isNullOrEmpty() || query["key"].isNullOrEmpty()) {
        throw PortalException("o portal não entregou a sessão do jogo (user/key)")
    }
    val pairs = GAME_FRAME_PARAMS.map { it to (query[it] ?: "") } + ("http" to "${uri.scheme}:")
    val flashvars = pairs.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
    return GameStart(
        movie = uri.resolve("Loading.swf?rnd=$now").toString(),
        flashvars = flashvars,
        base = uri.resolve(".").toString(),
    )
}
