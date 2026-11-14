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
import kotlin.math.sqrt
import kotlin.math.tanh

// 스템 6개를 한 스트림에 직접 섞는다. 마디가 끝날 때마다 Arranger가 고른 마디로 넘어간다.
// 모든 스템이 같은 위치를 읽으니 파트를 건너뛰어도 싱크가 안 어긋난다
class Mixer(context: Context, private val arranger: Arranger) {
    private val stems = STEMS.map { load(context, it) }
    private val songLen = stems[0].size

    private var master = 0f
    @Volatile private var focusLevel = 1f
    @Volatile private var duckLevel = 1f

    private var written = 0L // 써 넣은 샘플 수
    private var src = 0 // 곡 안 읽는 위치
    private var old = -1 // 건너뛸 때 이전 위치. 짧게 겹쳐서 이음새를 감춘다
    private var fade = 0
    private val rms = FloatArray(STEMS.size)

    @Volatile private var running = false

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

    // 화면 막대용 스템별 세기(0~1). fx는 뺀다
    fun levels(): FloatArray = synchronized(rms) { FloatArray(5) { minOf(rms[it] * 5f, 1f) } }

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
        val sum = FloatArray(STEMS.size)
        val masterStep = 1f / (0.3f * SR)
        while (running) {
            sum.fill(0f)
            val masterTarget = focusLevel * duckLevel
            for (n in 0 until CHUNK) {
                if (written % Arranger.BAR == 0L) {
                    val to = (arranger.nextBar(written) * Arranger.BAR).toInt()
                    if (to != src) {
                        old = src
                        fade = XFADE
                        src = to
                    }
                }
                var s = 0f
                for (i in stems.indices) {
                    var v = stems[i][src]
                    if (fade > 0 && old < songLen) v = v * (1f - fade.toFloat() / XFADE) + stems[i][old] * fade / XFADE
                    sum[i] += v * v
                    s += v
                }
                if (fade > 0) {
                    fade--
                    old++
                }
                src = (src + 1) % songLen
                written++
                if (master != masterTarget) master = if (master < masterTarget) minOf(master + masterStep, masterTarget) else maxOf(master - masterStep, masterTarget)
                out[n] = (limit(s * master) * 32767).toInt().toShort()
            }
            synchronized(rms) { for (i in rms.indices) rms[i] = rms[i] * 0.6f + sqrt(sum[i] / CHUNK) * 0.4f }
            track.write(out, 0, CHUNK)
        }
    }

    // 0.8 넘는 부분만 부드럽게 눌러서 클리핑 소리를 막는다
    private fun limit(x: Float): Float {
        val a = abs(x)
        return if (a <= 0.8f) x else sign(x) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
    }

    companion object {
        const val SR = Arranger.SR
        const val CHUNK = 1024
        const val XFADE = 256
        val STEMS = listOf("pad", "bass", "arp", "lead", "drums", "fx")

        // 16bit 모노 wav. data 청크 길이만큼만 읽는다
        private fun load(context: Context, name: String): FloatArray {
            val bytes = context.assets.open("$name.wav").use { it.readBytes() }
            val size = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val pcm = ByteBuffer.wrap(bytes, 44, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
        }
    }
}
