package com.jjmluz.quemdiria

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Music from the app's assets, faded in and out: the room's ([LOBBY], one track looping), the game's
 * ([GAME], its tracks one after another in a shuffled order) and the winner's ([WINNER], looping on the end
 * screen). The build bundles the free tracks in the `music` folder, or your own from `music-custom`, which stays out
 * of git (see `CopyMusic` in app/build.gradle.kts). A kind with no tracks is simply quiet.
 *
 * [duck] lowers it while the narrator speaks, so the questions can be heard over it.
 */
class Music(context: Context, private val scope: CoroutineScope, prefix: String, private val volume: Float) {
    private val assets = context.assets
    private val tracks = assets.list("music").orEmpty().filter { it.startsWith(prefix) }.sorted().map { "music/$it" }
        .also { if (it.isEmpty()) Log.i("QuemDiria", "No $prefix music in this build") }
    private var order = tracks.shuffled()
    private var index = 0
    private var player: MediaPlayer? = order.firstOrNull()?.let(::load)

    private var on = false
    private var ducked = false
    /** How far faded in (0 to 1), and how loud while ducking or not (1 or [DUCKED]). */
    private var level = 0f
    private var duckLevel = 1f
    private var fade: Job? = null
    private var duckFade: Job? = null

    private fun load(path: String): MediaPlayer? = try {
        assets.openFd(path).use { fd ->
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                // One track loops; several play in turn.
                isLooping = tracks.size == 1
                setOnCompletionListener { next() }
                prepare()
                Log.i("QuemDiria", "Music: $path")
            }
        }
    } catch (e: IOException) {
        Log.w("QuemDiria", "Can't play $path: ${e.message}")
        null
    }

    /** On to the next track; after the last, a new order that doesn't start with the one just played. */
    private fun next() {
        index++
        if (index >= order.size) {
            val last = order.lastOrNull()
            order = tracks.shuffled().let { if (it.size > 1 && it.first() == last) it.drop(1) + it.first() else it }
            index = 0
        }
        switchTo(order.getOrNull(index))
        if (on) player?.start()
    }

    private fun switchTo(path: String?) {
        player?.release()
        player = path?.let(::load)
        apply()
    }

    /**
     * Back to the start, of a random track when there are several (in a new shuffled order): for each new game,
     * or each time the winner is shown. Only while faded out.
     */
    fun shuffle() {
        if (on) return
        if (tracks.size < 2) {
            fade?.cancel()
            level = 0f
            apply()
            player?.seekTo(0)
            return
        }
        fade?.cancel()
        level = 0f
        order = tracks.shuffled()
        index = 0
        switchTo(order.first())
    }

    /** Fades the music in (from where it stopped) or out. */
    fun play(on: Boolean) {
        if (player == null || on == this.on) return
        this.on = on
        Log.i("QuemDiria", "Music: ${if (on) "in" else "out"}, ${order.getOrNull(index)}")
        fade?.cancel()
        fade = scope.launch {
            if (on && player?.isPlaying == false) player?.start()
            val from = level
            val target = if (on) 1f else 0f
            for (i in 1..STEPS) {
                level = from + (target - from) * i / STEPS
                apply()
                delay(FADE_MS / STEPS)
            }
            if (!on) player?.pause()
        }
    }

    /** Lowers the music (true) while someone speaks over it, or brings it back up (false). */
    fun duck(down: Boolean) {
        if (player == null || down == ducked) return
        ducked = down
        duckFade?.cancel()
        duckFade = scope.launch {
            // Back up only after a moment, so it doesn't swell in the pause between two lines.
            if (!down) delay(UNDUCK_DELAY_MS)
            val from = duckLevel
            val target = if (down) DUCKED else 1f
            val steps = if (down) DUCK_STEPS else STEPS
            val ms = if (down) DUCK_MS else FADE_MS
            for (i in 1..steps) {
                duckLevel = from + (target - from) * i / steps
                apply()
                delay(ms / steps)
            }
        }
    }

    private fun apply() {
        val v = volume * level * duckLevel
        player?.setVolume(v, v)
    }

    fun release() {
        fade?.cancel()
        duckFade?.cancel()
        player?.release()
        player = null
    }

    companion object {
        /** The room's track (`music/lobby.*`), the game's (`music/background_*`) and the winner's (`music/winner.*`). */
        const val LOBBY = "lobby."
        const val GAME = "background_"
        const val WINNER = "winner."
        private const val FADE_MS = 1_200L
        private const val STEPS = 24
        /** Ducking is quicker, so the first words aren't lost under the music. */
        private const val DUCK_MS = 250L
        private const val DUCK_STEPS = 8
        private const val DUCKED = 0.3f
        private const val UNDUCK_DELAY_MS = 500L
    }
}
