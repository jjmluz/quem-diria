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
 * Music for the room screen, looping, faded in and out. It's only there when the build found a track
 * to bundle (see `copyTitleMusic` in app/build.gradle.kts): the family's track isn't ours to share, so
 * it stays out of git, and a build without it is simply quiet.
 */
class TitleMusic(context: Context, private val scope: CoroutineScope) {
    private val player: MediaPlayer? = try {
        context.assets.openFd(PATH).use { fd ->
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
        Log.i("QuemDiria", "No title music in this build")
        null
    }

    private var on = false
    private var volume = 0f
    private var fade: Job? = null

    /** Fades the music in (from where it stopped) or out. */
    fun play(on: Boolean) {
        val player = player ?: return
        if (on == this.on) return
        this.on = on
        fade?.cancel()
        fade = scope.launch {
            if (on && !player.isPlaying) player.start()
            val from = volume
            val target = if (on) VOLUME else 0f
            for (i in 1..STEPS) {
                volume = from + (target - from) * i / STEPS
                player.setVolume(volume, volume)
                delay(FADE_MS / STEPS)
            }
            if (!on) player.pause()
        }
    }

    fun release() {
        fade?.cancel()
        player?.release()
    }

    private companion object {
        const val PATH = "music/title.ogg"
        /** Background music while people join: below the game's own sounds. */
        const val VOLUME = 0.6f
        const val FADE_MS = 1_200L
        const val STEPS = 24
    }
}
