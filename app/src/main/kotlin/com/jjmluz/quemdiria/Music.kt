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
 * A looping music track from the app's assets, faded in and out: the room's ([TITLE]) and the game's
 * ([GAME]). A track is only there when the build found it to bundle (see `copyMusic` in app/build.gradle.kts):
 * the family's tracks aren't ours to share, so they stay out of git, and a build without them is simply quiet.
 *
 * [duck] lowers it while the narrator speaks, so the questions can be heard over it.
 */
class Music(context: Context, private val scope: CoroutineScope, path: String, private val volume: Float) {
    private val player: MediaPlayer? = try {
        context.assets.openFd(path).use { fd ->
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                isLooping = true
                setVolume(0f, 0f)
                prepare()
            }
        }
    } catch (e: IOException) {
        Log.i("QuemDiria", "No $path in this build")
        null
    }

    private var on = false
    private var ducked = false
    /** How far faded in (0 to 1), and how loud while ducking or not (1 or [DUCKED]). */
    private var level = 0f
    private var duckLevel = 1f
    private var fade: Job? = null
    private var duckFade: Job? = null

    /** Fades the music in (from where it stopped) or out. */
    fun play(on: Boolean) {
        val player = player ?: return
        if (on == this.on) return
        this.on = on
        fade?.cancel()
        fade = scope.launch {
            if (on && !player.isPlaying) player.start()
            val from = level
            val target = if (on) 1f else 0f
            for (i in 1..STEPS) {
                level = from + (target - from) * i / STEPS
                apply()
                delay(FADE_MS / STEPS)
            }
            if (!on) player.pause()
        }
    }

    /** Lowers the music (true) while someone speaks over it, or brings it back up (false). */
    fun duck(down: Boolean) {
        player ?: return
        if (down == ducked) return
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
    }

    companion object {
        const val TITLE = "music/title.ogg"
        const val GAME = "music/background_1.m4a"
        private const val FADE_MS = 1_200L
        private const val STEPS = 24
        /** Ducking is quicker, so the first words aren't lost under the music. */
        private const val DUCK_MS = 250L
        private const val DUCK_STEPS = 8
        private const val DUCKED = 0.3f
        private const val UNDUCK_DELAY_MS = 500L
    }
}
