package com.jjmluz.quemdiria

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Reads questions aloud with the TV's text-to-speech, in the questions' language. Silent when the TV has
 * no voice for it, which Google TV may not have installed for Portuguese.
 */
class Narrator(context: Context, private val language: Language) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) return@TextToSpeech
        val locale = if (language == Language.PT) Locale.forLanguageTag("pt-PT") else Locale.US
        val result = tts.setLanguage(locale)
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) Log.w("QuemDiria", "No text-to-speech voice for $locale: the narrator stays quiet")
        tts.setSpeechRate(0.95f)
    }

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
