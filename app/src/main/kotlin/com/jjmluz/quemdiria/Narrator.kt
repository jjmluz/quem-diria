package com.jjmluz.quemdiria

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Reads questions aloud with the TV's text-to-speech, in the questions' language. Silent when the TV has
 * no voice for it, which Google TV may not have installed for Portuguese.
 *
 * Google's engine has several voices per language; the user chose one by ear (from samples of each). Its
 * "-network" version sounds more natural but needs the internet, so without a connection the narrator uses
 * the same voice's offline "-local" version, and the engine's default if neither is installed.
 */
class Narrator(
    context: Context,
    private val language: Language,
    /** Called on the main thread when the narrator starts (true) and stops (false) speaking. */
    private val onSpeaking: (Boolean) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var ready = false
    private var online: Voice? = null
    private var offline: Voice? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) return@TextToSpeech
        val locale = if (language == Language.PT) Locale.forLanguageTag("pt-PT") else Locale.US
        val result = tts.setLanguage(locale)
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) Log.w("QuemDiria", "No text-to-speech voice for $locale: the narrator stays quiet")
        val chosen = if (language == Language.PT) "pt-pt-x-sfs" else "en-us-x-tpf"
        val installed = tts.voices.orEmpty().filter { "notInstalled" !in it.features }
        online = installed.find { it.name == "$chosen-network" }
        offline = installed.find { it.name == "$chosen-local" }
        if (online == null && offline == null) Log.w("QuemDiria", "Voice $chosen missing: using ${tts.voice?.name}")
        tts.setSpeechRate(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = speaking(true)
            override fun onDone(id: String?) = speaking(false)
            override fun onStop(id: String?, interrupted: Boolean) = speaking(false)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = speaking(false)
        })
    }

    /** The engine reports from its own thread; the music is handled on the main one. */
    private fun speaking(now: Boolean) {
        main.post { onSpeaking(now) }
    }

    fun say(text: String) {
        if (!ready) return
        // Checked on every line, since the TV's connection can come and go during a game.
        val voice = (if (hasInternet()) online else null) ?: offline
        if (voice != null && voice != tts.voice) {
            tts.voice = voice
            Log.i("QuemDiria", "Narrator voice: ${voice.name}")
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q")
    }

    private fun hasInternet(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
