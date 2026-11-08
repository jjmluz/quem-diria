package com.jjmluz.quemdiria

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random
import kotlin.math.sin

/**
 * The game's few sounds, made from sine notes when the app starts, so there are no audio files to license.
 * Each plays on its own static track; playing again restarts it.
 */
class Sounds {
    enum class Kind { START, ANSWER, REVEAL, FANFARE, SPLASH }

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
        // The splash screen's: a warm chord spinning up like a tape, vinyl crackle, a chime for the "?".
        Kind.SPLASH to track(splash()),
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

    fun stop(kind: Kind) {
        runCatching { tracks[kind]?.stop() }
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
        return track(samples)
    }

    /**
     * The splash screen's sound (the timings match SplashScreen): an Fmaj9 chord whose pitch climbs to
     * tune in the first 0.65 s, like a tape getting up to speed, then wobbles slowly; a filter opening
     * as it swells; soft vinyl crackle; and a bell-like chime with an echo when the "?" lands.
     */
    private fun splash(): ShortArray {
        val length = (RATE * SPLASH_S).toInt()
        val mix = DoubleArray(length)
        // The chord: two slightly detuned oscillators per note, a triangle bass and sawtooths above.
        val notes = doubleArrayOf(174.6, 261.6, 329.6, 392.0, 440.0)
        val chord = DoubleArray(length)
        for ((n, hz) in notes.withIndex()) {
            for (detune in doubleArrayOf(-7.0, 7.0)) {
                var phase = 0.0
                for (i in 0 until length) {
                    val t = i.toDouble() / RATE
                    val spinUp = if (t < 0.65) 0.82 * (1 / 0.82).pow(t / 0.65) else 1.0
                    val cents = detune + 6 * sin(2 * PI * 0.7 * t)
                    phase = (phase + hz * spinUp * 2.0.pow(cents / 1200) / RATE) % 1.0
                    chord[i] += if (n == 0) 0.5 * (1 - 4 * kotlin.math.abs(phase - 0.5)) else 0.18 * (2 * phase - 1)
                }
            }
        }
        // Two one-pole low-passes opening from 500 to 2200 Hz, under the chord's swell.
        var a = 0.0
        var b = 0.0
        for (i in 0 until length) {
            val t = i.toDouble() / RATE
            val cutoff = 500 + 1700 * minOf(1.0, t / 1.6)
            val k = 1 - exp(-2 * PI * cutoff / RATE)
            a += k * (chord[i] - a)
            b += k * (a - b)
            val swell = when {
                t < 0.9 -> 0.16 * t / 0.9
                t < 2.6 -> 0.16
                else -> 0.16 * (0.0001 / 0.16).pow((t - 2.6) / 2.6)
            }
            mix[i] += b * swell
        }
        // Crackle: faint hiss and sparse clicks, high-passed, in for the first four seconds.
        val random = Random(7)
        var last = 0.0
        var high = 0.0
        val hp = exp(-2 * PI * 1200.0 / RATE)
        for (i in 0 until length) {
            val t = i.toDouble() / RATE
            val click = if (random.nextDouble() < 0.0018) (random.nextDouble() * 2 - 1) * 0.5 else 0.0
            val x = (random.nextDouble() * 2 - 1) * 0.012 + click
            high = hp * (high + x - last)
            last = x
            val level = when {
                t < 0.3 -> t / 0.3
                t < 3.8 -> 1.0
                else -> maxOf(0.0, 1 - (t - 3.8) / 1.2)
            }
            mix[i] += high * 0.5 * level
        }
        // The chime as the "?" lands, with two fainter echoes.
        for ((hz, gain) in listOf(1318.5 to 1.0, 1975.5 to 0.5)) {
            for ((delay, level) in listOf(0.0 to 0.22, 0.18 to 0.08, 0.36 to 0.03)) {
                val from = ((CHIME_S + delay) * RATE).toInt()
                for (j in 0 until (1.4 * RATE).toInt()) {
                    val i = from + j
                    if (i >= length) break
                    val t = j.toDouble() / RATE
                    val envelope = minOf(1.0, t / 0.008) * level * gain * (0.0001 / (level * gain)).pow(t / 1.4)
                    mix[i] += sin(2 * PI * hz * t) * envelope
                }
            }
        }
        return ShortArray(length) { i ->
            (mix[i] * 0.9 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun track(samples: ShortArray): AudioTrack {
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

    companion object {
        private const val RATE = 22_050
        /** How long the splash sound lasts, in seconds. */
        private const val SPLASH_S = 5.3
        /** When the splash's "?" lands: it starts falling at 2.1 s and first touches down a quarter in. */
        private const val CHIME_S = 2.36
    }
}
