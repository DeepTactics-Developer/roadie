package com.deeptactics.roadie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    @Test
    fun polyline() {
        // tools/geo.py encode([(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)])와 같은 값
        val p = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(6, p.size)
        assertEquals(40.7, p[2], 1e-6)
        assertEquals(-126.453, p[5], 1e-6)
    }

    private val demo by lazy { Composer.compose(TestRoads.sudogwon, TestRoads.seongnam, Wish.parse("40분 고갯길 신호 적은 길"))!! }

    @Test
    fun trackFollowsRoad() {
        for (k in TestRoads.CASES) {
            val c = Composer.compose(k.graph, k.here, Wish.parse(k.wish))!!
            val t = c.track
            // 도로 모양이 실제로 실렸다(직선만 있으면 점이 조각 수 + 1개)
            assertTrue("${k.name} 모양 없음", t.lat.size > c.legs.size * 3)
            // 처음과 끝은 지금 위치, 중간 지점은 그 지점 근처
            val (la0, lo0) = t.at(0.0)
            assertTrue(Node("", "", la0, lo0).km(k.here) < 0.01)
            for (l in c.legs) {
                val (la, lo) = t.at(l.toKm)
                assertTrue("${l.to.name}에서 ${Node("", "", la, lo).km(l.to)}km 어긋남", Node("", "", la, lo).km(l.to) < 1.0) // 지점이 호수 한가운데처럼 길에서 떨어져 있을 수 있다
            }
            for (i in 1 until t.km.size) assertTrue(t.km[i] >= t.km[i - 1])
        }
    }

    @Test
    fun waypointsFollowCourse() {
        for (k in TestRoads.CASES) {
            val c = Composer.compose(k.graph, k.here, Wish.parse(k.wish))!!
            val w = Composer.waypoints(k.graph, c)
            println("${k.name} ${c.name} 경유지 ${w.map { "${it.name} %.1fkm".format(it.km) }} 장면 ${c.scenes.map { it.name }}")
            assertEquals(2, w.size)
            assertTrue(w[0].km < w[1].km)
            // 경유지 좌표는 코스 위
            for (wp in w) {
                val (la, lo) = c.track.at(wp.km)
                assertEquals(la, wp.lat, 1e-9)
                assertEquals(lo, wp.lon, 1e-9)
            }
        }
    }

    @Test
    fun syncFromNavi() {
        val c = demo
        c.navWaypoints = Composer.waypoints(TestRoads.sudogwon, c)
        val w = c.waypoints.first()
        assertEquals(w.km - 1.2, c.syncKm(w.name, 1200)!!, 1e-9)
        assertNull("멀면 안 믿는다", c.syncKm(w.name, 8000))
        assertNull("모르는 이름", c.syncKm("없는 곳", 100))
        assertNotNull(c.syncKm(w.name, 0))
    }
}
