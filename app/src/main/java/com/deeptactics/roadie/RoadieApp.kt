package com.deeptactics.roadie

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log

// 화면(Activity)이 내려가도 서비스가 계속 쓰는 것들을 프로세스에 하나씩 둔다
class RoadieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        records = Records(this)
        Docent.load(this)
        speaker = Speaker(this)
        listener = Listener(this).apply {
            onText = { text, final -> Session.onText(text, final) }
            onState = { on -> Session.onListening(on) }
            onFail = { Session.onListenFail() }
        }
        navi = NaviHelper(applicationContext)
        navi.initialize(onConnected = {
            naviReady = true
            navi.addListener(object : NaviHelperEventListener {
                override fun onRouteStarted(routeStartInfo: RouteStartInfo) = Log.d(MainActivity.TAG, "navi 경로 시작 $routeStartInfo").let {}
                override fun onError(errorCode: NaviErrorCode) = Log.w(MainActivity.TAG, "navi 오류 $errorCode").let {}
                override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
                    Log.d(MainActivity.TAG, "지금 위치 ${info.latitude},${info.longitude} ${info.address}")
                    main.post { setHere(info.latitude, info.longitude, info.address) }
                }
            })
            requestHere()
        }, onFailed = { Log.w(MainActivity.TAG, "navi 연결 실패", it) })
    }

    companion object {
        lateinit var records: Records
        lateinit var speaker: Speaker
        lateinit var listener: Listener
        lateinit var navi: NaviHelper
        var naviReady = false
        private val main = Handler(Looper.getMainLooper())

        // 코스의 출발지이자 도착지. 내비가 알려 주는 지금 위치. 못 받으면 기본 위치
        class Here(val node: Node, val real: Boolean, val label: String)

        private val DEFAULT = Here(Node("here", "지금 위치", 37.5663, 126.9779), false, "서울시청 (기본 위치)")
        var here: Here = DEFAULT
            private set
        var onHere: (() -> Unit)? = null

        // 지금 위치 근처 지역의 도로 지도. 위치가 바뀌어 다른 지역이 되면 다시 읽는다
        var graph: Graph = Graph.EMPTY
            private set
        private var graphKey = ""

        fun requestHere() {
            if (naviReady) runCatching { navi.getCurrentLocationInfo() }.onFailure { Log.w(MainActivity.TAG, "위치 요청 실패", it) }
        }

        private fun setHere(lat: Double, lon: Double, address: String?) {
            val label = address?.takeIf { it.isNotBlank() } ?: "%.4f, %.4f".format(lat, lon)
            here = Here(Node("here", "지금 위치", lat, lon), true, label)
            onHere?.invoke()
        }

        // 위치에 맞는 지도를 불러 둔다(이미 같은 지역이면 그대로)
        fun loadGraph(context: android.content.Context) {
            val key = "%.1f,%.1f".format(here.node.lat, here.node.lon)
            if (key == graphKey) return
            graphKey = key
            graph = runCatching { Roads.load(context, here.node.lat, here.node.lon) }.onFailure { Log.w(MainActivity.TAG, "지도 읽기 실패", it) }.getOrDefault(Graph.EMPTY)
            Log.d(MainActivity.TAG, "지도 지점 ${graph.nodes.size}곳, 연결 ${graph.edges.size}개")
        }

        // 경로 요청이나 취소를 하면 내비가 앞으로 나오고 안내 음성도 낸다. 우리 대사는 그게 끝난 뒤에 한다
        fun naviCall(holdMs: Long = 1500, block: NaviHelper.() -> Unit) {
            if (!naviReady) return
            speaker.hold(holdMs)
            runCatching { navi.block() }.onFailure { Log.w(MainActivity.TAG, "navi 호출 실패", it) }
        }
    }
}
