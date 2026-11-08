package com.jjmluz.quemdiria

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server over real sockets on localhost, as a phone's browser would use it. */
class WebServerTest {
    private val lobby = Lobby()
    private val pages = mapOf("index.html" to "<html><h1>hi</h1>", "app.js" to "go()")
    private val server = WebServer(lobby) { pages[it]?.toByteArray() }
    private var port = 0

    @BeforeTest
    fun start() {
        port = server.start(0)
    }

    @AfterTest
    fun stop() = server.stop()

    private class Response(val status: Int, val type: String?, val body: ByteArray) {
        val text get() = String(body)
    }

    private fun call(method: String, path: String, body: ByteArray? = null, type: String = "application/x-www-form-urlencoded"): Response {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", type)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        return Response(status, connection.contentType, stream?.use { it.readBytes() } ?: ByteArray(0))
    }

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray()

    private fun join(name: String): String {
        val response = call("POST", "/api/join", form("name" to name))
        assertEquals(200, response.status, response.text)
        return Regex("\"token\":\"(\\w+)\"").find(response.text)!!.groupValues[1]
    }

    @Test
    fun servesThePhonePage() {
        val index = call("GET", "/")
        assertEquals(200, index.status)
        assertEquals("text/html; charset=utf-8", index.type)
        assertEquals("<html lang=\"en\"><h1>hi</h1>", index.text)
        assertEquals("go()", call("GET", "/app.js").text)
        assertEquals(404, call("GET", "/missing.css").status)
        assertEquals(404, call("GET", "/../secret").status)
    }

    @Test
    fun anyoneOnTheNetworkCanJoin() {
        join("Ana")
        // An old link with a room code in it, from before there was no code, still works.
        assertEquals(200, call("POST", "/api/join", form("c" to "ZZZZ", "name" to "Rui")).status)
        assertEquals(listOf("Ana", "Rui"), lobby.room.value.players.map { it.name })
    }

    @Test
    fun joinErrorsSayWhy() {
        join("Ana")
        val taken = call("POST", "/api/join", form("name" to "ana"))
        assertEquals(409, taken.status)
        assertEquals("{\"error\":\"taken\"}", taken.text)
        assertEquals("{\"error\":\"name\"}", call("POST", "/api/join", form("name" to " ")).text)
    }

    @Test
    fun nonAsciiNamesSurviveTheTrip() {
        val token = join("João 🎉")
        assertEquals("João 🎉", lobby.playerFor(token)?.name)
        assertContains(call("GET", "/api/me?t=$token").text, "\"name\":\"João 🎉\"")
    }

    @Test
    fun renameAndLeave() {
        val token = join("Ana")
        join("Rui")
        assertEquals(409, call("POST", "/api/name?t=$token", form("name" to "Rui")).status)
        assertEquals(204, call("POST", "/api/name?t=$token", form("name" to "Aninhas")).status)
        assertEquals("Aninhas", lobby.playerFor(token)?.name)
        assertEquals(401, call("POST", "/api/name?t=wrong", form("name" to "X")).status)
        assertEquals(204, call("POST", "/api/leave?t=$token", ByteArray(0)).status)
        assertEquals(401, call("GET", "/api/me?t=$token").status)
    }

    @Test
    fun selfiesGoUpAndComeBack() {
        val token = join("Ana")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        assertEquals(415, call("POST", "/api/selfie?t=$token", byteArrayOf(1, 2, 3, 4), "image/jpeg").status)
        assertEquals(204, call("POST", "/api/selfie?t=$token", jpeg, "image/jpeg").status)
        val id = lobby.playerFor(token)!!.id
        // Only players see the photos: not some other device on the network.
        assertEquals(403, call("GET", "/selfie/$id").status)
        assertEquals(403, call("GET", "/selfie/$id?t=wrong").status)
        val back = call("GET", "/selfie/$id?t=${join("Rui")}&v=1")
        assertEquals("image/jpeg", back.type)
        assertContentEquals(jpeg, back.body)
        assertContains(call("GET", "/api/me?t=$token").text, "\"selfie\":1")
    }

    @Test
    fun tooBigABodyIsRefused() {
        val token = join("Ana")
        val huge = ByteArray(3 * 1024 * 1024).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        val status = runCatching { call("POST", "/api/selfie?t=$token", huge, "image/jpeg").status }.getOrDefault(413)
        assertEquals(413, status)
        assertEquals(0, lobby.playerFor(token)!!.selfieVersion)
    }

    @Test
    fun theEventStreamSendsTheRoomOnEveryChange() {
        val token = join("Ana")
        val connection = URI("http://127.0.0.1:$port/api/events?t=$token").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 5000
        assertEquals("text/event-stream; charset=utf-8", connection.contentType)
        val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
        fun nextData() = generateSequence { reader.readLine() }.first { it.startsWith("data: ") }.removePrefix("data: ")

        val first = nextData()
        assertContains(first, "\"phase\":\"LOBBY\"")
        // The stream opening marks Ana as connected before her first event goes out.
        assertContains(first, "\"name\":\"Ana\",\"colour\":0,\"selfie\":0,\"connected\":true")
        assertTrue(lobby.playerFor(token)!!.connected)

        val rui = join("Rui")
        assertContains(nextData(), "\"name\":\"Rui\"")

        connection.disconnect()
        // The server notices when a write fails, which can take a write or two after the phone has gone.
        val deadline = System.currentTimeMillis() + 3000
        var i = 0
        while (lobby.playerFor(token)!!.connected && System.currentTimeMillis() < deadline) {
            lobby.rename(rui, "Rui${i++}")
            Thread.sleep(50)
        }
        assertTrue(!lobby.playerFor(token)!!.connected)
    }

    @Test
    fun anHttpsAttemptIsClosedAtOnceAndPlainHttpStillWorks() {
        // The start of a TLS ClientHello, as a browser trying https:// on this port sends.
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().apply {
                write(byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, 0xFC.toByte(), 0x03, 0x03))
                flush()
            }
            val started = System.currentTimeMillis()
            // Closed by the server: end of stream, not a timeout.
            assertEquals(-1, socket.getInputStream().read())
            assertTrue(System.currentTimeMillis() - started < 1000)
        }
        assertEquals(200, call("GET", "/").status)
    }

    @Test
    fun aClientThatKeepsSendingAfterItsRequestStillGetsTheWholeResponse() {
        // Extra bytes after the request used to be left unread, and closing then sent a reset.
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().apply {
                write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                flush()
                Thread.sleep(100)
                write("GET /app.js HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                flush()
            }
            val response = String(socket.getInputStream().readBytes())
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.endsWith("<h1>hi</h1>"))
        }
    }

    @Test
    fun requestsAreLoggedWithoutTokens() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val logged = WebServer(lobby, log = { lines += it }) { pages[it]?.toByteArray() }
        val loggedPort = logged.start(0)
        try {
            val connection = URI("http://127.0.0.1:$loggedPort/api/me?t=secret").toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "TestPhone/1.0")
            assertEquals(401, connection.responseCode)
            val deadline = System.currentTimeMillis() + 2000
            while (lines.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals("127.0.0.1: GET /api/me (TestPhone/1.0)", lines.single())
        } finally {
            logged.stop()
        }
    }

    private val deck = Deck(
        listOf(Item("v1", "Who sings?")),
        listOf(SpotlightItem("s1", "{name} at a party?", listOf("a", "b", "c", "d"))),
        listOf(Item("d1", "Draw {name} as a cat")),
    )

    @Test
    fun playingThroughTheServer() {
        val ana = join("Ana")
        val rui = join("Rui")
        val eva = join("Eva")
        val ids = listOf(ana, rui, eva).map { lobby.idFor(it)!! }
        assertTrue(lobby.start(deck, now = System.currentTimeMillis()))

        val state = call("GET", "/api/me?t=$ana").text
        assertContains(state, "\"phase\":\"GAME\"")
        assertContains(state, "\"stage\":\"VOTE\"")
        assertContains(state, "\"question\":\"Who sings?\"")
        assertContains(state, "\"voted\":null")

        assertEquals(204, call("POST", "/api/vote?t=$ana", form("target" to ids[1].toString())).status)
        assertContains(call("GET", "/api/me?t=$ana").text, "\"voted\":${ids[1]}")
        assertEquals(409, call("POST", "/api/vote?t=$ana", form("target" to "999")).status)
        assertEquals(409, call("POST", "/api/answer?t=$ana", form("option" to "1")).status)
        assertEquals(401, call("POST", "/api/vote?t=nope", form("target" to ids[1].toString())).status)
    }

    @Test
    fun drawingsGoUpAndShowInTheGallery() {
        val tokens = listOf(join("Ana"), join("Rui"), join("Eva"))
        val ids = tokens.map { lobby.idFor(it)!! }
        lobby.start(deck, now = 0)
        // Skip to the spotlight turn's drawing stage.
        while (lobby.room.value.game!!.stage != Stage.DRAW) lobby.skip(0)
        val spotlight = lobby.room.value.game!!.spotlightId!!
        val name = lobby.room.value.player(spotlight)!!.name
        val drawers = tokens.filter { lobby.idFor(it) != spotlight }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), '%'.code.toByte(), 0xE0.toByte(), 7)
        assertContains(call("GET", "/api/me?t=${drawers[0]}").text, "\"question\":\"Draw $name as a cat\"")
        drawers.forEach { assertEquals(204, call("POST", "/api/drawing?t=$it", jpeg, "image/jpeg").status) }
        val spotlightToken = tokens.first { lobby.idFor(it) == spotlight }
        assertEquals(409, call("POST", "/api/drawing?t=$spotlightToken", jpeg, "image/jpeg").status)

        lobby.skip(0)
        assertEquals(Stage.PICK, lobby.room.value.game!!.stage)
        val drawer = lobby.idFor(drawers[0])!!
        assertContentEquals(jpeg, call("GET", "/drawing/$drawer?t=${drawers[1]}&s=1").body)
        assertEquals(403, call("GET", "/drawing/$drawer?t=wrong").status)
        assertEquals(409, call("POST", "/api/pick?t=${drawers[0]}", form("drawer" to drawer.toString())).status)
        assertEquals(204, call("POST", "/api/pick?t=$spotlightToken", form("drawer" to drawer.toString())).status)
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun anUnknownTokenGetsNoStream() {
        assertEquals(401, call("GET", "/api/events?t=nope").status)
        assertEquals(401, call("GET", "/api/me?t=nope").status)
    }

    @Test
    fun jsonStringsAreEscaped() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", WebServer.jsonString("a\"b\\c\n"))
    }

    @Test
    fun thePageTakesTheTvsLanguage() {
        assertEquals("<html lang=\"en\"><h1>hi</h1>", call("GET", "/").text)
        val pt = WebServer(lobby, language = Language.PT) { pages[it]?.toByteArray() }
        val ptPort = pt.start(0)
        try {
            val page = java.net.URI("http://localhost:$ptPort/").toURL().readText()
            assertEquals("<html lang=\"pt\"><h1>hi</h1>", page)
        } finally {
            pt.stop()
        }
    }
}
