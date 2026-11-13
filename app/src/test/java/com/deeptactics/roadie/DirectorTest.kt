package com.deeptactics.roadie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 시뮬레이션 주행으로 코스를 끝까지 돌리면서 클라이맥스 타이밍을 본다
class DirectorTest {
    private fun run(course: Course, warp: Double, hint: Boolean) {
        val sim = SimulatedDrive(course, warp)
        val approachAt = mutableMapOf<String, Long>()
        val climaxAt = mutableMapOf<String, Long>()
        val passedAt = mutableMapOf<String, Long>()
        var now = 0L
        lateinit var d: Director
        d = Director(course.scenes, { approachAt[it.name] = now }, { climaxAt[it.name] = d.climaxAt })
        val step = Director.SR / 20L // 50ms
        var sawWaitInJam = false
        while (sim.km < course.km && now < 3600L * Director.SR) {
            sim.update(step.toDouble() / Director.SR)
            now += step
            d.update(now, sim.km, sim.speedKmh, if (hint) sim::etaSec else null)
            for (s in course.scenes) if (sim.km >= s.km && s.name !in passedAt) passedAt[s.name] = now
            if (sim.speedKmh < 10 && sim.km > 1 && d.state == Director.State.APPROACH) sawWaitInJam = true
        }
        assertEquals(course.km, sim.km, 1e-9)
        for (s in course.scenes) {
            val c = climaxAt[s.name] ?: error("${course.id} ${s.name} 클라이맥스 없음")
            assertEquals("마디 경계", 0L, c % Director.BAR)
            val off = (c - passedAt.getValue(s.name)).toDouble() / Director.BAR
            assertTrue("${s.name} 통과 시각과 ${"%.2f".format(off)}마디 차이", off in -1.0..1.0)
            val lead = (c - approachAt.getValue(s.name)).toDouble() / Director.SR
            assertTrue("${s.name} 대사 후 ${"%.0f".format(lead)}초 만에 클라이맥스", lead >= 40)
            if (hint) assertTrue("${s.name} 접근 ${"%.0f".format(lead)}초", lead <= 90)
        }
        if (course.id == "sea") assertTrue("정체 구간에서 접근 상태 유지", sawWaitInJam)
    }

    @Test
    fun allCourses() {
        for (c in COURSES) {
            run(c, 1.0, hint = false) // 실제 차량처럼 평균 속도만으로
            for (w in listOf(1.0, 10.0, 20.0)) run(c, w, hint = true)
        }
    }
}
