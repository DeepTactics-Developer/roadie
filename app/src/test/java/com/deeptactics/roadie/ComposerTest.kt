package com.deeptactics.roadie

import org.junit.Assert.assertEquals
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
        assertTrue(Wish.parse("구불구불한 길").curvy)
    }

    @Test
    fun compose() {
        for (text in ArrangerTest.WISHES) {
            val w = Wish.parse(text)
            val c = Composer.compose(w)
            println("$text → ${c.name} ${"%.0f".format(c.minutes)}분 ${"%.1f".format(c.km)}km 신호${c.signals} 장면 ${c.scenes.joinToString { it.name }} 갈림길 ${c.fork?.let { "${it.node.name} ${it.left.label}/${it.right.label}" }}")
            assertEquals(RoadMap.HOME, c.legs.last().to.id)
            assertTrue("$text 시간", c.minutes in w.minutes * 0.6..w.minutes * 1.5)
            for (i in 1 until c.legs.size) assertEquals(c.legs[i - 1].to.id, c.legs[i].from.id)
            val f = c.fork ?: continue
            for (ch in listOf(f.left, f.right)) {
                val b = c.branch(ch)
                assertEquals(RoadMap.HOME, b.legs.last().to.id)
                assertEquals(f.node.id, b.legs.first { it.fromKm >= f.km - 1e-6 }.from.id)
            }
        }
        if (true) {
            val w = Wish.parse("40분, 바다 느낌, 신호 적은 길")
            assertTrue("바다 비율", Composer.compose(w).share(Tag.SEA) > 0.3)
        }
    }

    @Test
    fun jam() {
        val c = Composer.compose(Wish.parse("40분 바다")).withJam()
        val j = c.segments.single { it.jam }
        assertTrue(c.scenes.any { it.km - j.untilKm in 0.05..0.3 })
        assertEquals(c.km, c.segments.last().untilKm, 1e-9)
    }
}
