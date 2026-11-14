package com.deeptactics.roadie

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

// Pleos 음성 인식. 안 되면 화면의 예시 버튼과 선택 버튼으로 대신한다
class Listener(context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    var ready = false
        private set
    var listening = false
        private set

    // 듣는 중 상태가 바뀔 때, 글자가 들어올 때(final이면 끝)
    var onState: (Boolean) -> Unit = {}
    var onText: (String, Boolean) -> Unit = { _, _ -> }
    var onFail: () -> Unit = {}

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            ui.post { onText(text, isFinal) }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onStartedRecognition() = state(true)
        override fun onEndedRecognition() = state(false)
        override fun onError(type: ErrorType) {
            Log.w(MainActivity.TAG, "stt 오류 $type")
            state(false)
            ui.post { onFail() }
        }
    }

    init {
        runCatching {
            val s = SpeechToText(context, Mode.HYBRID)
            stt = s
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    s.setLocale(Locale.KOREA)
                    s.addListener(listener)
                    ready = true
                }

                override fun onFailure(type: ErrorType) {
                    Log.w(MainActivity.TAG, "stt 초기화 실패 $type")
                }
            })
        }.onFailure { Log.w(MainActivity.TAG, "stt 시작 실패", it) }
    }

    private fun state(on: Boolean) {
        ui.post {
            listening = on
            onState(on)
        }
    }

    fun listen(): Boolean {
        if (!ready) return false
        runCatching { stt?.request() }.onFailure {
            Log.w(MainActivity.TAG, "stt 요청 실패", it)
            return false
        }
        return true
    }

    fun stop() {
        if (ready) runCatching { stt?.stop() }
    }

    fun release() {
        stop()
        runCatching { stt?.removeListener(listener) }
        runCatching { stt?.release() }
    }
}
