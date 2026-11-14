package com.deeptactics.roadie

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.car.Car
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var navi: NaviHelper
    private var naviReady = false
    private lateinit var speaker: Speaker
    private lateinit var records: Records

    private var course = COURSES[0]
    private var simulate = true
    private var warp = 10.0

    // 주행 중에만 있는 것들
    private var source: DriveSource? = null
    private var mixer: Mixer? = null
    private var director: Director? = null
    private val passed = mutableListOf<Pair<Scene, Long>>()
    private var startedAt = 0L
    private var reclaim = false
    private var lastTick = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            step()
            if (source != null) handler.postDelayed(this, TICK_MS)
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = Log.d(TAG, "navi 경로 시작 $routeStartInfo").let {}
        override fun onError(errorCode: NaviErrorCode) = Log.w(TAG, "navi 오류 $errorCode").let {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        records = Records(this)
        speaker = Speaker(this) { mixer?.duck(it) }
        navi = NaviHelper(applicationContext)
        navi.initialize(onConnected = {
            naviReady = true
            navi.addListener(naviListener)
        }, onFailed = { Log.w(TAG, "navi 연결 실패", it) })
        showHome()
    }

    private fun showHome() {
        setContentView(R.layout.home)
        val greeting = Lines.greeting(records.km, records.count)
        findViewById<TextView>(R.id.bubble).text = greeting
        findViewById<TextView>(R.id.record).text = "함께 달린 거리 %.1f km · 드라이브 %d회".format(records.km, records.count)
        speaker.say(greeting)
        val list = findViewById<LinearLayout>(R.id.courses)
        val cards = COURSES.map { c ->
            TextView(this).apply {
                val info = "약 ${c.minutes.toInt()}분 · 장면 ${c.scenes.size}곳\n${c.tags.joinToString(" ") { "#$it" }}"
                text = SpannableStringBuilder("${c.name}\n\n").apply {
                    setSpan(RelativeSizeSpan(1.5f), 0, c.name.length, 0)
                    setSpan(StyleSpan(Typeface.BOLD), 0, c.name.length, 0)
                    val from = length
                    append(info)
                    setSpan(ForegroundColorSpan(getColor(R.color.sub)), from, length, 0)
                }
                textSize = 30f
                setTextColor(getColor(R.color.text))
                setPadding(40, 40, 40, 40)
                list.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = 24 })
            }
        }
        fun paint() = cards.forEachIndexed { i, v ->
            v.background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(getColor(R.color.card))
                if (COURSES[i] == course) setStroke(6, getColor(R.color.accent))
            }
        }
        cards.forEachIndexed { i, v -> v.setOnClickListener { course = COURSES[i]; paint() } }
        paint()

        findViewById<Switch>(R.id.simulate).apply {
            isChecked = simulate
            setOnCheckedChangeListener { _, on -> simulate = on }
        }
        findViewById<Button>(R.id.warp).apply {
            text = "${warp.toInt()}x"
            setOnClickListener {
                warp = when (warp) { 1.0 -> 10.0; 10.0 -> 20.0; else -> 1.0 }
                text = "${warp.toInt()}x"
            }
        }
        findViewById<Button>(R.id.start).setOnClickListener { startDrive() }
    }

    private fun startDrive() {
        if (!simulate && checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Car.PERMISSION_SPEED), REQ_SPEED)
            return
        }
        source = if (simulate) SimulatedDrive(course, warp) else CarDrive(this)
        director = Director(course.scenes, ::onApproach, ::onClimax)
        mixer = Mixer(this).also { it.start() }
        passed.clear()
        startedAt = System.currentTimeMillis()
        lastTick = SystemClock.elapsedRealtime()

        naviCall(NAVI_VOICE_MS) {
            requestRoute(
                RouteInfo(
                    latitude = course.destLat, longitude = course.destLon, displayLongitude = null, displayLatitude = null,
                    poiName = course.destName, poiId = "", poiSubId = "0", address = course.destName,
                )
            )
        }

        setContentView(R.layout.drive)
        findViewById<DriveView>(R.id.driveView).markers = course.scenes.map { (it.km / course.km).toFloat() }.toFloatArray()
        findViewById<Button>(R.id.stop).setOnClickListener {
            endDrive()
            showHome()
        }
        handler.post(tick)
    }

    // 경로 요청이나 취소를 하면 내비 앱이 앞으로 나온다. 안내는 뒤에서 하고 화면은 다시 가져온다
    private fun naviCall(holdMs: Long = 1500, block: NaviHelper.() -> Unit) {
        if (!naviReady) return
        reclaim = true
        handler.postDelayed({ reclaim = false }, 5000)
        speaker.hold(holdMs)
        navi.block()
    }

    override fun onStop() {
        super.onStop()
        if (reclaim) {
            reclaim = false
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_SPEED && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDrive()
    }

    private fun step() {
        val src = source ?: return
        val d = director ?: return
        val m = mixer ?: return
        val t = SystemClock.elapsedRealtime()
        src.update((t - lastTick) / 1000.0)
        lastTick = t

        val now = m.now()
        d.update(now, src.km, src.speedKmh, src::etaSec)
        m.apply(d)

        val next = course.scenes.firstOrNull { it.km > src.km }
        findViewById<TextView>(R.id.nextName).text = next?.name ?: course.destName
        findViewById<TextView>(R.id.remain).text = "%.1f km".format((next?.km ?: course.km) - src.km)
        findViewById<TextView>(R.id.speed).text = "%.0f km/h".format(src.speedKmh)
        findViewById<DriveView>(R.id.driveView).apply {
            progress = (src.km / course.km).toFloat()
            levels = m.levels()
            beat = (now % (Director.BAR / 4)).toFloat() / (Director.BAR / 4)
            invalidate()
        }

        if (src.km >= course.km && src.speedKmh == 0f) {
            endDrive()
            showArrive(src.km)
        }
    }

    private fun onApproach(scene: Scene) {
        Log.d(TAG, "접근 ${scene.name}: ${scene.line}")
        speaker.say(scene.line)
    }

    private fun onClimax(scene: Scene) {
        Log.d(TAG, "클라이맥스 ${scene.name}")
        speaker.stop()
        passed += scene to System.currentTimeMillis()
        findViewById<TextView>(R.id.popup)?.apply {
            text = scene.name
            animate().alpha(1f).setDuration(300).withEndAction {
                animate().alpha(0f).setStartDelay(2000).setDuration(500)
            }
        }
    }

    private fun endDrive() {
        handler.removeCallbacks(tick)
        speaker.stop()
        source?.stop()
        source = null
        mixer?.release()
        mixer = null
        director = null
        naviCall { cancelRoute() }
    }

    private fun showArrive(km: Double) {
        setContentView(R.layout.arrive)
        val min = (System.currentTimeMillis() - startedAt) / 60000.0
        val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
        findViewById<TextView>(R.id.title).text = "${course.name} 도착"
        findViewById<TextView>(R.id.stats).text = "%.1f km · %.0f분".format(km, min)
        findViewById<TextView>(R.id.scenes).text = passed.joinToString("\n") { (s, at) -> "${clock.format(Date(at))}  ${s.name}" }
        val scenes = passed.map { it.first }
        val before = records.km
        val line = Lines.arrive(
            scenes, records.count == 0,
            if (((before + km) / 100).toInt() > (before / 100).toInt()) ((before + km) / 100).toInt() * 100 else null,
            records.visited(course.id),
        )
        records.add(km, course.id, scenes.map { it.name })
        findViewById<TextView>(R.id.line).text = line
        findViewById<TextView>(R.id.record).text = "함께 달린 거리 %.1f km · 드라이브 %d회".format(records.km, records.count)
        speaker.say(line)
        findViewById<Button>(R.id.again).setOnClickListener { startDrive() }
        findViewById<Button>(R.id.home).setOnClickListener { showHome() }
    }

    override fun onDestroy() {
        endDrive()
        speaker.release()
        navi.removeListener(naviListener)
        navi.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "ROADIE"
        const val TICK_MS = 50L
        const val REQ_SPEED = 1
        // 내비 안내 음성은 요청 뒤 늦으면 6초쯤 지나서 나온다
        const val NAVI_VOICE_MS = 7000L
    }
}
