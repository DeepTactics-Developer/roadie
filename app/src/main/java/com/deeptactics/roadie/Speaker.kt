package com.deeptactics.roadie

import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

// Pleos TTS를 먼저 쓰고, 안 되면 안드로이드 기본 TTS로 넘어간다
class Speaker(context: Context, private val onSpeaking: (Boolean) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private var pleos: PleosTts? = null
    private var basic: TextToSpeech? = null
    private var pleosReady = false
    private var basicReady = false
    private var pending: String? = null

    init {
        runCatching { startPleos(context) }.onFailure {
            Log.w(MainActivity.TAG, "pleos tts 시작 실패", it)
            fallBack(context)
        }
    }

    private fun startPleos(context: Context) {
        val t = PleosTts(context, Mode.HYBRID)
        pleos = t
        t.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                t.setLocale(Locale.KOREA)
                t.addEventListener(object : EventListener {
                    override fun onStart() { Log.d(MainActivity.TAG, "tts 시작"); speaking(true) }
                    override fun onReady() {}
                    override fun onDone() { Log.d(MainActivity.TAG, "tts 끝"); speaking(false) }
                    override fun onStop() = speaking(false)
                    override fun onUpdatedRms(rms: Double) {}
                    override fun onError(type: ErrorType) {
                        Log.w(MainActivity.TAG, "pleos tts 오류 $type")
                        speaking(false)
                        ui.post { fallBack(context) }
                    }
                })
                pleosReady = true
                ui.post { flush() }
            }

            override fun onFailure(type: ErrorType) {
                Log.w(MainActivity.TAG, "pleos tts 초기화 실패 $type")
                ui.post { fallBack(context) }
            }
        })
    }

    private fun speaking(on: Boolean) { ui.post { onSpeaking(on) } }

    private fun fallBack(context: Context) {
        if (basic != null) return
        pleosReady = false
        basic = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val b = basic ?: return@TextToSpeech
            if (b.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) Log.w(MainActivity.TAG, "기본 tts 한국어 없음")
            b.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = speaking(true)
                override fun onDone(id: String?) = speaking(false)
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = speaking(false)
            })
            basicReady = true
            ui.post { flush() }
        }
    }

    // 준비 전에 온 말은 하나만 들고 있다가 준비되면 한다
    fun say(text: String) {
        pending = text
        flush()
    }

    private fun flush() {
        val text = pending ?: return
        when {
            pleosReady -> pleos?.speak(text, true)
            basicReady -> basic?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "roadie")
            else -> return
        }
        pending = null
    }

    fun stop() {
        pending = null
        if (pleosReady) pleos?.stop()
        basic?.stop()
    }

    fun release() {
        stop()
        pleos?.release()
        basic?.shutdown()
    }
}
