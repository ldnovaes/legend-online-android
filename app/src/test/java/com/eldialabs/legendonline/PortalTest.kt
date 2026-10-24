package com.eldialabs.legendonline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortalTest {
    private val portalHtml = """
        <div class="server_played">
          <a href="/serverlist/s3265" class="recomm_l1"><span></span>
             OAS1265:C&atilde;o de Tr&ecirc;s Olhos <i></i></a>
        </div>
        <a href="javascript:void(0)" data-url="/serverlist/s9999" id="s_quick_play">Jogar</a>
        <a href="/serverlist/s9999">Jogar</a>
        <ul>
          <li><a href="/serverlist/s3265"><span></span> OAS1265:Cão de Três Olhos </a></li>
          <li><a href="/serverlist/s3264" target="_blank"><span></span> OAS1264:Gole de Cervejinha</a></li>
          <li><a href="/serverlist/s12">Servidor sem código</a></li>
          <li><a href="/serverlist/s13"><span></span></a></li>
          <li><a href="/articlelist/news">Notícias</a></li>
        </ul>
    """

    @Test
    fun parseDedupesSortsAndSkipsPlaceholders() {
        val found = parseServers(portalHtml)
        assertEquals(listOf(3265, 3264, 12), found.map { it.id })
        assertEquals(Server(3265, "OAS1265", "Cão de Três Olhos"), found[0])
        assertEquals("OAS1264 — Gole de Cervejinha", found[1].label)
        assertEquals("Servidor sem código", found[2].label)
    }

    @Test
    fun urls() {
        assertEquals("https://lobr.creaction-network.com/serverlist/s3265", playUrl("lobr", 3265))
        assertThrows(IllegalArgumentException::class.java) { portalUrl("evil.example/") }
    }

    @Test
    fun buildsLoadingSwfCallLikeGameJsp() {
        val start = startFromGameFrame(
            "https://s1217oas.creaction-network.com/client/game.jsp" +
                "?user=359&key=15d4e891d784977cacbfcbb00c48f133&site=broas_1237",
            now = 42,
        )
        assertEquals("https://s1217oas.creaction-network.com/client/Loading.swf?rnd=42", start.movie)
        assertEquals(
            "user=359&key=15d4e891d784977cacbfcbb00c48f133&site=broas_1237" +
                "&p2=&rid=&logintype=&http=https%3A",
            start.flashvars,
        )
        assertEquals("https://s1217oas.creaction-network.com/client/", start.base)
    }

    @Test
    fun rejectsUnexpectedOrIncompleteFrames() {
        for (url in listOf(
            "https://evil.example/client/other.jsp?user=1&key=2",
            "file:///client/game.jsp?user=1&key=2",
            "https://s1.creaction-network.com/client/game.jsp?user=1",
        )) {
            assertThrows(url, PortalException::class.java) { startFromGameFrame(url) }
        }
    }
}
