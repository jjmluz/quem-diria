package com.jjmluz.quemdiria

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The web server on the TV that the phones play through. A phone opens the page from the QR code
 * (`http://<tv>:<port>/`) in its browser, so nobody installs anything.
 *
 * Phones send what they do as small POSTs and hear about every change to the room on one long-lived
 * server-sent event stream (`/api/events`), which browsers reconnect by themselves when a phone wakes up.
 * Plain HTTP/1.1 with one request per connection is plenty for eight phones on a home network, and keeps
 * the TV app free of a server library.
 *
 * Anyone who opens the address can join: it's a home network, and asking for a room code only got in
 * the way. Everything after joining uses the player's secret token, the photos and drawings too, so
 * only players in the room can see them.
 *
 * @param asset the bytes of a file of the phone page (`index.html`, `app.js`...), or null if there's none.
 * @param log where each request and refused connection is reported, for finding out what a phone sent.
 */
class WebServer(
    private val lobby: Lobby,
    private val log: (String) -> Unit = {},
    /** The same clock the game's deadlines use, to tell phones how long a stage has left. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** The TV's language. Phones show their words in it too, so the whole room plays in one language. */
    private val language: Language = Language.EN,
    private val asset: (String) -> ByteArray?,
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())

    /** The port it listens on, once [start]ed. */
    var port: Int = 0
        private set

    /**
     * Listens on the first free port from [first] (0 picks any, for tests). Returns the port.
     * @throws IOException if none of the ten ports from [first] is free.
     */
    fun start(first: Int = DEFAULT_PORT): Int {
        val socket = bind(first)
        server = socket
        port = socket.localPort
        val threads = Executors.newCachedThreadPool { r -> Thread(r, "quemdiria-web").apply { isDaemon = true } }
        pool = threads
        threads.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                clients += client
                threads.execute {
                    try {
                        client.use {
                            handle(it)
                            closeGently(it)
                        }
                    } catch (e: Exception) {
                        // One bad request must never take the TV's game down with it.
                        log("request failed: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        clients -= client
                    }
                }
            }
        }
        return port
    }

    /** Closes the server and every phone's connection, which ends their event streams. */
    fun stop() {
        runCatching { server?.close() }
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        pool?.shutdownNow()
        server = null
    }

    private fun bind(first: Int): ServerSocket {
        val ports = if (first == 0) listOf(0) else (first until first + 10).toList()
        var last: IOException? = null
        for (p in ports) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(p))
                return socket
            } catch (e: IOException) {
                socket.close()
                last = e
            }
        }
        throw last ?: IOException("no port")
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val out = socket.getOutputStream()
        val input = BufferedInputStream(socket.getInputStream())
        val from = socket.inetAddress?.hostAddress
        val request = try {
            // Phone browsers often try https:// first (Chrome's "Always use secure connections"). Waiting for
            // an HTTP request line that never comes made Chrome retry until it failed with
            // ERR_TOO_MANY_RETRIES. Closing at once on a TLS handshake (it starts with byte 0x16) makes it
            // fall back to http:// straight away.
            input.mark(1)
            when (input.read()) {
                -1 -> return log("$from: connected, then closed without a request")
                TLS_HANDSHAKE -> return log("$from: https attempt, closed")
                else -> input.reset()
            }
            readRequest(input)
        } catch (e: TooLarge) {
            return respond(out, 413, "{\"error\":\"too_large\"}")
        } catch (e: IOException) {
            // Includes a phone that connected but sent nothing for 15 s.
            return log("$from: no request (${e.javaClass.simpleName}: ${e.message})")
        } catch (e: RuntimeException) {
            return respond(out, 400, "")
        }
        log("$from: ${request.method} ${request.path} (${request.userAgent.take(120)})")
        val token = request.query["t"].orEmpty()
        when {
            request.method == "GET" && request.path == "/api/events" -> {
                socket.soTimeout = 0
                events(out, token)
            }
            request.method == "GET" && request.path == "/api/me" ->
                lobby.playerFor(token)?.let { respond(out, 200, stateJson(lobby.room.value, it.id, clock())) }
                    ?: respond(out, 401, "")
            request.method == "POST" && request.path in ACTIONS -> {
                if (lobby.playerFor(token) == null) return respond(out, 401, "")
                // A drawing's body is a JPEG, not a form: only read forms for the other actions.
                val form = if (request.path == "/api/drawing") emptyMap() else request.form()
                val done = when (request.path) {
                    "/api/vote" -> form["target"]?.toIntOrNull()?.let { lobby.vote(token, it) }
                    "/api/answer" -> form["option"]?.toIntOrNull()?.let { lobby.answer(token, it) }
                    "/api/pick" -> form["drawer"]?.toIntOrNull()?.let { lobby.pick(token, it) }
                    else -> if (isJpeg(request.body)) lobby.draw(token, request.body) else null
                }
                // 409: too late (the stage moved on) or not allowed, such as picking your own drawing.
                respond(out, if (done == true) 204 else 409, "")
            }
            request.method == "GET" && request.path.startsWith("/drawing/") -> drawing(out, request)
            request.method == "POST" && request.path == "/api/join" -> join(out, request.form())
            request.method == "POST" && request.path == "/api/name" -> {
                if (lobby.playerFor(token) == null) return respond(out, 401, "")
                val name = request.form()["name"].orEmpty()
                when {
                    lobby.rename(token, name) -> respond(out, 204, "")
                    Lobby.cleanName(name) == null -> respond(out, 409, "{\"error\":\"name\"}")
                    else -> respond(out, 409, "{\"error\":\"taken\"}")
                }
            }
            request.method == "POST" && request.path == "/api/selfie" -> {
                val image = request.body
                when {
                    !isJpeg(image) -> respond(out, 415, "")
                    lobby.setSelfie(token, image) -> respond(out, 204, "")
                    else -> respond(out, 401, "")
                }
            }
            request.method == "POST" && request.path == "/api/leave" ->
                respond(out, if (lobby.leave(token)) 204 else 401, "")
            request.method == "GET" && request.path.startsWith("/selfie/") -> selfie(out, request)
            request.method == "GET" -> page(out, request.path)
            else -> respond(out, 405, "")
        }
    }

    private fun join(out: OutputStream, form: Map<String, String>) {
        when (val result = lobby.join(form["name"].orEmpty())) {
            is JoinResult.Joined -> respond(out, 200, "{\"token\":\"${result.token}\",\"id\":${result.id}}")
            JoinResult.BadName -> respond(out, 409, "{\"error\":\"name\"}")
            JoinResult.NameTaken -> respond(out, 409, "{\"error\":\"taken\"}")
            JoinResult.Full -> respond(out, 409, "{\"error\":\"full\"}")
            JoinResult.Started -> respond(out, 409, "{\"error\":\"started\"}")
        }
    }

    /**
     * The room as server-sent events: the whole state on connect and again on every change, plus a
     * comment every [HEARTBEAT_MS] so a phone that went away is noticed (the write fails).
     */
    private fun events(out: OutputStream, token: String) {
        val player = lobby.playerFor(token) ?: return respond(out, 401, "")
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.flush()
        lobby.streamChanged(token, open = true)
        try {
            // Both coroutines run on this one thread, so their writes never interleave.
            runBlocking {
                launch {
                    while (true) {
                        delay(HEARTBEAT_MS)
                        out.write(":\n\n".toByteArray())
                        out.flush()
                    }
                }
                lobby.room.collect { room ->
                    if (room.players.none { it.id == player.id }) {
                        // They left (from another tab): tell the page, then end the stream.
                        out.write("event: gone\ndata: {}\n\n".toByteArray())
                        out.flush()
                        throw SocketException("left")
                    }
                    out.write("data: ${stateJson(room, player.id, clock())}\n\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        } catch (e: IOException) {
            // The phone went away or the server stopped.
        } finally {
            lobby.streamChanged(token, open = false)
        }
    }

    private fun selfie(out: OutputStream, request: Request) {
        if (lobby.playerFor(request.query["t"].orEmpty()) == null) return respond(out, 403, "")
        val id = request.path.removePrefix("/selfie/").toIntOrNull()
        val jpeg = lobby.room.value.players.firstOrNull { it.id == id }?.selfie ?: return respond(out, 404, "")
        // The URL carries the selfie's version, so a new selfie gets a new URL and this one never changes.
        respond(out, 200, jpeg, "image/jpeg", "Cache-Control: max-age=86400\r\n")
    }

    /** A drawing of the current spotlight turn, for the phones' gallery. Anonymous until the reveal. */
    private fun drawing(out: OutputStream, request: Request) {
        if (lobby.playerFor(request.query["t"].orEmpty()) == null) return respond(out, 403, "")
        val id = request.path.removePrefix("/drawing/").toIntOrNull()
        val jpeg = lobby.room.value.game?.drawings?.get(id) ?: return respond(out, 404, "")
        // The URL carries the stage's serial, so the same address never means another drawing.
        respond(out, 200, jpeg, "image/jpeg", "Cache-Control: max-age=3600\r\n")
    }

    private fun page(out: OutputStream, path: String) {
        val name = if (path == "/") "index.html" else path.removePrefix("/")
        if (name.contains("..") || name.contains('/')) return respond(out, 404, "")
        var bytes = asset(name) ?: return respond(out, 404, "")
        if (name == "index.html") {
            // The page reads its language from <html lang>, so it matches the TV and not the phone.
            val lang = language.name.lowercase()
            bytes = String(bytes, Charsets.UTF_8).replaceFirst("<html>", "<html lang=\"$lang\">").toByteArray()
        }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
        // The page changes only with a new version of the app; no-cache makes the phone check each time.
        respond(out, 200, bytes, type, "Cache-Control: no-cache\r\n")
    }

    private fun respond(out: OutputStream, status: Int, json: String) =
        respond(out, status, json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    private fun respond(out: OutputStream, status: Int, body: ByteArray, type: String, extra: String = "") {
        val head = "HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\n" +
            (if (body.isNotEmpty()) "Content-Type: $type\r\n" else "") +
            "Content-Length: ${body.size}\r\n" + extra +
            "Connection: close\r\n\r\n"
        try {
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        } catch (e: IOException) {
            // The phone gave up waiting.
        }
    }

    /**
     * Ends a finished connection without a reset: says we're done sending, then reads whatever the phone
     * still sends before closing. Closing with unread bytes waiting makes the system send a reset, which
     * can cut off the response the phone hasn't read yet.
     */
    private fun closeGently(socket: Socket) {
        if (socket.isClosed) return
        try {
            socket.shutdownOutput()
            socket.soTimeout = 1000
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < MAX_DRAIN) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
        } catch (e: IOException) {
            // Timed out or already gone: close anyway.
        }
    }

    private class TooLarge : IOException()

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val userAgent: String,
        val body: ByteArray,
    ) {
        fun form(): Map<String, String> = parseQuery(String(body, Charsets.UTF_8))
    }

    private fun readRequest(input: InputStream): Request {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("incomplete headers")
            if (head.length > MAX_HEAD) throw TooLarge()
            head.append(b.toChar())
        }
        val lines = head.lines()
        val (method, target) = lines.first().split(" ").let { it[0] to it[1] }
        val length = lines.firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt()
        } ?: 0
        if (length > MAX_BODY) throw TooLarge()
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("body cut short")
            read += n
        }
        val userAgent = lines.firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith("User-Agent:", ignoreCase = true) }?.substringAfter(':')?.trim()
        }.orEmpty()
        return Request(method, target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), userAgent, body)
    }

    companion object {
        const val DEFAULT_PORT = 8642
        private val ACTIONS = setOf("/api/vote", "/api/answer", "/api/drawing", "/api/pick")
        const val HEARTBEAT_MS = 15_000L
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_DRAIN = 64 * 1024
        private const val TLS_HANDSHAKE = 0x16
        /** A phone sends a 480-pixel JPEG of a few dozen KB; this leaves room for a bigger one. */
        private const val MAX_BODY = 2 * 1024 * 1024
        private val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 413 to "Payload Too Large",
            415 to "Unsupported Media Type",
        )

        private fun isJpeg(bytes: ByteArray) =
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

        private fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(key) to decode(value)
            }

        /** Percent-decoding that leaves a malformed escape as it is instead of throwing. */
        private fun decode(text: String): String = try {
            URLDecoder.decode(text, "UTF-8")
        } catch (e: IllegalArgumentException) {
            text
        }

        /**
         * What a phone sees: the room, which player it is and, during a game, the stage from its point of
         * view. Selfies go by version (0: none yet). `remaining` is the stage's time left when this was sent.
         */
        fun stateJson(room: Room, you: Int, now: Long): String = buildString {
            append("{\"phase\":\"").append(room.phase.name).append("\",\"you\":").append(you)
            append(",\"mode\":\"").append(room.mode.name).append('"')
            append(",\"canStart\":").append(room.canStart)
            append(",\"min\":").append(Lobby.MIN_PLAYERS).append(",\"max\":").append(Lobby.MAX_PLAYERS)
            append(",\"players\":[")
            room.players.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("{\"id\":").append(p.id).append(",\"name\":").append(jsonString(p.name))
                append(",\"colour\":").append(p.colour).append(",\"selfie\":").append(p.selfieVersion)
                append(",\"connected\":").append(p.connected).append('}')
            }
            append("]")
            room.game?.let { append(",\"game\":"); gameJson(it, room, you, now) }
            append("}")
        }

        private fun StringBuilder.gameJson(game: Game, room: Room, you: Int, now: Long) {
            val spotlight = game.spotlightId
            val name = room.player(spotlight)?.name.orEmpty()
            val (number, of) = game.voteNumber
            append("{\"stage\":\"").append(game.stage.name).append('"')
            append(",\"serial\":").append(game.serial)
            append(",\"remaining\":").append(if (game.deadline == 0L) 0 else (game.deadline - now).coerceAtLeast(0))
            append(",\"double\":").append(game.double)
            append(",\"number\":").append(number).append(",\"of\":").append(of)
            append(",\"spotlight\":").append(spotlight ?: "null")
            when (val step = game.step) {
                is Step.Vote -> append(",\"question\":").append(jsonString(step.question.text))
                is Step.Spotlight -> {
                    val drawing = game.stage in setOf(Stage.DRAW, Stage.PICK, Stage.PICK_REVEAL)
                    val text = if (drawing) step.prompt.text else step.question.text
                    append(",\"question\":").append(jsonString(text.withName(name)))
                    append(",\"options\":[").append(step.question.options.joinToString(",") { jsonString(it) }).append(']')
                }
                else -> {}
            }
            append(",\"voted\":").append(game.votes[you] ?: "null")
            append(",\"answered\":").append(game.answers[you] ?: "null")
            if (game.stage == Stage.ANSWER_REVEAL) append(",\"truth\":").append(game.answers[spotlight] ?: "null")
            append(",\"drew\":").append(you in game.drawings)
            append(",\"picked\":").append(game.picks[you] ?: "null")
            append(",\"drawers\":[").append(game.galleryOrder.joinToString(",")).append(']')
            append(",\"gained\":").append(game.gained[you] ?: 0)
            val score = game.scores[you] ?: 0
            append(",\"score\":").append(score)
            append(",\"rank\":").append(1 + room.players.count { (game.scores[it.id] ?: 0) > score })
            if (game.stage == Stage.END) {
                val ids = room.players.map { it.id }
                game.awards(ids).firstOrNull { it.playerId == you }?.let {
                    append(",\"award\":{\"question\":").append(it.question?.let(::jsonString) ?: "null")
                    append(",\"votes\":").append(it.votes).append('}')
                }
                append(",\"winners\":[").append(game.winners(ids).joinToString(",")).append(']')
            }
            append('}')
        }

        fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
