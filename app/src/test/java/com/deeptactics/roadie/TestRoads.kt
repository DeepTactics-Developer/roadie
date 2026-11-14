package com.deeptactics.roadie

import java.io.File

// 테스트에서 쓰는 지도와 출발 위치. 실제 앱이 읽는 지도 파일을 그대로 쓴다
object TestRoads {
    private fun load(vararg ids: String) = Roads.merge(ids.map { Roads.parse(File("src/main/assets/roads/$it.json").readText()) })

    val gangneung by lazy { load("gangneung") }
    val sudogwon by lazy { load("sudogwon") }

    val gangneungStation = Node("here", "지금 위치", 37.7637, 128.8996)
    val seoulCityHall = Node("here", "지금 위치", 37.5663, 126.9779)
    val seongnam = Node("here", "지금 위치", 37.4127, 127.0947) // 에뮬레이터 내비의 기본 위치(수정구 시흥동 쪽)

    class Case(val name: String, val graph: Graph, val here: Node, val wish: String)

    val CASES by lazy {
        listOf(
            Case("강릉", gangneung, gangneungStation, "40분, 바다 느낌, 신호 적은 길"),
            Case("강릉", gangneung, gangneungStation, "한 시간 고갯길 와인딩"),
            Case("서울", sudogwon, seoulCityHall, "40분, 한강 야경, 신호 적은 길"),
            Case("서울", sudogwon, seoulCityHall, "한 시간 산길 와인딩"),
            Case("성남", sudogwon, seongnam, "30분 호숫가 한 바퀴"),
            Case("성남", sudogwon, seongnam, "그냥 아무 데나"),
        )
    }
}
