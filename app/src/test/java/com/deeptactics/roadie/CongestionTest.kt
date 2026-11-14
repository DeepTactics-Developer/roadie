package com.deeptactics.roadie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CongestionTest {
    // 서비스와 같은 기준으로 언제 묻는지 흉내 낸다
    private fun askPoint(c: Course, warp: Double = 1.0): Pair<Double, Double>? {
        val sim = SimulatedDrive(c, warp)
        var t = 0.0
        while (sim.km < c.km && t < 3 * 3600) {
            sim.update(0.05)
            t += 0.05 * sim.timeScale()
            val late = t - c.planMinutes(0.0, sim.km) * 60
            if (sim.speedKmh < 20 && late > maxOf(240.0, c.minutes * 60 * 0.12)) return sim.km to late
        }
        return null
    }

    @Test
    fun congestionLeadsToChoice() {
        for (k in TestRoads.CASES) {
            val plan = Composer.compose(k.graph, k.here, Wish.parse(k.wish))!!
            val c0 = plan.fork?.let { plan.branch(it.planned) } ?: plan
            val c = c0.withJam().withCongestion(plan.fork?.km ?: 0.0)
            val jams = c.segments.filter { it.jam && it.kmh == Course.CONGEST_KMH }
            if (jams.isEmpty()) {
                println("${k.name} ${k.wish}: 정체 넣을 자리 없음")
                continue
            }
            // 구간이 이어져 있고 끝이 코스 끝과 같아야 한다
            for (i in 1 until c.segments.size) assertTrue(c.segments[i].untilKm >= c.segments[i - 1].untilKm)
            assertEquals(c.km, c.segments.last().untilKm, 1e-9)
            val j = c.segments.indexOfFirst { it.jam && it.kmh == Course.CONGEST_KMH }
            val span = c.segments[j - 1].untilKm..c.segments[j].untilKm
            assertTrue("정체 구간에 장면 없음", c.scenes.none { it.km in span })

            val (km, late) = askPoint(c) ?: error("${k.name} ${k.wish}: 정체를 겪어도 안 물음")
            val budget = c.planMinutes(km, c.km) - late / 60
            val d = Composer.shortcut(k.graph, c, km, budget)
            println("${k.name} ${k.wish}: ${"%.1f".format(km)}km에서 ${"%.0f".format(late / 60)}분 늦음, 남은 ${"%.0f".format(c.planMinutes(km, c.km))}분 → " +
                (d?.let { "바꾸면 ${"%.0f".format(it.planMinutes(km, it.km))}분, 장면 ${it.scenes.filter { s -> s.km > km }.map { s -> s.name }}" } ?: "바꿀 길 없음"))
            if (d == null) continue
            assertEquals(k.here.id, d.legs.last().to.id)
            for (i in 1 until d.legs.size) assertEquals(d.legs[i - 1].to.id, d.legs[i].from.id)
            assertEquals(d.km, d.segments.last().untilKm, 1e-9)
            assertTrue(d.planMinutes(km, d.km) < c.planMinutes(km, c.km) - 3)
        }
        // 시연하는 성남 기본 코스에서는 갈림길에서 어느 쪽을 골라도 물어야 한다
        val p = Composer.compose(TestRoads.sudogwon, TestRoads.seongnam, Wish.parse("40분 고갯길 신호 적은 길"))!!
        val f = p.fork!!
        for (ch in listOf(f.left, f.right)) {
            val b = p.branch(ch)
            println("시연 ${ch.label}: ${b.name} ${"%.1f".format(b.km)}km 갈림길 ${"%.1f".format(f.km)} 장면 ${b.scenes.map { "${it.name} ${"%.1f".format(it.km)}" }} 조각 ${b.legs.map { "%.1f".format(it.toKm) }}")
            val c = b.withJam(f.km).withCongestion(f.km)
            println("  40배속 ${askPoint(c, 40.0)}")
            val (km, late) = askPoint(c) ?: error("${ch.label} 쪽은 안 물음")
            println("  ${"%.1f".format(km)}km에서 물음, ${"%.0f".format(late / 60)}분 늦음")
            val d = Composer.shortcut(TestRoads.sudogwon, c, km, c.planMinutes(km, c.km) - late / 60)
            println("  바꾸면 ${d?.name} 남은 ${d?.let { "%.0f".format(it.planMinutes(km, it.km)) }}분 장면 ${d?.scenes?.filter { it.km > km }?.map { it.name }}")
            assertNotNull(d)
        }
    }
}
