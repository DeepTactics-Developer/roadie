package com.deeptactics.roadie

// 강릉 일대 도로 조각. 값은 RoadData.kt에 있고 tools/build_roads.py가 실제 지도 데이터로 재서 만든다
enum class Tag(val word: String) { SEA("바다"), LAKE("호숫가"), MOUNTAIN("고갯길"), CITY("시내") }

enum class SceneKind { TUNNEL_EXIT, SEA, PASS, BRIDGE, LAKE, VIEW }

class Node(val id: String, val name: String, val lat: Double, val lon: Double)

// at: 조각 시작(a)에서의 거리. 터널 출구면 at이 출구, len이 터널 길이. oneWay는 a→b 방향일 때만, reverseOnly는 b→a 방향일 때만 보인다.
// 터널이 아닌 장면은 a→b일 땐 at이 구간 시작, b→a일 땐 reverseOnly 쪽 at이 구간 끝(거꾸로 가면 그게 시작)
class Feature(
    val at: Double,
    val kind: SceneKind,
    val name: String,
    val line: String,
    val len: Double = 0.0,
    val oneWay: Boolean = false,
    val reverseOnly: Boolean = false,
)

class Edge(
    val a: String,
    val b: String,
    val road: String,
    val km: Double,
    val kmh: Double,
    val signals: Int,
    val curves: Int, // S자나 헤어핀 같은 굽이 수
    val climb: Int, // a→b로 갈 때 오르는 높이(m). 내려가면 음수
    val tags: Set<Tag>,
    val features: List<Feature> = emptyList(),
)

object RoadMap {
    const val HOME = "home"

    val nodes = RoadData.nodes.associateBy { it.id }
    val edges = RoadData.edges

    fun node(id: String) = nodes.getValue(id)
}
