package com.jjmluz.quemdiria

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import kotlin.random.Random

private const val TAG = "QuemDiria"

/** Where the phones find the TV: the address to show, and the full link for the QR code. */
data class Invite(val host: String) {
    val url: String get() = "http://$host/"
}

/** The lobby's buttons along the bottom, picked with left and right. */
enum class LobbyButton { MODE, NARRATOR, START }

/** The pause screen's buttons, picked with left and right. */
enum class PauseButton { CONTINUE, END }

/**
 * Hosts the room for as long as the game is open: the [Lobby], the [WebServer] the phones talk to, the TV's
 * address on the home network (which can change while the app is open), the game's clock, the remote's
 * keys, and the narrator and sounds.
 */
class RoomViewModel(app: Application) : AndroidViewModel(app) {
    val lobby = Lobby()
    val room: StateFlow<Room> = lobby.room

    /** The game's language, questions and phones included: the TV's, when it's Portuguese; English otherwise. */
    val language: Language =
        if (app.resources.configuration.locales[0].language == "pt") Language.PT else Language.EN

    // Requests go to logcat (`adb logcat -s QuemDiria`), to see what a phone that can't load the page sent.
    private val server = WebServer(lobby, log = { Log.i(TAG, it) }, language = language) { name ->
        try {
            app.assets.open("web/$name").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _invite = MutableStateFlow<Invite?>(null)
    /** Null while the TV has no network address, or the server couldn't start. */
    val invite: StateFlow<Invite?> = _invite.asStateFlow()

    private val _button = MutableStateFlow(LobbyButton.START)
    val button: StateFlow<LobbyButton> = _button.asStateFlow()

    private val _narrator = MutableStateFlow(prefs.getBoolean("narrator", true))
    val narratorOn: StateFlow<Boolean> = _narrator.asStateFlow()

    private val _paused = MutableStateFlow<PauseButton?>(null)
    /** Back during a game pauses it; null while playing, else the lit button of the pause screen. */
    val paused: StateFlow<PauseButton?> = _paused.asStateFlow()

    private val narrator = Narrator(app, language)
    private val sounds = Sounds()
    private val music = TitleMusic(app, viewModelScope)
    /** Whether the app is on screen: the music stops when the TV goes elsewhere. */
    private var foreground = false

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var port = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = updateInvite()
        override fun onLost(network: Network) = updateInvite()
    }

    init {
        // Binding a socket is quick, and the screen needs the port before it can show the QR code.
        port = try {
            server.start()
        } catch (e: IOException) {
            Log.e(TAG, "Can't start the web server", e)
            0
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        updateInvite()
        lobby.setMode(if (prefs.getString("mode", null) == Mode.ADULTS.name) Mode.ADULTS else Mode.FAMILY)
        viewModelScope.launch {
            while (true) {
                lobby.tick(System.currentTimeMillis())
                delay(200)
            }
        }
        viewModelScope.launch { room.collect(::react) }
    }

    private fun updateInvite() {
        val props = connectivity.getLinkProperties(connectivity.activeNetwork)
        val ip = props?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        _invite.value = if (ip != null && port != 0) Invite("${ip.hostAddress}:$port") else null
    }

    private var lastSerial = -1
    private var lastAnswers = 0

    /** Sounds and the narrator, on each new stage and on each answer coming in. */
    private fun react(room: Room) {
        updateMusic()
        val game = room.game
        if (game == null) {
            lastSerial = -1
            narrator.stop()
            return
        }
        val answers = game.votes.size + game.answers.size + game.drawings.size + game.picks.size
        if (game.serial != lastSerial) {
            lastSerial = game.serial
            onStage(room, game)
        } else if (answers > lastAnswers) {
            sounds.play(Sounds.Kind.ANSWER)
        }
        lastAnswers = answers
    }

    private fun onStage(room: Room, game: Game) {
        val name = room.player(game.spotlightId)?.name.orEmpty()
        val step = game.step
        val say = _narrator.value
        when (game.stage) {
            Stage.VOTE -> {
                sounds.play(Sounds.Kind.START)
                if (say && step is Step.Vote) narrator.say(step.question.text)
            }
            Stage.ANSWER -> {
                sounds.play(Sounds.Kind.START)
                if (say && step is Step.Spotlight) narrator.say(step.question.text.withName(name))
            }
            Stage.DRAW -> {
                sounds.play(Sounds.Kind.START)
                if (say && step is Step.Spotlight) narrator.say(step.prompt.text.withName(name))
            }
            Stage.PICK -> sounds.play(Sounds.Kind.START)
            Stage.VOTE_REVEAL, Stage.ANSWER_REVEAL, Stage.PICK_REVEAL -> sounds.play(Sounds.Kind.REVEAL)
            Stage.FINAL_INTRO, Stage.SCORES -> sounds.play(Sounds.Kind.REVEAL)
            Stage.END -> sounds.play(Sounds.Kind.FANFARE)
        }
    }

    /** The activity is on screen (true) or not: the music plays only while it is. */
    fun onForeground(shown: Boolean) {
        foreground = shown
        updateMusic()
    }

    /** Music in the room only: the game has the narrator and its own sounds. */
    private fun updateMusic() = music.play(foreground && room.value.phase == Phase.LOBBY)

    /** What the remote's keys do. Returns true for a key it used. */
    fun onKeyDown(keyCode: Int): Boolean {
        val room = room.value
        val ok = keyCode in OK_KEYS
        val back = keyCode == KeyEvent.KEYCODE_BACK
        if (room.phase == Phase.LOBBY) {
            return when {
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> move(1)
                ok -> {
                    press(_button.value)
                    true
                }
                else -> false
            }
        }
        val game = room.game ?: return false
        return when {
            _paused.value != null -> onPausedKey(keyCode, ok, back)
            back -> {
                // Continue is lit first, so an OK pressed by habit doesn't end the game.
                _paused.value = PauseButton.CONTINUE
                lobby.pause(System.currentTimeMillis())
                narrator.stop()
                true
            }
            ok && game.stage == Stage.END -> {
                lobby.backToLobby()
                true
            }
            ok -> {
                lobby.skip(System.currentTimeMillis())
                true
            }
            else -> false
        }
    }

    /** On the pause screen: left and right pick, OK presses, and Back carries on as Continue does. */
    private fun onPausedKey(keyCode: Int, ok: Boolean, back: Boolean): Boolean {
        when {
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> _paused.value = PauseButton.CONTINUE
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> _paused.value = PauseButton.END
            back || (ok && _paused.value == PauseButton.CONTINUE) -> {
                _paused.value = null
                lobby.resume(System.currentTimeMillis())
            }
            ok -> {
                _paused.value = null
                lobby.backToLobby()
            }
            // Other keys (up, down...) do nothing, but are still the pause screen's.
        }
        return true
    }

    private fun move(by: Int): Boolean {
        val all = LobbyButton.entries
        _button.value = all[(_button.value.ordinal + by).coerceIn(0, all.lastIndex)]
        return true
    }

    private fun press(button: LobbyButton) {
        when (button) {
            LobbyButton.MODE -> {
                val next = if (room.value.mode == Mode.FAMILY) Mode.ADULTS else Mode.FAMILY
                lobby.setMode(next)
                prefs.edit().putString("mode", next.name).apply()
            }
            LobbyButton.NARRATOR -> {
                _narrator.value = !_narrator.value
                prefs.edit().putBoolean("narrator", _narrator.value).apply()
                if (_narrator.value) narrator.say(getApplication<Application>().getString(R.string.narrator_hello))
            }
            LobbyButton.START -> startGame()
        }
    }

    /** Starts a game, preferring questions this TV hasn't played lately; once all have been, starts over. */
    private fun startGame() {
        if (!room.value.canStart) return
        val mode = room.value.mode
        val file = if (mode == Mode.ADULTS) "decks/adults.txt" else "decks/family.txt"
        val deck = try {
            Deck.parse(getApplication<Application>().assets.open(file).bufferedReader().readText(), language)
        } catch (e: Exception) {
            Log.e(TAG, "Can't read $file", e)
            return
        }
        val key = "used_${mode.name}"
        var used = prefs.getStringSet(key, emptySet())!!
        val random = Random(System.nanoTime())
        fun <T> fresh(items: List<T>, id: (T) -> String): List<T> {
            val (old, new) = items.partition { id(it) in used }
            return new.shuffled(random) + old.shuffled(random)
        }
        // Every list played through: forget and start again.
        if (deck.votes.all { it.id in used }) used = emptySet()
        val ordered = Deck(
            fresh(deck.votes) { it.id },
            fresh(deck.spotlights) { it.id },
            fresh(deck.drawings) { it.id },
        )
        if (!lobby.start(ordered, System.currentTimeMillis(), random)) return
        val played = lobby.room.value.game!!.steps.flatMap {
            when (it) {
                is Step.Vote -> listOf(it.question.id)
                is Step.Spotlight -> listOf(it.question.id, it.prompt.id)
                else -> emptyList()
            }
        }
        prefs.edit().putStringSet(key, used + played).apply()
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
        server.stop()
        narrator.shutdown()
        sounds.release()
        music.release()
    }

    private companion object {
        val OK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        )
    }
}
