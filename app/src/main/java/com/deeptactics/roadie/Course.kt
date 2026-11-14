package com.deeptactics.roadie

// 코스 위의 도로 조각 하나. fromKm은 코스 시작 기준 거리
class Leg(val edge: Edge, val reversed: Boolean, val fromKm: Double) {
    val from get() = if (reversed) edge.b else edge.a
    val to get() = if (reversed) edge.a else edge.b
    val toKm get() = fromKm + edge.km
    val climb get() = if (reversed) -edge.climb else edge.climb

    fun scenes() = edge.features.filter { (!it.oneWay || !reversed) && (!it.reverseOnly || reversed) }.map { f ->
        // 반대로 달리면 위치가 뒤집힌다. 터널은 반대편 입구가 출구가 된다
        val at = if (!reversed) f.at else edge.km - (f.at - f.len)
        // 긴 터널은 빌드업을 끝 쪽 일부에서만 쌓는다
        val tunnelFrom = if (f.len > 0) fromKm + at - minOf(f.len, Course.MAX_TUNNEL_BUILD_KM) else null
        Scene(fromKm + at, f.name, f.kind, f.line, tunnelFrom)
    }

    fun same(o: Leg) = edge === o.edge && reversed == o.reversed
}

// km: 코스 시작 기준 거리, line: 장면 직전에 반려차가 하는 말
// tunnelFrom: 터널 출구 장면이면 터널이 시작되는 지점. 터널 안에서 빌드업을 쌓는다
class Scene(val km: Double, val name: String, val kind: SceneKind, val line: String, val tunnelFrom: Double? = null)

// untilKm 지점까지 kmh로 달리는 구간. jam은 지도에 없는 갑작스러운 정체
class Segment(val untilKm: Double, val kmh: Double, val jam: Boolean = false)

// 갈림길. km 지점(node)에서 left/right 중 하나로 이어 간다. planned는 원래 코스 쪽
class Fork(val km: Double, val node: Node, val left: Choice, val right: Choice, val planned: Choice)

class Choice(val label: String, val tail: List<Leg>)

class Course(val legs: List<Leg>, val segments: List<Segment> = legs.map { Segment(it.toKm, it.edge.kmh) }) {
    var fork: Fork? = null

    val km get() = legs.last().toKm
    val minutes get() = legs.sumOf { it.edge.km / it.edge.kmh * 60 }
    val signals get() = legs.sumOf { it.edge.signals }
    val curves get() = legs.sumOf { it.edge.curves }
    val id get() = legs.joinToString("-") { it.to.id }
    val start get() = legs.first().from
    // 지도 데이터 없이 추정한 연결 구간이 전체에서 차지하는 비율
    val connectorShare get() = legs.filter { it.edge.connector }.sumOf { it.edge.km } / km

    // 장면끼리 너무 붙으면 음악이 못 따라간다. 1.2km 안쪽은 뒤엣것을 뺀다.
    // 클라이맥스가 너무 잦으면 무뎌지니 인상적인 것부터 MAX_SCENES개만
    val scenes: List<Scene> = legs.flatMap { it.scenes() }.sortedBy { it.km }.fold(mutableListOf<Scene>()) { acc, s ->
        if (acc.isEmpty() || s.km - acc.last().km >= 1.2) acc += s
        acc
    }.let { all ->
        val keep = all.sortedBy { PRIORITY.indexOf(it.kind) }.take(MAX_SCENES).toSet()
        val seen = mutableMapOf<String, Int>()
        all.filter { it in keep }.map { sc ->
            // 이름이 같은 장면은 번호를 붙여 구분한다
            val n = seen.merge(sc.name, 1, Int::plus)!!
            if (n == 1) sc else Scene(sc.km, "${sc.name} $n", sc.kind, sc.line, sc.tunnelFrom)
        }
    }

    val name: String
        get() {
            val stops = legs.map { it.to }.filter { it.id != start.id }
            // 처음 들르는 곳과 가장 멀리 가는 곳
            val home = start
            val far = stops.maxByOrNull { (it.lat - home.lat) * (it.lat - home.lat) + (it.lon - home.lon) * (it.lon - home.lon) }
            val picks = listOfNotNull(stops.firstOrNull(), far).distinct()
            return picks.joinToString(" · ") { it.name } + " 한 바퀴"
        }

    // 지도에 그릴 점들
    val points get() = listOf(start) + legs.map { it.to }

    // 내비 경유지는 둘까지. 코스를 셋으로 나눈 지점 근처를 고른다
    val waypoints: List<Node>
        get() {
            val stops = legs.dropLast(1).map { it.to }
            if (stops.size <= 2) return stops
            return listOf(stops[stops.size / 3], stops[stops.size * 2 / 3]).distinct()
        }

    // 시뮬레이션이 미리 1배로 늦출 지점과 그 앞 거리
    val slowPoints: List<Pair<Double, Double>>
        get() = scenes.map { it.km to maxOf(kmAhead(it.km, SLOW_SEC), it.tunnelFrom?.let { t -> it.km - t + 0.3 } ?: 0.0) } +
            listOfNotNull(fork?.let { it.km to forkAskKm() + 0.1 })

    // km 지점에 sec초 전에 닿는 거리(그 앞 구간 속도 기준)
    private fun kmAhead(km: Double, sec: Double) = speedAt(km - 0.01, plan = true) * sec / 3600

    // 갈림길은 이만큼 앞에서 묻는다
    fun forkAskKm() = fork?.let { kmAhead(it.km, FORK_ASK_SEC) } ?: 0.0

    // 바다와 호숫가는 잰 비율, 나머지는 그 성격으로 분류된 조각의 길이 비율
    fun share(tag: Tag) = when (tag) {
        Tag.SEA -> legs.sumOf { it.edge.km * it.edge.sea } / km
        Tag.LAKE -> legs.sumOf { it.edge.km * it.edge.lake } / km
        Tag.RIVER -> legs.sumOf { it.edge.km * it.edge.river } / km
        else -> legs.filter { tag in it.edge.tags }.sumOf { it.edge.km } / km
    }

    // plan이면 정체를 모르는 예상 속도(정체 구간은 바로 앞 구간 속도로)
    fun speedAt(km: Double, plan: Boolean = false): Double {
        val i = segments.indexOfFirst { km < it.untilKm }.let { if (it < 0) segments.lastIndex else it }
        if (!plan) return segments[i].kmh
        return segments.subList(0, i + 1).lastOrNull { !it.jam }?.kmh ?: segments[i].kmh
    }

    // 시연용 정체. afterKm 뒤, 두 번째 이후 장면 바로 앞에 넣는다
    fun withJam(afterKm: Double = 0.0): Course {
        // 정체가 풀리고 장면까지 12초쯤 남게. 그래야 빌드업을 돌다가 바로 드롭으로 간다
        fun end(s: Scene) = s.km - speedAt(s.km - 0.01) * JAM_LEAD_SEC / 3600
        val s = scenes.drop(1).firstOrNull { end(it) - JAM_KM > afterKm } ?: return this
        val to = end(s)
        val from = to - JAM_KM
        val segs = mutableListOf<Segment>()
        var start = 0.0
        for (seg in segments.filter { !it.jam }) {
            if (from > start && from < seg.untilKm) {
                segs += Segment(from, seg.kmh)
                segs += Segment(to, 6.0, jam = true)
                if (seg.untilKm > to) segs += seg
            } else segs += seg
            start = seg.untilKm
        }
        return Course(legs, segs).also { it.fork = fork }
    }

    // a~b km 구간을 지도 속도(정체 모름)로 달리는 데 드는 분
    fun planMinutes(a: Double, b: Double) = legs.sumOf { maxOf(0.0, minOf(b, it.toKm) - maxOf(a, it.fromKm)) / it.edge.kmh * 60 }

    // 시연용 긴 정체. 서서히 막혀서 몇 km를 12km/h로 기어간다.
    // 갈림길 뒤, 앞뒤로 장면이 없는 곳에 둔다. 예정보다 CONGEST_LATE_MIN분쯤 늦어지게 길이를 잡는다
    fun withCongestion(afterKm: Double = 0.0): Course {
        val first = maxOf(afterKm + 0.5, (fork?.km ?: 0.0) + 1.0, legs.first().toKm + 0.3)
        val last = legs.last().fromKm - 0.3
        var from = first
        while (from < last) {
            val v = speedAt(from, plan = true)
            val core = (CONGEST_LATE_MIN / (60 / CONGEST_KMH - 60 / v)).coerceIn(1.2, 3.5)
            val to = from + RAMP_IN_KM + core + RAMP_OUT_KM
            if (to > last) break
            if (scenes.none { it.km in from - 0.6..to + 1.0 }) {
                return splice(from, listOf(
                    Segment(from + RAMP_IN_KM, minOf(25.0, v), jam = true),
                    Segment(from + RAMP_IN_KM + core, CONGEST_KMH, jam = true),
                    Segment(to, minOf(25.0, v), jam = true),
                )).also { it.fork = fork }
            }
            from += 0.1
        }
        return this
    }

    // from부터 pieces로 속도 구간을 갈아 끼운다. 나머지 구간은 그대로
    private fun splice(from: Double, pieces: List<Segment>): Course {
        val to = pieces.last().untilKm
        val out = mutableListOf<Segment>()
        var start = 0.0
        var added = false
        for (s in segments) {
            if (start < from) out += Segment(minOf(s.untilKm, from), s.kmh, s.jam)
            if (!added && s.untilKm > from) {
                out += pieces
                added = true
            }
            if (s.untilKm > to) out += s
            start = s.untilKm
        }
        return Course(legs, out)
    }

    // 막혀서 길을 바꿀 때. 앞의 keep개 조각(지금 달리는 조각까지)은 그대로 두고 뒤를 tail로 바꾼다
    fun detour(keep: Int, tail: List<Pair<Edge, Boolean>>): Course {
        val head = legs.take(keep)
        val end = head.last().toKm
        var at = end
        val t = tail.map { (e, r) -> Leg(e, r, at).also { at = it.toKm } }
        val cut = segments.first { it.untilKm >= end - 1e-9 }
        val segs = segments.takeWhile { it.untilKm < end - 1e-9 } + Segment(end, cut.kmh, cut.jam) + t.map { Segment(it.toKm, it.edge.kmh) }
        return Course(head + t, segs)
    }

    // 갈림길에서 고른 쪽으로 뒤를 바꾼다
    fun branch(choice: Choice): Course {
        val f = fork ?: return this
        val head = legs.takeWhile { it.toKm <= f.km + 1e-6 }
        var at = f.km
        val tail = choice.tail.map { Leg(it.edge, it.reversed, at).also { l -> at = l.toKm } }
        return Course(head + tail)
    }

    companion object {
        const val JAM_KM = 0.03
        const val CONGEST_KMH = 12.0
        const val CONGEST_LATE_MIN = 10.0
        const val RAMP_IN_KM = 0.5
        const val RAMP_OUT_KM = 0.4
        const val MAX_SCENES = 3
        const val MAX_TUNNEL_BUILD_KM = 1.2
        val PRIORITY = listOf(SceneKind.TUNNEL_EXIT, SceneKind.PASS, SceneKind.SEA, SceneKind.RIVER, SceneKind.LAKE, SceneKind.VIEW, SceneKind.BRIDGE)
        const val JAM_LEAD_SEC = 12.0
        const val FORK_ASK_SEC = 25.0
        const val SLOW_SEC = 45.0 // 장면 앞 이만큼은 1배속. 대사(35초 전)와 빌드업이 들어간다
    }
}
