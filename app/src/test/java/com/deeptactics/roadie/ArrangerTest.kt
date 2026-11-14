package com.deeptactics.roadie

import org.junit.Assert.assertTrue
import org.junit.Test

// 시뮬레이션 주행으로 코스를 끝까지 돌리면서 드롭 타이밍과 파트 재배열을 본다
class ArrangerTest {
    private val sr = Arranger.SR.toLong()
    private val bar = Arranger.BAR

    private fun run(course: Course, warp: Double, hint: Boolean) {
        val sim = SimulatedDrive(course, warp)
        val lineAt = mutableMapOf<String, Long>()
        val dropAt = mutableMapOf<String, Long>()
        val passedAt = mutableMapOf<String, Long>()
        var now = 0L
        lateinit var a: Arranger
        a = Arranger({ lineAt[it.name] = now }, {})
        a.scenes = course.scenes
        var written = 0L
        val latency = 8192L // 오디오 버퍼만큼 앞서 쓴다
        var jamBuildBars = 0
        val tick = sr / 20 // 50ms
        val tag = "${course.name} ${warp}x ${if (hint) "예측" else "평균속도"}"

        while (sim.km < course.km && now < 3600 * sr) {
            sim.update(tick.toDouble() / sr)
            now += tick
            a.update(now, sim.km, sim.speedKmh, if (hint) sim::etaSec else null)
            // 믹서는 마디 경계에서만 묻는다
            while (written < now + latency) {
                val target = a.target
                val b = a.nextBar(written)
                if (b == Part.DROP.start && target != null && target.name !in dropAt) dropAt[target.name] = written
                if (a.part.building && sim.speedKmh < 10 && sim.km > 1) jamBuildBars++
                written += bar
            }
            for (s in course.scenes) if (sim.km >= s.km && s.name !in passedAt) passedAt[s.name] = now
        }
        for (s in course.scenes) {
            val d = dropAt[s.name] ?: error("$tag ${s.name} 드롭 없음")
            val off = (d - passedAt.getValue(s.name)).toDouble() / bar
            val limit = if (hint) 1.0 else 1.5
            assertTrue("$tag ${s.name} 통과 시각과 ${"%.2f".format(off)}마디 차이", off in -limit..limit)
            val lead = (d - lineAt.getValue(s.name)).toDouble() / sr
            assertTrue("$tag ${s.name} 대사 후 ${"%.0f".format(lead)}초 만에 드롭", lead >= 12)
        }
        if (hint && course.segments.any { it.jam }) assertTrue("$tag 정체 구간에서 빌드업 반복(${jamBuildBars}마디)", jamBuildBars >= 4)
    }

    @Test
    fun allCourses() {
        for (k in TestRoads.CASES) {
            val c = Composer.compose(k.graph, k.here, Wish.parse(k.wish))?.withJam() ?: continue
            run(c, 1.0, hint = false)
            for (w in listOf(1.0, 10.0, 40.0)) run(c, w, hint = true)
        }
    }
}
