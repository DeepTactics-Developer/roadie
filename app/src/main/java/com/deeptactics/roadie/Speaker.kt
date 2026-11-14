package com.deeptactics.roadie

import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

// Pleos TTS를 먼저 쓰고, 안 되면 안드로이드 기본 TTS로 넘어간다
class Speaker(context: Context) {
    // 말하는 중인지. 화면과 서비스가 읽어 간다
    @Volatile var talking = false
        private set

    // 말하는 중이거나 말할 게 밀려 있다
    val busy get() = talking || pending != null
    private val ui = Handler(Looper.getMainLooper())
    private var pleos: PleosTts? = null
    private var basic: TextToSpeech? = null
    private var pleosReady = false
    private var basicReady = false
    private var pending: String? = null
    private var holdUntil = 0L
    private val audio = context.getSystemService(AudioManager::class.java)
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = flush()
    }

    init {
        audio.registerAudioPlaybackCallback(playback, ui)
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

    private fun speaking(on: Boolean) { talking = on }

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

    // 경로를 요청하거나 취소하면 내비가 안내 음성을 낸다. 그게 끝날 때까지 말을 미룬다
    fun hold(ms: Long) {
        holdUntil = SystemClock.uptimeMillis() + ms
        ui.postAtTime({ flush() }, holdUntil + 10)
    }

    private fun naviTalking() = audio.activePlaybackConfigurations.any {
        it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun flush() {
        val text = pending ?: return
        if (SystemClock.uptimeMillis() < holdUntil || naviTalking()) return
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
        audio.unregisterAudioPlaybackCallback(playback)
        pleos?.release()
        basic?.shutdown()
    }
}
