package com.deeptactics.roadie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {
    @Test
    fun parse() {
        val w = Wish.parse("40분, 바다 느낌, 신호 적은 길")
        assertEquals(40, w.minutes)
        assertEquals(setOf(Tag.SEA), w.tags)
        assertTrue(w.fewSignals)
        assertEquals(90, Wish.parse("한 시간 반 정도 산으로").minutes)
        assertEquals(setOf(Tag.MOUNTAIN), Wish.parse("한 시간 반 정도 산으로").tags)
        assertEquals(setOf(Tag.RIVER), Wish.parse("한강 야경 보고 싶어").tags)
        assertTrue(Wish.parse("구불구불한 길").curvy)
    }

    // 어디서 출발해도 지금 위치에서 나가 같은 자리로 돌아오는 이어진 코스여야 한다
    @Test
    fun compose() {
        for (k in TestRoads.CASES) {
            val w = Wish.parse(k.wish)
            val c = Composer.compose(k.graph, k.here, w)
            assertNotNull("${k.name} ${k.wish} 코스 없음", c)
            c!!
            println("경로 " + c.legs.joinToString(" > ") { "${it.from.name}→${it.to.name}(${"%.1f".format(it.edge.km)}${if (it.edge.connector) "*" else ""})" })
            println("${k.name} ${k.wish} → ${c.name} ${"%.0f".format(c.minutes)}분 ${"%.1f".format(c.km)}km 연결 ${"%.0f".format(c.connectorShare * 100)}% 장면 ${c.scenes.joinToString { it.name }} 갈림길 ${c.fork?.let { "${it.node.name} ${it.left.label}/${it.right.label}" }}")
            assertEquals("출발지", k.here.id, c.start.id)
            assertEquals("도착지", k.here.id, c.legs.last().to.id)
            assertTrue("${k.name} ${k.wish} 시간 ${c.minutes}", c.minutes in w.minutes * 0.5..w.minutes * 1.6)
            for (i in 1 until c.legs.size) assertEquals(c.legs[i - 1].to.id, c.legs[i].from.id)
            assertTrue("연결 구간은 처음과 끝에만", c.legs.filterIndexed { i, l -> l.edge.connector && i != 0 && i != c.legs.lastIndex }.isEmpty())
            val f = c.fork ?: continue
            for (ch in listOf(f.left, f.right)) {
                val b = c.branch(ch)
                assertEquals(k.here.id, b.legs.last().to.id)
                assertEquals(f.node.id, b.legs.first { it.fromKm >= f.km - 1e-6 }.from.id)
            }
        }
    }

    @Test
    fun sameWishFromDifferentPlaces() {
        // 출발 위치가 다르면 코스도 달라야 한다(고정 출발지가 아니다)
        val w = Wish.parse("40분 신호 적은 길")
        val a = Composer.compose(TestRoads.sudogwon, TestRoads.seoulCityHall, w)!!
        val b = Composer.compose(TestRoads.sudogwon, TestRoads.seongnam, w)!!
        assertTrue(a.id != b.id)
        assertTrue(a.legs.first().edge.a.id == "here" && b.legs.first().edge.a.id == "here")
    }

    @Test
    fun nothingNearby() {
        // 지도를 준비하지 않은 곳(부산)에서는 코스가 없다
        val busan = Node("here", "지금 위치", 35.1796, 129.0756)
        assertNull(Composer.compose(TestRoads.sudogwon, busan, Wish()))
    }

    @Test
    fun jam() {
        val k = TestRoads.CASES[0]
        val c = Composer.compose(k.graph, k.here, Wish.parse(k.wish))!!.withJam()
        val j = c.segments.singleOrNull { it.jam } ?: return
        assertTrue(c.scenes.any { it.km - j.untilKm in 0.05..0.3 })
        assertEquals(c.km, c.segments.last().untilKm, 1e-9)
    }

    @Test
    fun variety() {
        for (k in TestRoads.CASES) {
            val w = Wish.parse(k.wish)
            val shown = (0 until 4).mapNotNull { Composer.compose(k.graph, k.here, w, skip = it) }
            for (c in shown) println("후보 ${k.name} ${k.wish} → ${c.name} ${"%.0f".format(c.minutes)}분 ${"%.1f".format(c.km)}km 장면 ${c.scenes.joinToString { it.name }} 갈림길 ${c.fork?.let { f -> "${f.node.name} %.1fkm ${f.left.label}/${f.right.label}".format(f.km) }}")
            for (i in shown.indices) for (j in i + 1 until shown.size) {
                assertTrue("${k.wish} ${i}번째와 ${j}번째 코스가 너무 비슷함", Composer.overlap(shown[i], shown[j]) < 0.6)
            }
            for (c in shown) {
                val f = c.fork ?: continue
                assertTrue("${k.wish} 갈림길이 장면과 겹침", Composer.forkClear(c, f.km, strict = false))
            }
        }
    }
}
