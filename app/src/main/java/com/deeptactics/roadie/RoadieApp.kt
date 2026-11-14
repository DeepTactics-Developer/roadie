package com.deeptactics.roadie

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Application
import android.util.Log

// 화면(Activity)이 내려가도 서비스가 계속 쓰는 것들을 프로세스에 하나씩 둔다
class RoadieApp : Application() {
    override fun onCreate() {
        super.onCreate()
        records = Records(this)
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
            })
        }, onFailed = { Log.w(MainActivity.TAG, "navi 연결 실패", it) })
    }

    companion object {
        lateinit var records: Records
        lateinit var speaker: Speaker
        lateinit var listener: Listener
        lateinit var navi: NaviHelper
        var naviReady = false

        // 경로 요청이나 취소를 하면 내비가 앞으로 나오고 안내 음성도 낸다. 우리 대사는 그게 끝난 뒤에 한다
        fun naviCall(holdMs: Long = 1500, block: NaviHelper.() -> Unit) {
            if (!naviReady) return
            speaker.hold(holdMs)
            runCatching { navi.block() }.onFailure { Log.w(MainActivity.TAG, "navi 호출 실패", it) }
        }
    }
}
