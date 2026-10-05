package com.questoverlay.capture

import android.content.Context
import android.speech.tts.TextToSpeech

/** Says short coaching lines out loud with the phone's own text-to-speech. Respects the mute button. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val prefs = context.applicationContext.getSharedPreferences("coach", Context.MODE_PRIVATE)
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(v) {
            prefs.edit().putBoolean("muted", v).apply()
            if (v) try { tts.stop() } catch (e: Exception) {}
        }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
    }

    fun say(text: String) {
        if (!ready || muted || text.isBlank()) return
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "breadcrumbs")
        } catch (e: Exception) {
            // Speech isn't essential.
        }
    }

    fun shutdown() {
        try {
            tts.stop()
            tts.shutdown()
        } catch (e: Exception) {}
    }
}
