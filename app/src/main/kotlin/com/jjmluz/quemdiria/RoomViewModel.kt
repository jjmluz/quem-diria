package com.jjmluz.quemdiria

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

/** The lobby's buttons along the bottom, picked with left and right. PHOTOS shows only with Photos TV installed. */
enum class LobbyButton { MODE, NARRATOR, PHOTOS, START }

/**
 * The screen that links players to people in Google Photos, over the lobby. First a player is picked
 * ([player], an index into the room's players); OK there opens [people] to pick from ([person], where 0 is
 * "not linked" and 1 onwards the people), while [choosing].
 */
data class Linking(
    val player: Int = 0,
    val choosing: Boolean = false,
    val person: Int = 0,
    /** Null while loading. */
    val people: List<PhotosPerson>? = null,
    val problem: LinkProblem? = null,
)

enum class LinkProblem { NOT_ALLOWED, NOT_SIGNED_IN, FAILED, NO_PEOPLE }

/** The pause screen's buttons, picked with left and right. */
enum class PauseButton { CONTINUE, END }

/**
 * Hosts the room for as long as the game is open: the [Lobby], the [WebServer] the phones talk to, the TV's
 * address on the home network (which can change while the app is open), the game's clock, the remote's
 * keys, and the narrator and sounds.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted, for photos looked through in time
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

    val photos = GooglePhotos(app)

    private val _photosInstalled = MutableStateFlow(photos.installed)
    /** Photos TV (with its games provider) is on this TV, so players can be linked to Google Photos. */
    val photosInstalled: StateFlow<Boolean> = _photosInstalled.asStateFlow()

    private val _linking = MutableStateFlow<Linking?>(null)
    /** The linking screen, or null when it's closed. */
    val linking: StateFlow<Linking?> = _linking.asStateFlow()

    private val _faces = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    /** Faces of Google Photos people by their key, loaded as people are listed. */
    val faces: StateFlow<Map<String, ImageBitmap>> = _faces.asStateFlow()

    private val _personNames = MutableStateFlow<Map<String, String>>(prefs.all
        .filterKeys { it.startsWith("person_name:") }
        .mapKeys { it.key.removePrefix("person_name:") }
        .mapValues { it.value.toString() })
    /** Google Photos names by person key, to show who a player is linked to. */
    val personNames: StateFlow<Map<String, String>> = _personNames.asStateFlow()

    private val _gettingPhotos = MutableStateFlow(false)
    /** OK on Start is waiting for the players' photos to be looked through. */
    val gettingPhotos: StateFlow<Boolean> = _gettingPhotos.asStateFlow()

    private val _gamePhotos = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    /** This game's photo questions' pictures, by photo key, as they arrive. */
    val gamePhotos: StateFlow<Map<String, ImageBitmap>> = _gamePhotos.asStateFlow()

    /** Each linked person's photos to ask about, looked through in the background once they're linked. */
    private val facts = mutableMapOf<String, Deferred<List<PhotoFacts>>>()

    private val _askPermission = MutableStateFlow(false)
    /** The activity should ask for [GooglePhotos.PERMISSION]; it answers with [onPermission]. */
    val askPermission: StateFlow<Boolean> = _askPermission.asStateFlow()

    private val music = Music(app, viewModelScope, Music.TITLE, volume = 0.6f)
    /** The game's music, under the narrator and the game's sounds: quieter than the room's. */
    private val gameMusic = Music(app, viewModelScope, Music.GAME, volume = 0.35f)
    private val narrator = Narrator(app, language) { speaking -> gameMusic.duck(speaking) }
    private val sounds = Sounds()

    private val _splash = MutableStateFlow(true)
    /** The splash screen shows when the app opens, until it ends or OK skips it. */
    val splash: StateFlow<Boolean> = _splash.asStateFlow()
    private var splashStarted = false
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
        if (room.phase == Phase.LOBBY) relink(room)
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
            Stage.PHOTO -> {
                sounds.play(Sounds.Kind.START)
                if (say && step is Step.Photo) narrator.say(step.question.text.withName(name))
            }
            Stage.DRAW -> {
                sounds.play(Sounds.Kind.START)
                if (say && step is Step.Spotlight) narrator.say(step.prompt.text.withName(name))
            }
            Stage.PICK -> sounds.play(Sounds.Kind.START)
            Stage.VOTE_REVEAL, Stage.ANSWER_REVEAL, Stage.PICK_REVEAL, Stage.PHOTO_REVEAL -> sounds.play(Sounds.Kind.REVEAL)
            Stage.FINAL_INTRO, Stage.SCORES -> sounds.play(Sounds.Kind.REVEAL)
            Stage.END -> sounds.play(Sounds.Kind.FANFARE)
        }
    }

    /** The activity is on screen (true) or not: the music plays only while it is. */
    fun onForeground(shown: Boolean) {
        foreground = shown
        // Photos TV may have been installed (or removed) meanwhile.
        if (shown) _photosInstalled.value = photos.installed
        updateMusic()
    }

    /** The splash's first frame is on screen: its sound starts with it, once per launch. */
    fun onSplashStart() {
        if (splashStarted) return
        splashStarted = true
        sounds.play(Sounds.Kind.SPLASH)
    }

    /** The splash ran its course (or OK skipped it): on to the room and its music. */
    fun endSplash() {
        if (!_splash.value) return
        _splash.value = false
        updateMusic()
    }

    /**
     * The room's music in the room (after the splash), the game's during the game; only while the app is on
     * screen.
     */
    private fun updateMusic() {
        val shown = foreground && !_splash.value
        music.play(shown && room.value.phase == Phase.LOBBY)
        gameMusic.play(shown && room.value.phase == Phase.GAME)
    }

    /** What the remote's keys do. Returns true for a key it used. */
    fun onKeyDown(keyCode: Int): Boolean {
        if (_splash.value) {
            // Any key skips the splash, its sound too, and does nothing else.
            sounds.stop(Sounds.Kind.SPLASH)
            endSplash()
            return true
        }
        val room = room.value
        val ok = keyCode in OK_KEYS
        val back = keyCode == KeyEvent.KEYCODE_BACK
        if (room.phase == Phase.LOBBY && _linking.value != null) return onLinkingKey(keyCode, ok, back)
        if (room.phase == Phase.LOBBY) {
            if (_gettingPhotos.value) return true
            if (!(ok && _button.value == LobbyButton.START)) robotPresses = 0
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
        val all = LobbyButton.entries.filter { it != LobbyButton.PHOTOS || _photosInstalled.value }
        val at = all.indexOf(_button.value).coerceAtLeast(0)
        _button.value = all[(at + by).coerceIn(0, all.lastIndex)]
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
            LobbyButton.PHOTOS -> openLinking()
            LobbyButton.START -> if (room.value.canStart) startGame() else countRobotPress()
        }
    }

    private var robotPresses = 0
    private var lastRobotPress = 0L

    /**
     * OK pressed five times in a row on "N more players needed" adds two robots, so the game can be tried on
     * the TV with one phone (or none). Presses more than [ROBOT_PRESS_GAP_MS] apart start the count again.
     */
    private fun countRobotPress() {
        val now = SystemClock.elapsedRealtime()
        robotPresses = if (now - lastRobotPress > ROBOT_PRESS_GAP_MS) 1 else robotPresses + 1
        lastRobotPress = now
        if (robotPresses < ROBOT_PRESSES) return
        robotPresses = 0
        val app = getApplication<Application>()
        app.resources.getStringArray(R.array.robot_names).forEachIndexed { i, name ->
            val picture = app.assets.open("robots/bot${i + 1}.jpg").use { it.readBytes() }
            lobby.addRobot(name, picture)
        }
        sounds.play(Sounds.Kind.ANSWER)
    }

    // Linking players to Google Photos -------------------------------------------------------------------

    private fun openLinking() {
        if (room.value.players.isEmpty()) return
        _linking.value = Linking()
        if (!photos.allowed) {
            _askPermission.value = true
            return
        }
        loadPeople()
    }

    /** The TV's answer to asking for [GooglePhotos.PERMISSION]. */
    fun onPermission(granted: Boolean) {
        _askPermission.value = false
        if (granted) loadPeople() else _linking.value = _linking.value?.copy(problem = LinkProblem.NOT_ALLOWED)
    }

    private fun loadPeople() {
        viewModelScope.launch {
            val problem = try {
                val people = photos.people().sortedByDescending { it.count }
                _linking.value = _linking.value?.copy(people = people, problem = if (people.isEmpty()) LinkProblem.NO_PEOPLE else null)
                rememberNames(people)
                loadFaces(people.map { it.key })
                null
            } catch (e: GooglePhotos.NotSignedIn) {
                LinkProblem.NOT_SIGNED_IN
            } catch (e: IOException) {
                Log.w(TAG, "Google Photos people", e)
                LinkProblem.FAILED
            }
            if (problem != null) _linking.value = _linking.value?.copy(problem = problem)
        }
    }

    private fun rememberNames(people: List<PhotosPerson>) {
        _personNames.value = _personNames.value + people.associate { it.key to it.name }
        prefs.edit().apply { people.forEach { putString("person_name:${it.key}", it.name) } }.apply()
    }

    /** Fetches the faces of [keys] not loaded yet, a few at a time, so the first ones on screen show soon. */
    private suspend fun loadFaces(keys: List<String>) {
        keys.filter { it !in _faces.value }.chunked(6).forEach { chunk ->
            val loaded = chunk.map { key -> viewModelScope.async { photos.face(key, FACE_PX)?.let(::decode)?.let { key to it } } }
                .mapNotNull { it.await() }
            _faces.value = _faces.value + loaded
        }
    }

    /** The linking screen's keys: the d-pad moves, OK picks, Back goes back a level and then closes it. */
    private fun onLinkingKey(keyCode: Int, ok: Boolean, back: Boolean): Boolean {
        val l = _linking.value ?: return false
        val players = room.value.players
        if (players.isEmpty()) {
            _linking.value = null
            return true
        }
        val dx = when (keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (keyCode) { KeyEvent.KEYCODE_DPAD_UP -> -1; KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        val player = l.player.coerceIn(0, players.lastIndex)
        if (!l.choosing) {
            when {
                back -> _linking.value = null
                ok && l.people != null && l.problem == null -> {
                    // Start on the person they're linked to, or the first one with their name.
                    val people = l.people
                    val current = players[player].person
                    val match = people.indexOfFirst { it.key == current }.takeIf { it >= 0 }
                        ?: people.indexOfFirst { sameName(it.name, players[player].name) }
                    _linking.value = l.copy(player = player, choosing = true, person = match + 1)
                }
                dx != 0 || dy != 0 -> _linking.value = l.copy(player = (player + dx + dy * LINK_PLAYER_COLUMNS).coerceIn(0, players.lastIndex))
            }
            return true
        }
        val count = (l.people?.size ?: 0) + 1
        when {
            back -> _linking.value = l.copy(choosing = false)
            ok -> {
                val person = l.people?.getOrNull(l.person - 1)
                link(players[player], person)
                _linking.value = l.copy(choosing = false, player = (player + 1).coerceAtMost(players.lastIndex))
            }
            dx != 0 || dy != 0 -> _linking.value = l.copy(person = (l.person + dx + dy * LINK_PEOPLE_COLUMNS).coerceIn(0, count - 1))
        }
        return true
    }

    /** Links [player] to [person] (null: unlinks them), remembered by name for the next time they join. */
    private fun link(player: Player, person: PhotosPerson?) {
        lobby.link(player.id, person?.key)
        val key = "link:${player.name.lowercase()}"
        prefs.edit().apply { if (person == null) remove(key) else putString(key, person.key) }.apply()
        person?.let { prepare(it.key) }
    }

    /** Players who were linked before (by name) are linked again when they join. */
    private fun relink(room: Room) {
        if (!_photosInstalled.value) return
        room.players.forEach { p ->
            val person = p.person
            if (person == null) {
                prefs.getString("link:${p.name.lowercase()}", null)?.let { lobby.link(p.id, it) }
            } else {
                prepare(person)
            }
        }
        // Their faces, for the lobby, once the TV may see them.
        val missing = room.players.mapNotNull { it.person }.filter { it !in _faces.value }
        if (missing.isNotEmpty() && photos.allowed && !loadingLobbyFaces) {
            loadingLobbyFaces = true
            viewModelScope.launch {
                // Photos TV opens only faces it has listed since it started.
                runCatching { rememberNames(photos.people()) }
                loadFaces(missing)
                loadingLobbyFaces = false
            }
        }
    }

    private var loadingLobbyFaces = false

    /**
     * Starts looking through [person]'s photos, once: a dozen photos (not videos) at random among their newest
     * few hundred, with each one's year and place.
     */
    private fun prepare(person: String) {
        if (!photos.allowed) return
        facts[person]?.let { if (!it.isCompleted || it.getCompleted().isNotEmpty()) return }
        facts[person] = viewModelScope.async {
            try {
                val items = photos.photos(person, pages = 5).filter { !it.video }.shuffled().take(PHOTOS_TO_LOOK_AT)
                items.map { item ->
                    async { PhotoFacts(item.key, item.year, runCatching { photos.place(item.key) }.getOrNull()) }
                }.awaitAll().also { Log.i(TAG, "Google Photos: ${it.size} photos for $person, ${it.count { f -> f.place != null }} with places") }
            } catch (e: IOException) {
                Log.w(TAG, "Google Photos photos of $person", e)
                emptyList()
            }
        }
    }

    /** True if [person]'s name in Google Photos starts with the player's first name (accents and case aside). */
    private fun sameName(person: String, player: String): Boolean {
        fun clean(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").lowercase().trim()
        val first = clean(player).substringBefore(' ')
        return first.isNotEmpty() && clean(person).split(' ').firstOrNull() == first
    }

    /**
     * The photo questions for a game, when every player is linked: waits (up to [PHOTOS_WAIT_MS]) for their
     * photos to be looked through. Empty when someone isn't linked, so the game has none.
     */
    private suspend fun photoQuestions(room: Room, random: Random): Map<Int, List<PhotoQuestion>> {
        if (!room.allLinked || !photos.allowed) return emptyMap()
        room.players.forEach { prepare(it.person!!) }
        val byPlayer = withTimeoutOrNull(PHOTOS_WAIT_MS) {
            room.players.associate { it.id to facts[it.person]!!.await() }
        } ?: room.players.associate { p -> p.id to (facts[p.person]?.takeIf { it.isCompleted }?.getCompleted() ?: emptyList()) }
        val app = getApplication<Application>()
        return PhotoQuestions.make(
            byPlayer,
            thisYear = java.time.Year.now().value,
            yearText = app.getString(R.string.photo_year_question),
            placeText = app.getString(R.string.photo_place_question),
            random = random,
        )
    }

    /** Fetches the game's photos for the TV, in the order they're played. */
    private fun loadGamePhotos(game: Game, room: Room) {
        _gamePhotos.value = emptyMap()
        val wanted = game.steps.filterIsInstance<Step.Photo>()
        if (wanted.isEmpty()) return
        viewModelScope.launch {
            for (step in wanted) {
                val key = step.question.photo
                var bytes = photos.picture(key, PHOTO_WIDTH, PHOTO_HEIGHT)
                if (bytes == null) {
                    // Photos TV forgets the photos it listed when it restarts: list them again and retry.
                    room.player(step.playerId)?.person?.let { runCatching { photos.photos(it, pages = 5) } }
                    bytes = photos.picture(key, PHOTO_WIDTH, PHOTO_HEIGHT)
                }
                bytes?.let(::decode)?.let { _gamePhotos.value = _gamePhotos.value + (key to it) }
            }
        }
    }

    /** Starts a game, preferring questions this TV hasn't played lately; once all have been, starts over. */
    private fun startGame() {
        if (!room.value.canStart) return
        if (!room.value.allLinked) {
            startGame(emptyMap())
            return
        }
        _gettingPhotos.value = true
        viewModelScope.launch {
            val questions = photoQuestions(room.value, Random(System.nanoTime()))
            _gettingPhotos.value = false
            startGame(questions)
        }
    }

    private fun startGame(photoQuestions: Map<Int, List<PhotoQuestion>>) {
        if (!room.value.canStart) return
        val mode = room.value.mode
        fun read(file: String) = Deck.parse(getApplication<Application>().assets.open(file).bufferedReader().readText(), language)
        val decks = try {
            listOf(read("decks/family.txt")) + if (mode == Mode.ADULTS) listOf(read("decks/adults.txt")) else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Can't read the decks", e)
            return
        }
        // Adults mode is mostly family questions, with the adults' ones mixed in (see Deck.mixedWith).
        val deck = Deck(decks.flatMap { it.votes }, decks.flatMap { it.spotlights }, decks.flatMap { it.drawings })
        val key = "used_${mode.name}"
        var used = prefs.getStringSet(key, emptySet())!!
        val random = Random(System.nanoTime())
        fun <T> fresh(items: List<T>, id: (T) -> String): List<T> {
            val (old, new) = items.partition { id(it) in used }
            return new.shuffled(random) + old.shuffled(random)
        }
        // Every list played through: forget and start again.
        if (deck.votes.all { it.id in used }) used = emptySet()
        fun ordered(d: Deck) = Deck(fresh(d.votes) { it.id }, fresh(d.spotlights) { it.id }, fresh(d.drawings) { it.id })
        val ordered = if (decks.size == 1) ordered(decks[0]) else ordered(decks[0]).mixedWith(ordered(decks[1]), Deck.ADULTS_SHARE)
        if (!lobby.start(ordered, System.currentTimeMillis(), random, photoQuestions)) return
        loadGamePhotos(lobby.room.value.game!!, lobby.room.value)
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
        gameMusic.release()
    }

    private companion object {
        const val FACE_PX = 160
        const val PHOTO_WIDTH = 1600
        const val PHOTO_HEIGHT = 900
        const val PHOTOS_TO_LOOK_AT = 12
        const val PHOTOS_WAIT_MS = 15_000L
        const val LINK_PLAYER_COLUMNS = 4
        const val LINK_PEOPLE_COLUMNS = 7
        const val ROBOT_PRESSES = 5
        const val ROBOT_PRESS_GAP_MS = 2_000L
        val OK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        )
    }
}
