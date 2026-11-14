package com.deeptactics.roadie

// 주행 중 묻는 것(갈림길, 막힐 때). 화면과 알림이 같은 내용을 띄운다. arrows면 화면 버튼에 방향 화살표를 붙인다
class Ask(val title: String, val left: String, val right: String, val hint: String, val arrows: Boolean = false)

// 주행이 끝나고 도착 카드에 보여 줄 것
class Result(
    val course: Course,
    val km: Double,
    val passed: List<Pair<Scene, Long>>,
    val line: String,
    val note: String,
    val minutes: Int,
    val simMinutes: Int,
    val simulated: Boolean,
)

// 주행 서비스가 쓰고 화면이 읽는 상태. 같은 프로세스, 메인 스레드라 잠그지 않는다
object Session {
    enum class Phase { HOME, DRIVING, ARRIVED }

    var phase = Phase.HOME

    // 출발 전에 화면이 정해 두는 것
    var plan: Course? = null
    var simulate = true
    var warp = 40.0

    // 주행 중 상태
    var course: Course? = null
    var km = 0.0
    var speed = 0f
    var part = Part.IDLE
    var targetName = ""
    var remainKm = 0.0
    var levels = FloatArray(5)
    var beat = 0f
    var bubble = ""
    var ask: Ask? = null
    var listening = false
    var heardText: String? = null
    var dropName: String? = null
    var dropSeq = 0
    var result: Result? = null

    // 화면이 켜져 있을 때만 받는 콜백
    var homeText: ((String, Boolean) -> Unit)? = null
    var homeListening: ((Boolean) -> Unit)? = null
    var homeFail: (() -> Unit)? = null

    fun onText(text: String, final: Boolean) {
        if (ask != null) DriveService.instance?.onHeard(text, final) else homeText?.invoke(text, final)
    }

    fun onListening(on: Boolean) {
        listening = on
        homeListening?.invoke(on)
    }

    fun onListenFail() {
        if (ask == null) homeFail?.invoke()
    }
}
