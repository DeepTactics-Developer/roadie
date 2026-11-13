package com.deeptactics.roadie

import kotlin.math.max
import kotlin.math.roundToLong

// 음악 연출 규칙. 시간은 전부 오디오 샘플 단위(22050Hz)라 마디 경계와 정확히 맞는다.
// 결과(gains, ramp, riserAt, climaxAt)는 Mixer가 가져다 쓴다
class Director(
    private val scenes: List<Scene>,
    private val onApproach: (Scene) -> Unit,
    private val onClimax: (Scene) -> Unit,
) {
    enum class State { STOP, CRUISE, APPROACH, BREATH, CLIMAX, AFTER }

    var state = State.STOP
        private set
    val gains = FloatArray(5) // pad, bass, arp, lead, drums
    var ramp = BAR
        private set
    var riserAt = -1L
        private set
    var climaxAt = -1L
        private set
    var eta = Double.POSITIVE_INFINITY // 다음 장면까지 초
        private set

    private var next = 0
    private var announced = -1
    private var afterUntil = 0L
    private val hist = ArrayDeque<Pair<Long, Double>>()

    // etaHint: 주행 소스가 직접 아는 도착 예상(초). 없으면 최근 평균 속도로 잰다
    fun update(now: Long, km: Double, speed: Float, etaHint: ((Double) -> Double?)? = null) {
        hist.addLast(now to km)
        while (hist.size > 2 && now - hist[1].first >= WINDOW) hist.removeFirst()
        val (t0, k0) = hist.first()
        val rate = if (now > t0) (km - k0) / ((now - t0).toDouble() / SR) else 0.0
        val scene = scenes.getOrNull(next)
        eta = when {
            scene == null -> Double.POSITIVE_INFINITY
            else -> etaHint?.invoke(scene.km)
                ?: if (rate <= 1e-7) Double.POSITIVE_INFINITY else max(scene.km - km, 0.0) / rate
        }

        when (state) {
            State.BREATH -> when {
                now >= climaxAt -> {
                    state = State.CLIMAX
                    next++
                    onClimax(scene!!)
                }
                // 숨 고르기 중에 크게 늦어지면 접근으로 되돌린다. riser가 나간 뒤엔 그냥 간다
                now < riserAt && arrival(now) > climaxAt + 2 * BAR -> {
                    state = State.APPROACH
                    riserAt = -1
                    climaxAt = -1
                }
            }
            State.CLIMAX -> if (now >= climaxAt + 8 * BAR) {
                state = State.AFTER
                afterUntil = now + 3 * BAR
                riserAt = -1
                climaxAt = -1
            }
            State.AFTER -> if (now >= afterUntil) state = State.CRUISE
            else -> {}
        }

        if (state == State.STOP || state == State.CRUISE || state == State.APPROACH) {
            if (scene != null && eta <= 60 && state != State.APPROACH) {
                state = State.APPROACH
                if (announced != next) {
                    announced = next
                    onApproach(scene)
                }
            } else if (state == State.APPROACH && eta > 600) { // 정체면 기다리고, 아예 섰을 때만 푼다
                state = State.CRUISE
            } else if (state != State.APPROACH) {
                state = if (speed < 5) State.STOP else State.CRUISE
            }

            if (state == State.APPROACH) {
                // 도착 예상 시각에 가장 가까운 마디. 2마디 안으로 들어오면 확정
                val target = (arrival(now).toDouble() / BAR).roundToLong() * BAR
                if (target - now <= 2 * BAR) {
                    // riser 4초는 들어갈 자리가 있어야 한다
                    val earliest = (now + RISER + BAR - 1) / BAR * BAR
                    climaxAt = max(target, earliest)
                    riserAt = climaxAt - RISER
                    state = State.BREATH
                }
            }
        }

        val arp = speed >= 40
        val g = when (state) {
            State.STOP -> floatArrayOf(1f, 0f, 0f, 0f, 0f)
            State.CRUISE, State.AFTER -> floatArrayOf(1f, 1f, if (arp) 1f else 0f, 0f, 0f)
            State.APPROACH -> floatArrayOf(1f, 1f, 1f, 0f, 1f)
            State.BREATH -> floatArrayOf(1f, 0f, 1f, 0f, 0f)
            State.CLIMAX -> floatArrayOf(1f, 1f, 1f, 1f, 1f)
        }
        g.copyInto(gains)
        ramp = if (state == State.AFTER) 3 * BAR else BAR
    }

    private fun arrival(now: Long) =
        if (eta.isInfinite()) Long.MAX_VALUE / 2 else now + (eta * SR).toLong()

    companion object {
        const val SR = 22050
        const val BAR = 55125 // 96bpm 4/4 한 마디
        const val RISER = 88200L // riser.wav 4초
        const val WINDOW = 2L * SR // 평균 속도 구간
    }
}
