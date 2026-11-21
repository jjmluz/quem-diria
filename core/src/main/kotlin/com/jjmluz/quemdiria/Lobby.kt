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
    /** The Google Photos person the TV linked them to (that person's key there), or null. */
    val person: String? = null,
)

enum class Phase { LOBBY, GAME }

/** Everything the TV and the phones show about the room. A new value on every change. */
data class Room(
    val players: List<Player>,
    val phase: Phase,
    val mode: Mode = Mode.FAMILY,
    /** The game being played, or null in the lobby. */
    val game: Game? = null,
    /** The last game's pictures, for the phones to save, from its end until the next game starts. */
    val keepsake: Keepsake? = null,
) {
    val canStart: Boolean get() = phase == Phase.LOBBY && players.size >= Lobby.MIN_PLAYERS
    /** Every player is linked to a person in Google Photos, so the game has photo questions. */
    val allLinked: Boolean get() = players.isNotEmpty() && players.all { it.person != null }
    fun player(id: Int?): Player? = players.firstOrNull { it.id == id }
}

/**
 * A finished game's pictures: its [drawings], and the TV's [finalScreen] as a JPEG once the TV has drawn it.
 * [id] is the game's last stage number, which names it in the pictures' addresses.
 */
class Keepsake(val id: Int, val drawings: List<Drawing>, val finalScreen: ByteArray? = null)

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
class Lobby(private val maxPlayers: Int = MAX_PLAYERS, private val robotRandom: Random = Random.Default) {
    /** [robotDrawing] is set for a robot: a test player with no phone, who answers by itself (see [addRobot]). */
    private class Seat(val token: String, var player: Player, var streams: Int = 0, val robotDrawing: ByteArray? = null)

    private val seats = mutableListOf<Seat>()
    private var nextId = 1
    private var phase = Phase.LOBBY
    private var mode = Mode.FAMILY
    private var game: Game? = null
    private var keepsake: Keepsake? = null
    private val random = SecureRandom()

    /** The last stage number of any game here, so the next game's numbers carry on from it. */
    private var lastSerial = 0
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

    /**
     * Adds a test player with no phone, for trying the game on the TV without three phones. It counts as
     * connected, and answers every stage at random the moment it opens; its drawing is always [picture],
     * which is its selfie too. It stays until the app closes. A name already taken gets a number added.
     */
    @Synchronized
    fun addRobot(name: String, picture: ByteArray): JoinResult {
        if (phase != Phase.LOBBY) return JoinResult.Started
        if (seats.size >= maxPlayers) return JoinResult.Full
        val base = cleanName(name) ?: return JoinResult.BadName
        val free = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "${base.take(MAX_NAME - 3)} $it" })
            .first { !taken(it, except = null) }
        val colour = (0 until maxPlayers).first { c -> seats.none { it.player.colour == c } }
        val player = Player(nextId++, free, colour, picture, 1, connected = true)
        val seat = Seat(newToken(), player, robotDrawing = picture)
        seats += seat
        publish()
        return JoinResult.Joined(seat.token, player.id)
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

    /** Links player [id] to a Google Photos person ([person] is its key), or unlinks them with null. */
    @Synchronized
    fun link(id: Int, person: String?): Boolean {
        val seat = seats.firstOrNull { it.player.id == id } ?: return false
        seat.player = seat.player.copy(person = person)
        publish()
        return true
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

    /**
     * Starts a game from [deck] (questions not played recently first) if there are enough players. [photos]
     * are questions about each player's photos, played only if every player has a list (see [Game.plan]).
     */
    @Synchronized
    fun start(
        deck: Deck, now: Long, random: Random = Random.Default, photos: Map<Int, List<PhotoQuestion>> = emptyMap(),
        spotlights: Spotlights = Spotlights.MODERATE,
    ): Boolean {
        if (!_room.value.canStart) return false
        val ids = seats.map { it.player.id }
        // Numbered on from the last game: phones keep each drawing they load by its stage's number, so
        // starting again from 0 showed them an earlier game's drawings.
        game = Game.start(Game.plan(ids, deck, random, photos, spotlights), now, ids.toSet(), seed = random.nextLong(), serial = lastSerial)
        phase = Phase.GAME
        keepsake = null
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
    fun guess(token: String, value: Int): Boolean = act(token) { g, id -> g.guess(id, value, present()) }

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

    /** Back on the remote: stops the game's clock, and phones show that it's paused. */
    @Synchronized
    fun pause(now: Long) {
        game = game?.pause(now) ?: return
        publish()
    }

    /** Carries on from where [pause] stopped, with the same time left. */
    @Synchronized
    fun resume(now: Long) {
        game = game?.resume(now) ?: return
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

    /** Every robot that hasn't answered this stage yet does, at random. Paused games refuse, and wait. */
    private fun robotsPlay(g: Game): Game {
        val present = present()
        var game = g
        for (seat in seats) {
            val picture = seat.robotDrawing ?: continue
            val id = seat.player.id
            game = when (game.stage) {
                Stage.VOTE -> if (id in game.votes) null else game.vote(id, present.random(robotRandom), present)
                Stage.ANSWER, Stage.PHOTO -> if (id in game.answers) null else game.answer(id, robotRandom.nextInt(4), present)
                Stage.DRAW -> if (id in game.drawings) null else game.draw(id, picture, present)
                Stage.PICK -> {
                    val others = game.drawings.keys - id
                    if (id in game.picks || !game.picksDrawing(id)) null else game.pick(id, others.random(robotRandom), present)
                }
                Stage.TIE -> {
                    val t = game.tie
                    when {
                        t == null || id !in t.contenders || id in game.answers -> null
                        t.question is Memory.Answered -> game.guess(id, robotRandom.nextInt(4), present)
                        else -> t.choices(present).randomOrNull(robotRandom)?.let { game.guess(id, it, present) }
                    }
                }
                else -> null
            } ?: game
        }
        return game
    }

    /** The TV's picture of game [id]'s final screen, for the phones to save with its drawings. */
    @Synchronized
    fun keepFinalScreen(id: Int, jpeg: ByteArray) {
        val k = keepsake?.takeIf { it.id == id } ?: return
        keepsake = Keepsake(k.id, k.drawings, jpeg)
        publish()
    }

    private fun publish() {
        game = game?.let(::robotsPlay)
        game?.let { g ->
            lastSerial = g.serial
            if (g.stage == Stage.END && keepsake?.id != g.serial) keepsake = Keepsake(g.serial, g.gallery)
        }
        _room.value = Room(seats.map { it.player }, phase, mode, game, keepsake)
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
    person: String? = this.person,
) = Player(id, name, colour, selfie, selfieVersion, connected, person)
