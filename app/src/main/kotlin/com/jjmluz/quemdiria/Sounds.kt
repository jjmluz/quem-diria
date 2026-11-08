package com.jjmluz.quemdiria

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The game's few sounds, made from sine notes when the app starts, so there are no audio files to license.
 * Each plays on its own static track; playing again restarts it.
 */
class Sounds {
    enum class Kind { START, ANSWER, REVEAL, FANFARE }

    private val tracks: Map<Kind, AudioTrack> = mapOf(
        // A rising pair: something to do on the phones.
        Kind.START to track(listOf(Note(660.0, 0.0, 0.12), Note(990.0, 0.1, 0.18))),
        // A soft blip each time someone answers.
        Kind.ANSWER to track(listOf(Note(1320.0, 0.0, 0.07, volume = 0.25))),
        // Three quick notes up: look at the TV.
        Kind.REVEAL to track(listOf(Note(523.3, 0.0, 0.1), Note(659.3, 0.09, 0.1), Note(784.0, 0.18, 0.25))),
        // A little fanfare for the winner.
        Kind.FANFARE to track(
            listOf(
                Note(523.3, 0.0, 0.14), Note(659.3, 0.14, 0.14), Note(784.0, 0.28, 0.14),
                Note(1046.5, 0.42, 0.5), Note(784.0, 0.42, 0.5, volume = 0.3),
            )
        ),
    )

    private data class Note(val hz: Double, val start: Double, val length: Double, val volume: Double = 0.45)

    fun play(kind: Kind) {
        val track = tracks[kind] ?: return
        runCatching {
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    fun release() = tracks.values.forEach { it.release() }

    private fun track(notes: List<Note>): AudioTrack {
        val total = notes.maxOf { it.start + it.length } + 0.05
        val samples = ShortArray((RATE * total).toInt())
        for (note in notes) {
            val from = (note.start * RATE).toInt()
            val count = (note.length * RATE).toInt()
            for (i in 0 until count) {
                val t = i.toDouble() / RATE
                // A quick attack and an exponential fade, so the notes don't click.
                val envelope = minOf(1.0, t / 0.005) * exp(-4.0 * t / note.length)
                val value = sin(2 * PI * note.hz * t) * envelope * note.volume
                val index = from + i
                if (index < samples.size) {
                    samples[index] = (samples[index] + value * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        return track
    }

    private companion object {
        const val RATE = 22_050
    }
}
