package com.deeptactics.roadie

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocentTest {
    init {
        Docent.stories = Docent.parse(File("src/main/assets/docent.json").readText())
    }

    @Test
    fun namesMatchMap() {
        // 이야기 이름이 지도 지점 이름과 하나라도 어긋나면 영영 안 나온다
        val names = (TestRoads.gangneung.nodes.values + TestRoads.sudogwon.nodes.values).map { it.name }.toSet()
        val missing = Docent.stories.keys - names
        assertTrue("지도에 없는 이름 $missing", missing.isEmpty())
        // 운전 중에 듣기 좋은 길이(20초 남짓)
        for ((k, v) in Docent.stories) assertTrue("$k 이야기가 너무 김", Docent.sec(v) < 26)
    }

    // 서비스처럼 달리면서 틈이 날 때 이야기를 꺼낸다. 이야기 중에는 다음 걸 꺼내지 않는다
    private fun told(c: Course): List<String> {
        val sim = SimulatedDrive(c, 1.0)
        val told = mutableListOf<String>()
        var busyUntil = 0.0
        var t = 0.0
        while (sim.km < c.km && t < 3 * 3600) {
            sim.update(0.5)
            t += 0.5
            if (t < busyUntil) continue
            val p = Docent.pick(c, sim.km, told.toSet(), Docent.gap(c, sim.km, false)) ?: continue
            // 이야기가 끝나기 전에 장면 대사가 시작되면 안 된다
            val next = c.scenes.firstOrNull { it.km > sim.km }
            if (next != null) assertTrue("${p.first} 이야기가 ${next.name} 대사와 겹침", c.planMinutes(sim.km, next.km) * 60 - Arranger.LINE_SEC > Docent.sec(p.second))
            told += p.first
            busyUntil = t + Docent.sec(p.second)
        }
        return told
    }

    @Test
    fun storiesFitBetweenScenes() {
        for (k in TestRoads.CASES) {
            val p = Composer.compose(k.graph, k.here, Wish.parse(k.wish))!!
            val c = p.fork?.let { p.branch(it.planned) } ?: p
            println("${k.name} ${k.wish} → ${c.name}: ${told(c)}")
        }
        val p = Composer.compose(TestRoads.sudogwon, TestRoads.seongnam, Wish.parse("40분 고갯길 신호 적은 길"))!!
        for (ch in listOf(p.fork!!.left, p.fork!!.right)) {
            val c = p.branch(ch)
            val t = told(c)
            println("시연 ${ch.label} ${c.name}: ${c.legs.map { it.to.name }} → $t")
            assertTrue("시연 코스에 이야기가 없음", t.isNotEmpty())
        }
    }
}
