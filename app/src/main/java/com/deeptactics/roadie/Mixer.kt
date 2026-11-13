package com.deeptactics.roadie

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

// 스템 5개를 한 스트림에 직접 섞는다. 같은 위치에서 같이 돌고 게인만 바뀌니 싱크가 안 어긋난다
class Mixer(context: Context) {
    private val stems = listOf("pad", "bass", "arp", "lead", "drums").map { load(context, it) }
    private val riser = load(context, "riser")
    private val impact = load(context, "impact")
    private val loop = stems[0].size

    private val gain = FloatArray(5)
    private val target = FloatArray(5)
    private val step = FloatArray(5)
    private var master = 0f
    private var focusLevel = 1f
    private var duckLevel = 1f
    private var riserAt = -1L
    private var climaxAt = -1L
    private var riserPos = -1
    private var impactPos = -1
    private var pos = 0L // 써 넣은 샘플 수
    private val lock = Any()

    @Volatile
    private var running = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val track = AudioTrack.Builder()
        .setAudioAttributes(attrs)
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        )
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            // 다른 앱이 소리를 가져가도 타임라인은 계속 돈다. 볼륨만 바꾼다
            focusLevel = when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> 1f
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.3f
                else -> 0f
            }
        }
        .build()
    private val thread = Thread({ render() }, "mixer")

    fun start() {
        audio.requestAudioFocus(focus)
        running = true
        track.play()
        thread.start()
    }

    // 지금 들리고 있는 샘플 위치
    fun now(): Long = track.playbackHeadPosition.toLong() and 0xffffffffL

    fun apply(d: Director) = synchronized(lock) {
        for (i in 0 until 5) if (target[i] != d.gains[i]) {
            target[i] = d.gains[i]
            step[i] = abs(target[i] - gain[i]) / d.ramp
        }
        riserAt = d.riserAt
        climaxAt = d.climaxAt
    }

    fun levels(): FloatArray = synchronized(lock) { gain.copyOf() }

    // TTS가 말하는 동안 낮춘다
    fun duck(on: Boolean) {
        duckLevel = if (on) 0.35f else 1f
    }

    fun release() {
        running = false
        thread.join(500)
        track.stop()
        track.release()
        audio.abandonAudioFocusRequest(focus)
    }

    private fun render() {
        val out = ShortArray(CHUNK)
        val masterStep = 1f / (0.3f * SR)
        while (running) {
            synchronized(lock) {
                val masterTarget = focusLevel * duckLevel
                for (n in 0 until CHUNK) {
                    val p = pos + n
                    if (p == riserAt) riserPos = 0
                    if (p == climaxAt) {
                        // 클라이맥스는 마디 경계에서 한 번에. 튀지 않게 아주 짧게만 올린다
                        impactPos = 0
                        for (i in 0 until 5) {
                            target[i] = 1f
                            step[i] = 1f / 512
                        }
                    }
                    val li = (p % loop).toInt()
                    var s = 0f
                    for (i in 0 until 5) {
                        val g = gain[i]
                        if (g != target[i]) gain[i] = if (g < target[i]) minOf(g + step[i], target[i]) else maxOf(g - step[i], target[i])
                        s += stems[i][li] * gain[i]
                    }
                    if (riserPos >= 0) {
                        s += riser[riserPos++]
                        if (riserPos >= riser.size) riserPos = -1
                    }
                    if (impactPos >= 0) {
                        s += impact[impactPos++]
                        if (impactPos >= impact.size) impactPos = -1
                    }
                    if (master != masterTarget) master = if (master < masterTarget) minOf(master + masterStep, masterTarget) else maxOf(master - masterStep, masterTarget)
                    out[n] = (limit(s * master) * 32767).toInt().toShort()
                }
                pos += CHUNK
            }
            track.write(out, 0, CHUNK)
        }
    }

    // 0.8 넘는 부분만 부드럽게 눌러서 클리핑 소리를 막는다
    private fun limit(x: Float): Float {
        val a = abs(x)
        return if (a <= 0.8f) x else sign(x) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
    }

    companion object {
        const val SR = Director.SR
        const val CHUNK = 1024

        // 16bit 모노 wav. data 청크 길이만큼만 읽는다(뒤에 다른 청크가 붙어 있을 수 있음)
        private fun load(context: Context, name: String): FloatArray {
            val bytes = context.assets.open("$name.wav").use { it.readBytes() }
            val size = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val pcm = ByteBuffer.wrap(bytes, 44, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
        }
    }
}
