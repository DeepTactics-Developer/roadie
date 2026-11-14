package com.deeptactics.roadie

// 강릉 일대 도로를 조각으로 나눈 데이터. 거리, 신호 수, 커브, 장면 위치는 연출용으로 직접 넣은 값이라 실제와 다를 수 있다
enum class Tag(val word: String) { SEA("바다"), LAKE("호숫가"), MOUNTAIN("고갯길"), CITY("시내") }

enum class SceneKind { TUNNEL_EXIT, SEA, PASS, BRIDGE, LAKE, VIEW }

class Node(val id: String, val name: String, val lat: Double, val lon: Double)

// at: 조각 시작(a)에서의 거리. 터널 출구면 len이 터널 길이. oneWay면 a→b 방향으로 갈 때만 보인다
class Feature(val at: Double, val kind: SceneKind, val name: String, val line: String, val len: Double = 0.0, val oneWay: Boolean = false)

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

    val nodes = listOf(
        Node(HOME, "강릉역", 37.7637, 128.8996),
        Node("ojuk", "오죽헌", 37.7792, 128.8786),
        Node("lake", "경포호", 37.7955, 128.9020),
        Node("gbeach", "경포해변", 37.8055, 128.9078),
        Node("sacheon", "사천진해변", 37.8410, 128.8770),
        Node("yeongok", "연곡", 37.8606, 128.8402),
        Node("anmok", "안목해변", 37.7722, 128.9476),
        Node("namhang", "남항진", 37.7699, 128.9512),
        Node("jdj", "정동진", 37.6912, 129.0341),
        Node("simgok", "심곡항", 37.6516, 129.0450),
        Node("seongsan", "대관령 옛길 입구", 37.7158, 128.8296),
        Node("pass", "대관령 마루", 37.6876, 128.7525),
    ).associateBy { it.id }

    val edges = listOf(
        Edge(HOME, "ojuk", "율곡로", 3.0, 40.0, 6, 0, 5, setOf(Tag.CITY)),
        Edge(HOME, "lake", "경포로", 4.5, 45.0, 7, 0, 0, setOf(Tag.CITY, Tag.LAKE),
            listOf(Feature(4.2, SceneKind.LAKE, "경포호 수면", "왼쪽 창 봐. 호수 나온다.", oneWay = true))),
        Edge("ojuk", "lake", "경포호수길", 4.0, 40.0, 3, 2, 0, setOf(Tag.LAKE),
            listOf(Feature(2.6, SceneKind.LAKE, "경포호 둘레길", "호수 옆으로 바짝 붙는다."))),
        Edge("lake", "gbeach", "경포 진입로", 1.6, 40.0, 2, 0, 0, setOf(Tag.LAKE, Tag.SEA),
            listOf(Feature(1.4, SceneKind.SEA, "경포 바다", "소나무 사이로 바다 보인다.", oneWay = true))),
        Edge("gbeach", "sacheon", "해안로", 4.6, 50.0, 1, 2, 0, setOf(Tag.SEA),
            listOf(Feature(3.2, SceneKind.SEA, "사근진 해안", "바다 바로 옆으로 붙는다."))),
        Edge("sacheon", "yeongok", "해안로", 3.6, 55.0, 1, 1, 10, setOf(Tag.SEA)),
        Edge("yeongok", "ojuk", "연곡천로", 9.0, 60.0, 2, 3, 20, setOf(Tag.MOUNTAIN)),
        Edge("gbeach", "anmok", "창해로", 5.5, 45.0, 5, 1, 0, setOf(Tag.SEA, Tag.CITY),
            listOf(Feature(1.2, SceneKind.BRIDGE, "강문 솟대다리", "다리 건널 때 양쪽 다 봐."))),
        Edge(HOME, "anmok", "경강로", 5.0, 45.0, 8, 0, 0, setOf(Tag.CITY)),
        Edge("anmok", "namhang", "솔바람다리", 1.2, 30.0, 1, 0, 0, setOf(Tag.SEA),
            listOf(Feature(0.8, SceneKind.BRIDGE, "솔바람다리", "다리 위에서 항구 한번 봐."))),
        Edge("namhang", "jdj", "율곡로 해안", 16.0, 70.0, 3, 4, 40, setOf(Tag.SEA),
            listOf(Feature(11.0, SceneKind.TUNNEL_EXIT, "터널 끝, 동해", "이 터널 지나면 바다야.", len = 1.2, oneWay = true))),
        Edge("jdj", "simgok", "헌화로", 6.0, 40.0, 0, 4, 0, setOf(Tag.SEA),
            listOf(Feature(2.5, SceneKind.SEA, "헌화로 해안도로", "바다 바로 옆이야. 창문 열어 봐."))),
        Edge("simgok", HOME, "강동 내륙길", 22.0, 70.0, 5, 3, 0, setOf(Tag.MOUNTAIN)),
        Edge("jdj", HOME, "동해대로", 19.0, 70.0, 6, 1, 0, setOf(Tag.CITY)),
        Edge(HOME, "seongsan", "대관령로", 10.0, 60.0, 6, 1, 150, setOf(Tag.CITY, Tag.MOUNTAIN)),
        Edge("seongsan", "pass", "대관령 옛길", 8.0, 35.0, 0, 9, 600, setOf(Tag.MOUNTAIN),
            listOf(Feature(7.6, SceneKind.PASS, "대관령 고갯마루", "곧 고갯마루야. 앞이 확 트여.", oneWay = true))),
        Edge("pass", HOME, "영동고속도로", 22.0, 80.0, 1, 2, -750, setOf(Tag.MOUNTAIN),
            listOf(Feature(9.0, SceneKind.TUNNEL_EXIT, "터널 끝, 강릉 시내", "터널 나가면 동해까지 다 보여.", len = 1.6, oneWay = true))),
        Edge("seongsan", "ojuk", "성산로", 8.0, 50.0, 4, 1, -140, setOf(Tag.CITY)),
        Edge("sacheon", "seongsan", "사천 계곡길", 14.0, 55.0, 2, 5, 120, setOf(Tag.MOUNTAIN),
            listOf(Feature(8.0, SceneKind.VIEW, "사천 계곡", "계곡 따라 들어간다. 공기 달라졌지?"))),
    )

    fun node(id: String) = nodes.getValue(id)
}
