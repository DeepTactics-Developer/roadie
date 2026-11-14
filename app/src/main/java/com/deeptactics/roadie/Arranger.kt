package com.deeptactics.roadie

import kotlin.math.max

// 곡의 파트. 위치는 tools/make_loops.py의 PARTS와 같아야 한다
enum class Part(val start: Int, val bars: Int) {
    IDLE(0, 4), CRUISE_A(4, 8), CRUISE_B(12, 8),
    BUILD_1(20, 2), BUILD_2(22, 2), PRE(24, 2), DROP(26, 8), AFTER(34, 4);

    val building get() = this == BUILD_1 || this == BUILD_2
    val cruising get() = this == CRUISE_A || this == CRUISE_B || this == IDLE
}

// 마디가 끝날 때마다 다음 마디를 고른다.
// 장면 도착 예상 시각에 맞춰 빌드업 → 숨 고르기 → 드롭. 늦어지면 빌드업을 반복하고, 빨라지면 건너뛴다.
// update는 화면 쪽에서, nextBar는 오디오 스레드에서 부른다. 시간은 전부 샘플 단위
class Arranger(
    private val onApproach: (Scene) -> Unit,
    private val onDrop: (Scene) -> Unit,
) {
    var scenes: List<Scene> = emptyList()
        @Synchronized set

    var part = Part.IDLE
        private set
    private var barInPart = -1

    // 다음 드롭 대상
    var next = 0
        private set
    var eta = Double.POSITIVE_INFINITY // 다음 장면까지 초
        private set
    private var etaAt = 0L
    private var speed = 0f
    private var tunnel = false

    private var dropAt = -1L
    private var dropScene: Scene? = null
    private var announced = -1
    private val hist = ArrayDeque<Pair<Long, Double>>()

    val target get() = scenes.getOrNull(next)

    // etaHint: 주행 소스가 직접 아는 도착 예상(초). 없으면 최근 평균 속도로 잰다
    @Synchronized
    fun update(now: Long, km: Double, speedKmh: Float, etaHint: ((Double) -> Double?)? = null) {
        hist.addLast(now to km)
        while (hist.size > 2 && now - hist[1].first >= WINDOW) hist.removeFirst()
        val (t0, k0) = hist.first()
        val rate = if (now > t0) (km - k0) / ((now - t0).toDouble() / SR) else 0.0
        val s = target
        eta = when {
            s == null -> Double.POSITIVE_INFINITY
            else -> etaHint?.invoke(s.km)
                ?: if (rate <= 1e-7) Double.POSITIVE_INFINITY else max(s.km - km, 0.0) / rate
        }
        etaAt = now
        speed = speedKmh
        tunnel = s?.tunnelFrom?.let { km >= it } ?: false

        if (s != null && announced < next && (eta <= LINE_SEC || tunnel)) {
            announced = next
            onApproach(s)
        }
        val d = dropScene
        if (d != null && now >= dropAt) {
            dropScene = null
            onDrop(d)
        }
    }

    // barStart부터 울릴 마디의 곡 안 위치(마디 번호)
    @Synchronized
    fun nextBar(barStart: Long): Int {
        val e = eta - (barStart - etaAt).toDouble() / SR
        val s = target
        val end = barInPart + 1 >= part.bars
        fun go(p: Part) {
            part = p
            barInPart = 0
        }

        when {
            part == Part.PRE -> if (end) {
                go(Part.DROP)
                dropAt = barStart
                dropScene = s
                next++
            } else barInPart++

            // 숨 고르기(2마디)가 끝나는 순간이 도착 시각과 가장 가깝게
            s != null && e <= PRE_SEC + BAR_SEC / 2 && part != Part.DROP -> go(Part.PRE)
            s != null && e <= PRE_SEC + BAR_SEC / 2 && part == Part.DROP && barInPart >= 1 -> go(Part.PRE)

            part.building -> when {
                (barInPart + 1) % 2 != 0 -> barInPart++
                e > ABORT_SEC && !tunnel -> go(cruise())
                e <= BUILD_2_SEC -> go(Part.BUILD_2)
                else -> go(Part.BUILD_1) // 정체면 같은 2마디를 계속 돈다
            }

            s != null && (e <= BUILD_SEC || tunnel) && part != Part.DROP && (barInPart + 1) % 2 == 0 -> go(Part.BUILD_1)

            part == Part.DROP -> if (end) go(Part.AFTER) else barInPart++
            part == Part.AFTER -> if (end) go(cruise()) else barInPart++

            part == Part.IDLE -> if (speed >= STOP_KMH) go(cruise()) else if (end) go(Part.IDLE) else barInPart++

            // 순항: 서면 IDLE, 4마디마다 속도에 맞는 파트로
            speed < STOP_KMH -> go(Part.IDLE)
            (barInPart + 1) % 4 == 0 && cruise() != part -> go(cruise())
            else -> if (end) go(part) else barInPart++
        }
        return part.start + barInPart
    }

    private fun cruise() = when {
        speed < STOP_KMH -> Part.IDLE
        speed >= FAST_KMH -> Part.CRUISE_B
        else -> Part.CRUISE_A
    }

    companion object {
        const val SR = 22050
        const val BAR = 55125L // 96bpm 한 마디
        const val BAR_SEC = BAR.toDouble() / SR
        const val PRE_SEC = 2 * BAR_SEC
        const val BUILD_SEC = 25.0
        const val BUILD_2_SEC = 12.5
        const val ABORT_SEC = 90.0
        const val LINE_SEC = 35.0
        const val STOP_KMH = 5f
        const val FAST_KMH = 50f
        const val WINDOW = 2L * SR
    }
}
