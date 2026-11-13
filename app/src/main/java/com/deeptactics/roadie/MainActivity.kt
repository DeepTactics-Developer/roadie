package com.deeptactics.roadie

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.car.Car
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView

// 3단계 검증용 화면. 속도와 내비 응답을 그대로 찍는다
class MainActivity : Activity() {
    private lateinit var speedText: TextView
    private lateinit var naviText: TextView
    private var carDrive: CarDrive? = null
    private lateinit var navi: NaviHelper

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = naviLog("경로 탐색 시작 $routeStartInfo")
        override fun onRoutingCompleted() = naviLog("경로 탐색 완료")
        override fun onDestinationChanged() = naviLog("목적지 변경됨")
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = naviLog("경로 상태 $routeStateInfo")
        override fun onNavigationStatus(navigationStatus: NavigationStatus) = naviLog("내비 상태 $navigationStatus")
        override fun onError(errorCode: NaviErrorCode) = naviLog("오류 $errorCode")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speedText = TextView(this).apply { textSize = 48f; text = "속도 -" }
        naviText = TextView(this).apply { textSize = 20f; text = "내비 연결 중" }
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(speedText)
            addView(naviText)
        })

        if (checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) startSpeed()
        else requestPermissions(arrayOf(Car.PERMISSION_SPEED), 1)

        navi = NaviHelper(applicationContext)
        navi.initialize(onConnected = {
            naviLog("연결됨")
            navi.addListener(naviListener)
            // 강릉 경포해변 쪽으로 시험 요청
            navi.requestRoute(RouteInfo(latitude = 37.8055, longitude = 128.9083, displayLongitude = null, displayLatitude = null,
                poiName = "경포해변", poiId = "", poiSubId = "0", address = "강원 강릉시 창해로"))
            navi.getRouteStateInfo()
        }, onFailed = { naviLog("연결 실패 $it") })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSpeed()
        else speedText.text = "속도 권한 거부됨"
    }

    private fun startSpeed() {
        carDrive = CarDrive(this) { kmh ->
            Log.d(TAG, "speed $kmh")
            speedText.text = "속도 %.1f km/h".format(kmh)
        }.also { it.start() }
    }

    private fun naviLog(msg: String) {
        Log.d(TAG, "navi $msg")
        runOnUiThread { naviText.append("\n$msg") }
    }

    override fun onDestroy() {
        carDrive?.stop()
        navi.removeListener(naviListener)
        navi.release()
        super.onDestroy()
    }

    companion object { const val TAG = "ROADIE" }
}
