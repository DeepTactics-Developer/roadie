package com.deeptactics.roadie

import kotlin.math.abs
import kotlin.math.max

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
            if (Regex("바다|해변|해안|파도|오션|동해|서해").containsMatchIn(t)) tags += Tag.SEA
            if (Regex("호수|호숫가|경포호").containsMatchIn(t)) tags += Tag.LAKE
            if (Regex("한강|강변|강가|리버|야경").containsMatchIn(t)) tags += Tag.RIVER
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

// 지금 위치에서 나가 도로 조각을 이어 같은 자리로 돌아오는 코스를 만든다.
// 지금 위치와 첫 지점, 마지막 지점과 지금 위치 사이는 지도 데이터가 없어서 직선거리로 추정한 연결 구간으로 둔다
object Composer {
    private const val SAME_COURSE = 0.6
    private const val FORK_LOOKAHEAD = 20
    private const val FORK_BONUS = 0.8
    private const val MAX_ENTRY = 5
    private const val ENTRY_SLACK_KM = 2.5
    private const val EXIT_SLACK_KM = 4.0
    private const val MAX_DEPTH = 6
    private const val MAX_CANDIDATES = 3000
    private const val CONNECTOR_KMH = 35.0
    private const val DETOUR = 1.3 // 직선거리 대비 도로가 돌아가는 정도
    private const val SAVE_MIN = 3.0

    // 한 번 만든 후보들. 갈림길 대안도 여기서 찾는다
    class Pool(val here: Node, val courses: List<Course>)

    fun candidates(g: Graph, here: Node, w: Wish): Pool {
        val radius = (w.minutes * 0.6).coerceIn(8.0, 60.0)
        val minMin = w.minutes * 0.5
        val maxMin = w.minutes * 1.6
        val conns = HashMap<String, Edge>()
        fun conn(a: Node, b: Node) = conns.getOrPut(a.id + ">" + b.id) { connector(a, b) }

        val out = ArrayList<Course>()
        val near = g.near(here, radius)
        if (near.isEmpty()) return Pool(here, emptyList())
        // 지도 데이터가 없는 연결 구간이 길어지지 않게, 가장 가까운 지점 근처에서만 들어가고 나온다
        val d0 = near.first().second
        val entries = near.filter { it.second <= d0 + ENTRY_SLACK_KM }.take(MAX_ENTRY).map { it.first }
        val exitMaxKm = d0 + EXIT_SLACK_KM
        for (entry in entries) {
            val cin = conn(here, entry)
            fun dfs(at: Node, path: List<Pair<Edge, Boolean>>, seen: Set<String>, minutes: Double) {
                for ((e, rev) in g.adj[at.id].orEmpty()) {
                    if (out.size >= MAX_CANDIDATES) return
                    val next = if (rev) e.a else e.b
                    if (next.id in seen || next.km(here) > radius * 1.3) continue
                    val m = minutes + e.km / e.kmh * 60
                    val cout = conn(next, here)
                    if (m + cout.km / cout.kmh * 60 > maxMin) continue
                    val np = path + (e to rev)
                    if (next.km(here) <= exitMaxKm && m + cout.km / cout.kmh * 60 >= minMin && np.any { it.first.features.isNotEmpty() }) out += build(cin, np, cout)
                    if (np.size < MAX_DEPTH) dfs(next, np, seen + next.id, m)
                }
            }
            dfs(entry, emptyList(), setOf(entry.id), cin.km / cin.kmh * 60)
        }
        return Pool(here, out.filter { it.scenes.isNotEmpty() })
    }

    private fun build(cin: Edge, path: List<Pair<Edge, Boolean>>, cout: Edge): Course {
        var at = 0.0
        val legs = ArrayList<Leg>()
        for ((e, r) in listOf(cin to false) + path + (cout to false)) legs += Leg(e, r, at).also { at = it.toKm }
        return Course(legs)
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
        s -= c.connectorShare * 2.5 // 분석 안 된 연결 구간이 길수록 깎는다
        s += Tag.entries.sumOf { c.share(it) * (taste[it] ?: 0.0) }
        return s
    }

    private fun kinds(tags: Set<Tag>) = tags.flatMap {
        when (it) {
            Tag.SEA -> listOf(SceneKind.SEA, SceneKind.TUNNEL_EXIT)
            Tag.LAKE -> listOf(SceneKind.LAKE)
            Tag.RIVER -> listOf(SceneKind.RIVER, SceneKind.BRIDGE)
            Tag.MOUNTAIN -> listOf(SceneKind.PASS, SceneKind.VIEW)
            Tag.CITY -> listOf(SceneKind.BRIDGE)
        }
    }

    // 점수 순으로 줄 세우되, 앞서 나온 코스와 길이 많이 겹치면(같은 길을 거꾸로 도는 것 포함) 건너뛴다.
    // skip번째를 준다("다른 코스"). 근처에 코스가 없으면 null
    fun compose(g: Graph, here: Node, w: Wish, taste: Map<Tag, Double> = emptyMap(), skip: Int = 0): Course? {
        val pool = candidates(g, here, w)
        val scored = pool.courses.map { it to score(it, w, taste) }.sortedByDescending { it.second }
        if (scored.isEmpty()) return null
        // 갈림길이 있는 코스가 이야기가 되니, 앞쪽 후보에는 가산점을 준다
        val head = scored.take(FORK_LOOKAHEAD).map { (c, sc) ->
            c.fork = fork(pool, c, w, taste)
            c to sc + if (c.fork != null) FORK_BONUS else 0.0
        }
        val ranked = (head + scored.drop(FORK_LOOKAHEAD)).sortedByDescending { it.second }.map { it.first }
        val picks = mutableListOf<Course>()
        for (c in ranked) {
            if (picks.none { overlap(it, c) >= SAME_COURSE }) picks += c
            if (picks.size > skip) break
        }
        val c = picks.getOrNull(skip) ?: picks[skip % picks.size]
        if (c.fork == null) c.fork = fork(pool, c, w, taste)
        return c
    }

    // 두 코스가 같은 길 조각을 얼마나 나눠 쓰는지 0~1
    fun overlap(a: Course, b: Course): Double {
        // 연결 구간은 출발 위치마다 새로 생기는 것이라 빼고 비교한다
        val x = a.legs.filter { !it.edge.connector }.map { it.edge }.toSet()
        val y = b.legs.filter { !it.edge.connector }.map { it.edge }.toSet()
        return if (x.isEmpty() || y.isEmpty()) 0.0 else x.intersect(y).size.toDouble() / x.union(y).size
    }

    // 갈림길 질문은 장면 대사나 드롭과 겹치면 안 된다. 앞쪽 80초, 뒤쪽 40초 안에 장면이 없어야 한다.
    // 그런 자리가 없으면 50초, 25초까지 줄여서 다시 찾는다
    fun forkClear(c: Course, at: Double, strict: Boolean = true): Boolean {
        val v = c.speedAt(at, plan = true) / 3600
        val (ahead, behind) = if (strict) 80 to 40 else 50 to 25
        return c.scenes.none { it.km - at in -v * behind..v * ahead }
    }

    // 코스 중간에서 성격이 다른 길로 갈라지는 지점을 하나 찾는다
    fun fork(pool: Pool, c: Course, w: Wish, taste: Map<Tag, Double> = emptyMap()): Fork? {
        for (strict in listOf(true, false)) for (i in 1 until c.legs.size) {
            val at = c.legs[i].fromKm
            if (at < 2.0 || at > c.km - 3.0 || !forkClear(c, at, strict)) continue
            val head = c.legs.subList(0, i)
            val mine = label(c.legs.drop(i))
            val alt = pool.courses.asSequence()
                .filter { it.legs.size > i && head.indices.all { k -> it.legs[k].same(head[k]) } && !it.legs[i].same(c.legs[i]) }
                .filter { it.minutes in w.minutes * 0.6..w.minutes * 1.5 && label(it.legs.drop(i)) != mine }
                .maxByOrNull { score(it, w, taste) } ?: continue
            val planned = Choice(mine, c.legs.drop(i))
            val other = Choice(label(alt.legs.drop(i)), alt.legs.drop(i))
            // 왼쪽 오른쪽은 연출. 갈림길마다 고정되게 위치로 정한다
            val plannedLeft = c.legs[i].from.lon < c.legs[i].to.lon
            return Fork(at, c.legs[i].from, if (plannedLeft) planned else other, if (plannedLeft) other else planned, planned)
        }
        return null
    }

    // 막혀서 늦어질 때 예정 시간에 맞춰 돌아오는 길. 지금 달리는 조각 끝에서 갈라진다.
    // 남은 예상 시간이 budgetMin 안에 드는 것 중 장면이 많이 남는 것(같으면 오래 달리는 것), 그런 게 없으면 가장 빠른 것.
    // 원래 길보다 SAVE_MIN분 넘게 줄지 않으면 null
    fun shortcut(g: Graph, c: Course, km: Double, budgetMin: Double): Course? {
        val i = c.legs.indexOfFirst { km < it.toKm }
        if (i < 0 || i >= c.legs.lastIndex) return null
        val from = c.legs[i].to
        val here = c.start
        if (from.id !in g.nodes) return null
        val passed = c.legs.take(i + 1).map { it.from.id }.toSet()
        val exitMaxKm = (g.near(here, Double.MAX_VALUE).firstOrNull()?.second ?: return null) + EXIT_SLACK_KM

        // 지나온 곳은 다시 안 지나게 하고 시간 기준 최단 경로
        val dist = hashMapOf(from.id to 0.0)
        val prev = HashMap<String, Pair<Edge, Boolean>>()
        val done = HashSet<String>()
        while (true) {
            val u = dist.entries.filter { it.key !in done }.minByOrNull { it.value }?.key ?: break
            done += u
            for ((e, rev) in g.adj[u].orEmpty()) {
                val v = if (rev) e.a else e.b
                if (v.id in passed) continue
                val d = dist.getValue(u) + e.km / e.kmh * 60
                if (d < (dist[v.id] ?: Double.MAX_VALUE)) {
                    dist[v.id] = d
                    prev[v.id] = e to rev
                }
            }
        }

        val rest = c.planMinutes(km, c.legs[i].toKm)
        val options = done.map { g.nodes.getValue(it) }.filter { it.km(here) <= exitMaxKm }.map { exit ->
            val path = ArrayList<Pair<Edge, Boolean>>()
            var at = exit.id
            while (at != from.id) {
                val step = prev.getValue(at)
                path.add(0, step)
                at = if (step.second) step.first.b.id else step.first.a.id
            }
            val out = connector(exit, here)
            c.detour(i + 1, path + (out to false)) to rest + dist.getValue(exit.id) + out.km / out.kmh * 60
        }
        val pick = options.filter { it.second <= budgetMin }.maxWithOrNull(compareBy({ o -> o.first.scenes.count { it.km > km } }, { it.second }))
            ?: options.minByOrNull { it.second } ?: return null
        if (c.planMinutes(km, c.km) - pick.second < SAVE_MIN) return null
        return pick.first
    }

    private fun connector(a: Node, b: Node) =
        Edge(a, b, "연결 구간", max(0.05, a.km(b) * DETOUR), CONNECTOR_KMH, 0, 0, 0, emptySet(), connector = true)

    // 갈라진 뒤 길의 성격. 시내가 아닌 첫 태그
    private fun label(tail: List<Leg>): String {
        for (l in tail.take(2)) {
            val t = listOf(Tag.LAKE, Tag.SEA, Tag.RIVER, Tag.MOUNTAIN).firstOrNull { it in l.edge.tags }
            if (t != null) return t.word
        }
        return Tag.CITY.word
    }
}
