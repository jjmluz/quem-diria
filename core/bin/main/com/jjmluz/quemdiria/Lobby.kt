package com.jjmluz.quemdiria

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import kotlin.random.Random

/** One person in the room, joined from their phone. */
class Player(
    val id: Int,
    val name: String,
    /** Index into the game's player colours; the smallest one free when they joined. */
    val colour: Int,
    /** A square JPEG from the phone, or null until they take one. */
    val selfie: ByteArray?,
    /** Goes up with each new selfie, so the TV and the phones know to fetch it again. */
    val selfieVersion: Int,
    /** True while their phone has the page open (an event stream is connected). */
    val connected: Boolean,
)

enum class Phase { LOBBY, GAME }

/** Everything the TV and the phones show about the room. A new value on every change. */
data class Room(
    val players: List<Player>,
    val phase: Phase,
    val mode: Mode = Mode.FAMILY,
    /** The game being played, or null in the lobby. */
    val game: Game? = null,
) {
    val canStart: Boolean get() = phase == Phase.LOBBY && players.size >= Lobby.MIN_PLAYERS
    fun player(id: Int?): Player? = players.firstOrNull { it.id == id }
}

sealed interface JoinResult {
    data class Joined(val token: String, val id: Int) : JoinResult
    data object Full : JoinResult
    data object Started : JoinResult
    data object BadName : JoinResult
    data object NameTaken : JoinResult
}

/**
 * The players in the room and the game's phase. Phones act through a secret token handed out on [join],
 * so one phone can't rename or remove another player. Safe to call from the server's threads.
 */
class Lobby(private val maxPlayers: Int = MAX_PLAYERS) {
    private class Seat(val token: String, var player: Player, var streams: Int = 0)

    private val seats = mutableListOf<Seat>()
    private var nextId = 1
    private var phase = Phase.LOBBY
    private var mode = Mode.FAMILY
    private var game: Game? = null
    private val random = SecureRandom()

    private val _room = MutableStateFlow(Room(emptyList(), Phase.LOBBY))
    val room: StateFlow<Room> = _room.asStateFlow()

    @Synchronized
    fun join(name: String): JoinResult {
        val clean = cleanName(name) ?: return JoinResult.BadName
        if (phase != Phase.LOBBY) return JoinResult.Started
        if (seats.size >= maxPlayers) return JoinResult.Full
        if (taken(clean, except = null)) return JoinResult.NameTaken
        val colour = (0 until maxPlayers).first { c -> seats.none { it.player.colour == c } }
        val seat = Seat(newToken(), Player(nextId++, clean, colour, null, 0, connected = false))
        seats += seat
        publish()
        return JoinResult.Joined(seat.token, seat.player.id)
    }

    /** The player holding [token], or null if there's none (the TV restarted, or they left). */
    @Synchronized
    fun playerFor(token: String): Player? = seat(token)?.player

    /** Renames the player; false if the name is empty, too long or someone else's. */
    @Synchronized
    fun rename(token: String, name: String): Boolean {
        val seat = seat(token) ?: return false
        val clean = cleanName(name) ?: return false
        if (taken(clean, except = seat)) return false
        seat.player = seat.player.copy(name = clean)
        publish()
        return true
    }

    @Synchronized
    fun setSelfie(token: String, jpeg: ByteArray): Boolean {
        val seat = seat(token) ?: return false
        seat.player = seat.player.copy(selfie = jpeg, selfieVersion = seat.player.selfieVersion + 1)
        publish()
        return true
    }

    @Synchronized
    fun leave(token: String): Boolean {
        val seat = seat(token) ?: return false
        seats -= seat
        publish()
        return true
    }

    /** A phone opened ([open] true) or lost its event stream. A phone may briefly have two while it reconnects. */
    @Synchronized
    fun streamChanged(token: String, open: Boolean) {
        val seat = seat(token) ?: return
        seat.streams = (seat.streams + if (open) 1 else -1).coerceAtLeast(0)
        val connected = seat.streams > 0
        if (connected != seat.player.connected) {
            seat.player = seat.player.copy(connected = connected)
            publish()
        }
    }

    /** The player holding [token], for the server to say who "you" are. */
    @Synchronized
    fun idFor(token: String): Int? = seat(token)?.player?.id

    @Synchronized
    fun setMode(mode: Mode) {
        if (phase != Phase.LOBBY) return
        this.mode = mode
        publish()
    }

    /** Starts a game from [deck] (questions not played recently first) if there are enough players. */
    @Synchronized
    fun start(deck: Deck, now: Long, random: Random = Random.Default): Boolean {
        if (!_room.value.canStart) return false
        val ids = seats.map { it.player.id }
        game = Game.start(Game.plan(ids, deck, random), now, ids.toSet(), seed = random.nextLong())
        phase = Phase.GAME
        publish()
        return true
    }

    /** Back to the lobby, keeping everyone. */
    @Synchronized
    fun backToLobby() {
        phase = Phase.LOBBY
        game = null
        publish()
    }

    @Synchronized
    fun vote(token: String, target: Int): Boolean = act(token) { g, id -> g.vote(id, target, present()) }

    @Synchronized
    fun answer(token: String, option: Int): Boolean = act(token) { g, id -> g.answer(id, option, present()) }

    @Synchronized
    fun draw(token: String, jpeg: ByteArray): Boolean = act(token) { g, id -> g.draw(id, jpeg, present()) }

    @Synchronized
    fun pick(token: String, drawer: Int): Boolean = act(token) { g, id -> g.pick(id, drawer, present()) }

    /** Called often by the TV: moves the game on when time is up or everyone has answered. */
    @Synchronized
    fun tick(now: Long) {
        val next = game?.tick(now, active(), present()) ?: return
        game = next
        publish()
    }

    /** OK on the remote during a game: stops waiting, or skips a reveal. */
    @Synchronized
    fun skip(now: Long) {
        val next = game?.skip(now, present()) ?: return
        game = next
        publish()
    }

    /** Pushes the stage's end back by [ms], after the game was paused for that long. */
    @Synchronized
    fun postpone(ms: Long) {
        val g = game ?: return
        if (g.deadline == 0L) return
        game = g.copy(deadline = g.deadline + ms)
        publish()
    }

    private fun act(token: String, action: (Game, Int) -> Game?): Boolean {
        val g = game ?: return false
        val id = seat(token)?.player?.id ?: return false
        game = action(g, id) ?: return false
        publish()
        return true
    }

    private fun present() = seats.map { it.player.id }.toSet()
    private fun active() = seats.filter { it.player.connected }.map { it.player.id }.toSet()

    private fun seat(token: String) = seats.firstOrNull { it.token == token }

    private fun taken(name: String, except: Seat?) =
        seats.any { it !== except && it.player.name.equals(name, ignoreCase = true) }

    private fun publish() {
        _room.value = Room(seats.map { it.player }, phase, mode, game)
    }

    private fun newToken(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MIN_PLAYERS = 3
        const val MAX_PLAYERS = 8
        const val MAX_NAME = 14

        /** The name trimmed with inner spaces collapsed, or null if it's empty or longer than [MAX_NAME]. */
        fun cleanName(name: String): String? {
            val clean = name.trim().replace(Regex("\\s+"), " ").filter { !it.isISOControl() }
            return clean.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_NAME }
        }
    }
}

private fun Player.copy(
    name: String = this.name,
    selfie: ByteArray? = this.selfie,
    selfieVersion: Int = this.selfieVersion,
    connected: Boolean = this.connected,
) = Player(id, name, colour, selfie, selfieVersion, connected)
