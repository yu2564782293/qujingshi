package com.qujingshi.aicamera

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 中文语音指导：把 AI 取景方向"说"出来。
 * 手机没有中文语音引擎时静默，不影响其它功能。
 */
class VoiceGuide(context: Context) {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private var lastMs = 0L

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.CHINESE)
                    ?: TextToSpeech.LANG_MISSING_DATA
                ready = r == TextToSpeech.LANG_AVAILABLE ||
                    r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                tts?.setSpeechRate(1.1f)
            }
        }
    }

    /**
     * 说一句话。两次播报至少间隔 [MIN_GAP_MS]，避免方向来回跳时吵个不停。
     * 同一方向改变时由调用方决定是否再说。
     */
    fun speak(text: String) {
        if (!ready) return
        val now = SystemClock.uptimeMillis()
        if (now - lastMs < MIN_GAP_MS) return
        lastMs = now
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "qujingshi-guide")
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val MIN_GAP_MS = 2500L
    }
}
