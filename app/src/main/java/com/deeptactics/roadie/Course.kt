package com.deeptactics.roadie

// 코스 위의 도로 조각 하나. fromKm은 코스 시작 기준 거리
class Leg(val edge: Edge, val reversed: Boolean, val fromKm: Double) {
    val from get() = RoadMap.node(if (reversed) edge.b else edge.a)
    val to get() = RoadMap.node(if (reversed) edge.a else edge.b)
    val toKm get() = fromKm + edge.km
    val climb get() = if (reversed) -edge.climb else edge.climb

    fun scenes() = edge.features.filter { !it.oneWay || !reversed }.map { f ->
        // 반대로 달리면 위치가 뒤집힌다. 터널은 반대편 입구가 출구가 된다
        val at = if (!reversed) f.at else edge.km - (f.at - f.len)
        val tunnelFrom = if (f.len > 0) fromKm + at - f.len else null
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

    // 장면끼리 너무 붙으면 음악이 못 따라간다. 1.2km 안쪽은 뒤엣것을 뺀다
    val scenes: List<Scene> = legs.flatMap { it.scenes() }.sortedBy { it.km }.fold(mutableListOf()) { acc, s ->
        if (acc.isEmpty() || s.km - acc.last().km >= 1.2) acc += s
        acc
    }

    val name: String
        get() {
            val stops = legs.map { it.to }.filter { it.id != RoadMap.HOME }
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

    fun share(tag: Tag) = legs.filter { tag in it.edge.tags }.sumOf { it.edge.km } / km

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

    // 갈림길에서 고른 쪽으로 뒤를 바꾼다
    fun branch(choice: Choice): Course {
        val f = fork ?: return this
        val head = legs.takeWhile { it.toKm <= f.km + 1e-6 }
        var at = f.km
        val tail = choice.tail.map { Leg(it.edge, it.reversed, at).also { l -> at = l.toKm } }
        return Course(head + tail)
    }

    companion object {
        const val JAM_KM = 0.05
        const val JAM_LEAD_SEC = 12.0
        const val FORK_ASK_SEC = 25.0
        const val SLOW_SEC = 45.0 // 장면 앞 이만큼은 1배속. 대사(35초 전)와 빌드업이 들어간다
    }
}
