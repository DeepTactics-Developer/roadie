package com.deeptactics.roadie

import kotlin.math.abs

// 말로 한 바람. "40분, 바다 느낌, 신호 적은 길"
class Wish(
    val minutes: Int = 40,
    val tags: Set<Tag> = emptySet(),
    val fewSignals: Boolean = false,
    val curvy: Boolean = false,
    val quiet: Boolean = false,
) {
    fun summary() = listOfNotNull(
        "${minutes}분",
        tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.word },
        "신호 적은 길".takeIf { fewSignals },
        "굽이진 길".takeIf { curvy },
        "한적한 길".takeIf { quiet },
    ).joinToString(" · ")

    companion object {
        // 규칙으로 알아듣는다. 못 알아들은 건 기본값
        fun parse(text: String): Wish {
            val t = text.replace(" ", "")
            var min = Regex("(\\d+)분").find(t)?.groupValues?.get(1)?.toInt()
            val hour = Regex("(\\d+|한|두|세)시간(반)?").find(t)
            if (hour != null) {
                val h = when (val v = hour.groupValues[1]) { "한" -> 1; "두" -> 2; "세" -> 3; else -> v.toInt() }
                min = h * 60 + (if (hour.groupValues[2].isNotEmpty()) 30 else 0) + (min ?: 0)
            } else if ("반시간" in t) min = 30
            val tags = mutableSetOf<Tag>()
            if (Regex("바다|해변|해안|파도|오션|동해").containsMatchIn(t)) tags += Tag.SEA
            if (Regex("호수|호숫가|경포호").containsMatchIn(t)) tags += Tag.LAKE
            if (Regex("산|고개|고갯길|숲|계곡|대관령|와인딩").containsMatchIn(t)) tags += Tag.MOUNTAIN
            if (Regex("시내|도심|도시").containsMatchIn(t)) tags += Tag.CITY
            return Wish(
                minutes = (min ?: 40).coerceIn(15, 120),
                tags = tags,
                fewSignals = Regex("신호(가|등)?(적|없|덜|안)").containsMatchIn(t),
                curvy = Regex("커브|굽이|구불|와인딩|S자|코너").containsMatchIn(t),
                quiet = Regex("한적|조용|여유|느긋").containsMatchIn(t),
            )
        }
    }
}

// 도로 조각을 이어서 출발지로 돌아오는 순환 코스를 만든다
object Composer {
    private val loops: List<List<Leg>> by lazy { findLoops() }

    // 강릉역에서 나가서 같은 노드를 두 번 안 지나고 돌아오는 길을 전부 찾는다. 조각이 20개 남짓이라 금방이다
    private fun findLoops(): List<List<Leg>> {
        val out = mutableListOf<List<Leg>>()
        fun dfs(at: String, path: List<Leg>, seen: Set<String>, km: Double) {
            if (path.size > 8) return
            for (e in RoadMap.edges) {
                val rev = e.b == at
                if (e.a != at && !rev) continue
                if (path.any { it.edge === e }) continue
                val next = if (rev) e.a else e.b
                val leg = Leg(e, rev, km)
                if (next == RoadMap.HOME) {
                    if (path.isNotEmpty()) out += path + leg
                } else if (next !in seen) dfs(next, path + leg, seen + next, leg.toKm)
            }
        }
        dfs(RoadMap.HOME, emptyList(), setOf(RoadMap.HOME), 0.0)
        return out
    }

    fun score(c: Course, w: Wish, taste: Map<Tag, Double> = emptyMap()): Double {
        val target = w.minutes.toDouble()
        val off = abs(c.minutes - target) / target
        var s = -off * off * 12 - off * 2 // 많이 벗어날수록 더 크게 깎는다
        if (w.tags.isNotEmpty()) s += w.tags.sumOf { c.share(it) } * 5 + c.scenes.count { it.kind in kinds(w.tags) } * 0.4
        s += minOf(c.scenes.size, 4) * 0.3
        s -= c.signals * if (w.fewSignals || w.quiet) 0.12 else 0.03
        if (w.curvy) s += c.curves * 0.12
        if (w.quiet) s -= c.share(Tag.CITY) * 2
        s += Tag.entries.sumOf { c.share(it) * (taste[it] ?: 0.0) }
        return s
    }

    private fun kinds(tags: Set<Tag>) = tags.flatMap {
        when (it) {
            Tag.SEA -> listOf(SceneKind.SEA, SceneKind.TUNNEL_EXIT)
            Tag.LAKE -> listOf(SceneKind.LAKE)
            Tag.MOUNTAIN -> listOf(SceneKind.PASS, SceneKind.VIEW)
            Tag.CITY -> listOf(SceneKind.BRIDGE)
        }
    }

    // 점수 순으로 줄 세운 코스. skip번째를 준다("다른 코스")
    fun compose(w: Wish, taste: Map<Tag, Double> = emptyMap(), skip: Int = 0): Course {
        val ranked = loops.map { Course(it) }
            .filter { it.scenes.isNotEmpty() }
            .sortedByDescending { score(it, w, taste) }
        val c = ranked[skip % ranked.size]
        c.fork = fork(c, w, taste)
        return c
    }

    // 코스 중간에서 성격이 다른 길로 갈라지는 지점을 하나 찾는다
    fun fork(c: Course, w: Wish, taste: Map<Tag, Double> = emptyMap()): Fork? {
        for (i in 1 until c.legs.size) {
            val at = c.legs[i].fromKm
            if (at < 2.0) continue
            val head = c.legs.subList(0, i)
            val mine = label(c.legs.drop(i))
            val alt = loops.asSequence()
                .filter { it.size > i && head.indices.all { k -> it[k].same(head[k]) } && !it[i].same(c.legs[i]) }
                .map { Course(it) }
                .filter { it.minutes in w.minutes * 0.6..w.minutes * 1.5 && label(it.legs.drop(i)) != mine && it.scenes.isNotEmpty() }
                .maxByOrNull { score(it, w, taste) } ?: continue
            val planned = Choice(mine, c.legs.drop(i))
            val other = Choice(label(alt.legs.drop(i)), alt.legs.drop(i))
            // 왼쪽 오른쪽은 연출. 갈림길마다 고정되게 위치로 정한다
            val plannedLeft = c.legs[i].from.lon < c.legs[i].to.lon
            return Fork(at, c.legs[i].from, if (plannedLeft) planned else other, if (plannedLeft) other else planned, planned)
        }
        return null
    }

    // 갈라진 뒤 길의 성격. 시내가 아닌 첫 태그
    private fun label(tail: List<Leg>): String {
        for (l in tail.take(2)) {
            val t = listOf(Tag.LAKE, Tag.SEA, Tag.MOUNTAIN).firstOrNull { it in l.edge.tags }
            if (t != null) return t.word
        }
        return Tag.CITY.word
    }
}
