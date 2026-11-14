package com.deeptactics.roadie

enum class SceneKind { TUNNEL_EXIT, SEA, PASS, BRIDGE, NIGHT }

// km: 코스 시작 기준 거리, line: 장면 직전에 반려차가 하는 말
// tunnelFrom: 터널 출구 장면이면 터널이 시작되는 지점. 터널 안에서 빌드업을 쌓는다
class Scene(val km: Double, val name: String, val kind: SceneKind, val line: String, val tunnelFrom: Double? = null)

// untilKm 지점까지 kmh로 달리는 구간. jam은 지도에 없는 갑작스러운 정체
class Segment(val untilKm: Double, val kmh: Double, val jam: Boolean = false)

class Course(
    val id: String,
    val name: String,
    val tags: List<String>,
    val segments: List<Segment>,
    val scenes: List<Scene>,
    val destName: String,
    val destLat: Double,
    val destLon: Double,
) {
    val km get() = segments.last().untilKm
    val minutes get() = segments.fold(0.0 to 0.0) { (from, min), s -> s.untilKm to min + (s.untilKm - from) / s.kmh * 60 }.second

    // plan이면 정체를 모르는 예상 속도(정체 구간은 바로 앞 구간 속도로)
    fun speedAt(km: Double, plan: Boolean = false): Double {
        val i = segments.indexOfFirst { km < it.untilKm }.let { if (it < 0) segments.lastIndex else it }
        if (!plan) return segments[i].kmh
        return segments.subList(0, i + 1).lastOrNull { !it.jam }?.kmh ?: segments[i].kmh
    }
}

// 거리와 좌표는 연출용 데이터. 실제 도로와 조금씩 다르다
val COURSES = listOf(
    Course(
        "sea", "바다 코스", listOf("바다"),
        listOf(
            Segment(8.0, 80.0), Segment(9.0, 70.0), // 터널
            Segment(14.22, 60.0), Segment(14.27, 6.0, jam = true),
            Segment(14.5, 60.0), Segment(17.0, 50.0),
        ),
        listOf(
            Scene(9.0, "터널 끝, 바다", SceneKind.TUNNEL_EXIT, "이 터널 지나면 바다야.", tunnelFrom = 8.0),
            Scene(14.5, "헌화로 해안도로", SceneKind.SEA, "바다 바로 옆으로 붙는다."),
        ),
        "정동진역", 37.6912, 129.0341,
    ),
    Course(
        "pass", "고갯길 코스", listOf("산"),
        listOf(Segment(6.0, 60.0), Segment(9.5, 45.0), Segment(16.0, 50.0), Segment(17.0, 40.0), Segment(21.0, 50.0)),
        listOf(
            Scene(9.5, "대관령 고갯마루", SceneKind.PASS, "곧 고갯마루야. 앞이 확 트여."),
            Scene(17.0, "능선 위 풍력단지", SceneKind.PASS, "저 능선 바람개비 보여?"),
        ),
        "대관령 양떼목장", 37.6876, 128.7525,
    ),
    Course(
        "night", "야경 코스", listOf("야경", "도심"),
        listOf(Segment(4.0, 50.0), Segment(9.0, 60.0), Segment(13.0, 50.0), Segment(15.0, 40.0)),
        listOf(
            Scene(4.0, "반포대교 위 한강", SceneKind.BRIDGE, "다리 건널 때 강 한번 봐."),
            Scene(9.0, "남산 아래 불빛", SceneKind.NIGHT, "불빛 많은 데로 들어간다."),
            Scene(13.0, "한강대교 야경", SceneKind.BRIDGE, "마지막 다리야. 천천히 보자."),
        ),
        "반포한강공원", 37.5103, 126.9959,
    ),
)
