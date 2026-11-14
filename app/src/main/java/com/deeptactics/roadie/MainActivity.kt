package com.deeptactics.roadie

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.car.Car
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var navi: NaviHelper
    private var naviReady = false
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private lateinit var records: Records

    // 홈에서 고른 것
    private var wish = Wish()
    private var heard: String? = null
    private var skip = 0
    private lateinit var course: Course
    private var simulate = true
    private var warp = 40.0

    // 주행 중에만 있는 것들
    private var drive: Course? = null
    private var source: DriveSource? = null
    private var mixer: Mixer? = null
    private var arranger: Arranger? = null
    private val passed = mutableListOf<Pair<Scene, Long>>()
    private var stats = DriveStats()
    private var startedAt = 0L
    private var lastTick = 0L
    private var reclaim = false
    private var forkAsked = false
    private var forkOpen = false
    private var forkListenAfterTalk = false
    private var forkDeadline = 0L
    private var jamSaid = false
    private var slowSince = 0L
    private var lastPart = Part.IDLE
    private var lastDrop: Scene? = null

    private var screen = ""
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
        speaker = Speaker(this) { on ->
            mixer?.duck(on)
            face()?.talking = on
            // 갈림길 질문을 다 말하고 나서 듣기 시작한다
            if (!on && forkListenAfterTalk) {
                forkListenAfterTalk = false
                if (forkOpen) listener.listen()
            }
        }
        listener = Listener(this)
        listener.onState = { on ->
            findViewById<MicButton>(R.id.mic)?.listening = on
            if (screen == "home") face()?.mood = if (on) CarFace.Mood.LISTENING else CarFace.Mood.HAPPY
            if (forkOpen) findViewById<TextView>(R.id.forkHint)?.text = if (on) "듣고 있어. 말해 줘" else "눌러서 골라도 돼"
        }
        listener.onText = { text, final -> onHeard(text, final) }
        listener.onFail = { if (screen == "home") say(Lines.NOT_HEARD) }

        navi = NaviHelper(applicationContext)
        navi.initialize(onConnected = {
            naviReady = true
            navi.addListener(naviListener)
        }, onFailed = { Log.w(TAG, "navi 연결 실패", it) })

        val taste = records.taste()
        wish = Taste.suggest(taste, records.drives())
        course = Composer.compose(wish, taste)
        showHome(greet = true)
    }

    private fun face() = findViewById<CarFace>(R.id.face)

    private fun say(text: String) {
        findViewById<TextView>(R.id.bubble)?.text = text
        speaker.say(text)
    }

    // 홈

    private fun showHome(greet: Boolean) {
        screen = "home"
        setContentView(R.layout.home)
        face()?.mood = CarFace.Mood.HAPPY
        findViewById<TextView>(R.id.record).text = recordLine()
        if (greet) {
            val drives = records.drives()
            say(Lines.greeting(records.km, records.count, drives.firstOrNull(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY), Taste.favorite(records.taste())))
        } else say(Lines.PROMPT)

        val chips = findViewById<LinearLayout>(R.id.chips)
        for (ex in EXAMPLES) {
            chips.addView(TextView(this, null, 0, R.style.Chip).apply {
                text = ex
                setOnClickListener { onWish(ex) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 16 })
        }
        findViewById<MicButton>(R.id.mic).setOnClickListener {
            if (listener.listening) listener.stop()
            else if (listener.listen()) findViewById<TextView>(R.id.heard).text = "듣고 있어..."
            else say("음성 인식을 켜지 못했어. 아래 예시를 눌러 줘.")
        }
        findViewById<View>(R.id.other).setOnClickListener {
            skip++
            course = Composer.compose(wish, records.taste(), skip)
            showCourse()
        }
        findViewById<Switch>(R.id.simulate).apply {
            isChecked = simulate
            setOnCheckedChangeListener { _, on -> simulate = on }
        }
        findViewById<TextView>(R.id.warp).apply {
            text = "${warp.toInt()}배속"
            setOnClickListener {
                warp = when (warp) { 1.0 -> 10.0; 10.0 -> 20.0; 20.0 -> 40.0; else -> 1.0 }
                text = "${warp.toInt()}배속"
            }
        }
        findViewById<View>(R.id.start).setOnClickListener { startDrive() }
        findViewById<View>(R.id.diary).setOnClickListener { showDiary() }
        showCourse()
    }

    private fun recordLine() = if (records.count == 0) "아직 같이 달린 기록이 없어"
    else "함께 달린 거리 %.1f km · 드라이브 %d번".format(records.km, records.count)

    private fun showCourse() {
        heard?.let { findViewById<TextView>(R.id.heard).text = "“$it”" }
        findViewById<RouteView>(R.id.route).course = course
        findViewById<TextView>(R.id.wish).text = wish.summary()
        findViewById<TextView>(R.id.name).text = course.name
        findViewById<TextView>(R.id.stats).text = "약 %d분 · %.1f km · 신호 %d개".format(course.minutes.toInt(), course.km, course.signals)
        val f = course.fork
        findViewById<TextView>(R.id.scenes).text = (course.scenes.map { "●  ${it.name}" } +
            listOfNotNull(f?.let { "◆  ${it.node.name}에서 갈림길 (${it.left.label} / ${it.right.label})" })).joinToString("\n")
    }

    private fun onHeard(text: String, final: Boolean) {
        when {
            forkOpen -> {
                findViewById<TextView>(R.id.forkHint)?.text = "“$text”"
                val f = drive?.fork ?: return
                if (final) Lines.pick(text, f)?.let { resolveFork(it) }
            }
            screen == "home" -> {
                findViewById<TextView>(R.id.heard)?.text = "“$text”"
                if (final) onWish(text)
            }
        }
    }

    private fun onWish(text: String) {
        heard = text
        wish = Wish.parse(text)
        skip = 0
        course = Composer.compose(wish, records.taste())
        say(Lines.composed(wish, course))
        showCourse()
    }

    // 주행

    private fun startDrive() {
        if (!simulate && checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Car.PERMISSION_SPEED), REQ_SPEED)
            return
        }
        listener.stop()
        val c = if (simulate) course.withJam() else course
        drive = c
        source = if (simulate) SimulatedDrive(c, warp) else CarDrive(this)
        val a = Arranger(::onApproach, ::onDrop).apply { scenes = c.scenes }
        arranger = a
        mixer = Mixer(this, a).also { it.start() }
        passed.clear()
        stats = DriveStats()
        forkAsked = false
        forkOpen = false
        jamSaid = false
        lastDrop = null
        startedAt = System.currentTimeMillis()
        lastTick = SystemClock.elapsedRealtime()

        screen = "drive"
        setContentView(R.layout.drive)
        face()?.driving = true
        findViewById<RouteView>(R.id.route).apply {
            course = c
            km = 0.0
        }
        findViewById<TextView>(R.id.course).text = c.name
        findViewById<View>(R.id.stop).setOnClickListener {
            val km = source?.km ?: 0.0
            endDrive()
            if (km > 1.0) showArrive(km) else showHome(greet = false)
        }
        findViewById<View>(R.id.left).setOnClickListener { c.fork?.let { resolveFork(it.left) } }
        findViewById<View>(R.id.right).setOnClickListener { c.fork?.let { resolveFork(it.right) } }
        say(Lines.START)
        routeTo(c)
        handler.post(tick)
    }

    // 코스를 내비 경유지 경로로 넘긴다. 출발지로 돌아오는 순환이라 목적지는 출발지
    private fun routeTo(c: Course) = naviCall(NAVI_VOICE_MS) {
        val home = c.start
        val wps = c.waypoints.mapIndexed { i, n ->
            RequestWaypointInfo(n.lat, n.lon, n.name, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND, "", "0", n.name, "")
        }
        val dest = RouteInfo(
            latitude = home.lat, longitude = home.lon, displayLongitude = null, displayLatitude = null,
            poiName = home.name, poiId = "", poiSubId = "0", address = home.name,
        )
        requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, wps))
    }

    // 경로 요청이나 취소를 하면 내비 앱이 앞으로 나온다. 안내는 뒤에서 하고 화면은 다시 가져온다
    private fun naviCall(holdMs: Long = 1500, block: NaviHelper.() -> Unit) {
        if (!naviReady) return
        reclaim = true
        handler.postDelayed({ reclaim = false }, 5000)
        speaker.hold(holdMs)
        runCatching { navi.block() }.onFailure { Log.w(TAG, "navi 호출 실패", it) }
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
        val a = arranger ?: return
        val m = mixer ?: return
        val c = drive ?: return
        val t = SystemClock.elapsedRealtime()
        val dt = (t - lastTick) / 1000.0
        src.update(dt)
        lastTick = t
        stats.add(dt * ((src as? SimulatedDrive)?.timeScale() ?: 1.0), src.speedKmh)

        val now = m.now()
        a.update(now, src.km, src.speedKmh, src::etaSec)

        val target = a.target
        findViewById<TextView>(R.id.nextName).text = target?.name ?: "${c.start.name}으로"
        findViewById<TextView>(R.id.remain).text = "%.1f km".format(((target?.km ?: c.km) - src.km).coerceAtLeast(0.0))
        findViewById<TextView>(R.id.speed).text = "%.0f km/h".format(src.speedKmh)
        findViewById<RouteView>(R.id.route).apply {
            km = src.km
            invalidate()
        }
        findViewById<MusicView>(R.id.music).apply {
            levels = m.levels()
            beat = (now % (Arranger.BAR / 4)).toFloat() / (Arranger.BAR / 4)
            invalidate()
        }

        face()?.mood = when {
            forkOpen -> if (listener.listening) CarFace.Mood.LISTENING else CarFace.Mood.CURIOUS
            a.part == Part.DROP -> CarFace.Mood.EXCITED
            a.part == Part.PRE || a.part.building -> CarFace.Mood.CURIOUS
            src.speedKmh < 5 -> CarFace.Mood.SLEEPY
            else -> CarFace.Mood.HAPPY
        }

        // 정체: 몇 초 이상 기어가면 한마디
        if (src.speedKmh < 12 && src.km > 0.3 && c.km - src.km > 0.5) {
            if (slowSince == 0L) slowSince = t
            if (!jamSaid && t - slowSince > 1500) {
                jamSaid = true
                say(Lines.JAM)
            }
        } else if (src.speedKmh > 30) {
            slowSince = 0L
            jamSaid = false
        }

        // 드롭 여운이 끝나면 한마디. 다음 장면 대사와 겹칠 것 같으면 참는다
        if (lastPart == Part.AFTER && a.part != Part.AFTER && a.eta > Arranger.LINE_SEC + 10) lastDrop?.let { say(Lines.afterScene(it.kind)) }
        lastPart = a.part

        // 갈림길
        val f = c.fork
        if (f != null && !forkAsked && src.km >= f.km - c.forkAskKm()) askFork(f)
        if (forkOpen && f != null && (t > forkDeadline || src.km >= f.km - 0.05)) resolveFork(null)

        if (src.km >= c.km && src.speedKmh == 0f) {
            endDrive()
            showArrive(src.km)
        }
    }

    private fun askFork(f: Fork) {
        Log.d(TAG, "갈림길 질문 ${f.node.name}")
        forkAsked = true
        forkOpen = true
        findViewById<View>(R.id.forkPanel).visibility = View.VISIBLE
        findViewById<TextView>(R.id.left).text = "←  ${f.left.label}"
        findViewById<TextView>(R.id.right).text = "${f.right.label}  →"
        findViewById<TextView>(R.id.forkTitle).text = "곧 ${f.node.name} 갈림길"
        findViewById<TextView>(R.id.forkHint).text = "말하거나 눌러서 골라 줘"
        forkListenAfterTalk = true
        say(Lines.forkAsk(f))
        forkDeadline = SystemClock.elapsedRealtime() + FORK_WAIT_MS
    }

    private fun resolveFork(choice: Choice?) {
        if (!forkOpen) return
        forkOpen = false
        forkListenAfterTalk = false
        listener.stop()
        findViewById<View>(R.id.forkPanel).visibility = View.GONE
        val c = drive ?: return
        val f = c.fork ?: return
        val ch = choice ?: f.planned
        Log.d(TAG, "갈림길 선택 ${ch.label} ${if (choice == null) "(기본)" else ""}")
        stats.picks += ch.label
        say(if (choice != null) Lines.forkPicked(ch) else Lines.forkDefault(ch))

        val src = source ?: return
        var next = c.branch(ch)
        // 정체를 아직 안 겪었으면 새 길에도 넣는다
        if (simulate && c.segments.none { it.jam && it.untilKm < src.km }) next = next.withJam(src.km)
        drive = next
        (src as? SimulatedDrive)?.course = next
        arranger?.replace(next.scenes, src.km)
        findViewById<RouteView>(R.id.route).course = next
        findViewById<TextView>(R.id.course).text = next.name
        routeTo(next)
    }

    private fun onApproach(scene: Scene) {
        Log.d(TAG, "접근 ${scene.name}: ${scene.line}")
        say(scene.line)
    }

    private fun onDrop(scene: Scene) {
        Log.d(TAG, "드롭 ${scene.name}")
        passed += scene to System.currentTimeMillis()
        lastDrop = scene
        speaker.stop()
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
        listener.stop()
        forkOpen = false
        source?.stop()
        source = null
        mixer?.release()
        mixer = null
        arranger = null
        if (screen == "drive") naviCall { cancelRoute() }
    }

    // 도착

    private fun showArrive(km: Double) {
        val c = drive ?: course
        Log.d(TAG, "도착 ${c.name} %.1fkm".format(km))
        screen = "arrive"
        setContentView(R.layout.arrive)
        face()?.mood = CarFace.Mood.HAPPY
        val min = ((System.currentTimeMillis() - startedAt) / 60000.0).toInt()
        val drivenMin = (stats.total / 60).toInt()
        val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
        val scenes = passed.map { it.first }
        val before = records.km
        val crossed = ((before + km) / 100).toInt().takeIf { it > (before / 100).toInt() }?.let { it * 100 }
        val line = Lines.arrive(scenes, records.count == 0, crossed, records.visited(c.id))
        val note = Taste.note(c, km, stats)
        records.add(c.id, Drive(Records.stamp(), c.name, km, drivenMin, scenes.map { it.name }, note))
        records.saveTaste(Taste.learn(records.taste(), c, stats))

        findViewById<TextView>(R.id.title).text = c.name
        findViewById<TextView>(R.id.stats).text = "%.1f km · %d분 주행".format(km, drivenMin) + if (simulate) " (시뮬레이션 ${min}분)" else ""
        findViewById<TextView>(R.id.note).text = "“$note”"
        findViewById<TextView>(R.id.scenes).text = passed.joinToString("\n") { (s, at) -> "${clock.format(Date(at))}   ${s.name}" }.ifEmpty { "-" }
        findViewById<RouteView>(R.id.route).apply {
            labels = false
            showFork = false
            course = c
            this.km = km
        }
        findViewById<TextView>(R.id.record).text = recordLine()
        findViewById<View>(R.id.home).setOnClickListener {
            heard = null
            showHome(greet = false)
        }
        say(line)
    }

    private fun showDiary() {
        screen = "diary"
        setContentView(R.layout.diary)
        val drives = records.drives()
        findViewById<TextView>(R.id.summary).text = if (drives.isEmpty()) "아직 일기가 비어 있어. 같이 달리면 여기 남길게."
        else "함께 달린 거리 %.1f km · %d번 · 처음 같이 달린 날 %s".format(records.km, records.count, records.first ?: "-")
        val list = findViewById<LinearLayout>(R.id.list)
        for (d in drives) {
            list.addView(TextView(this).apply {
                text = "${d.date}\n${d.name}  ·  %.1f km  ·  ${d.minutes}분\n${d.scenes.joinToString("  →  ").ifEmpty { "-" }}\n“${d.note}”".format(d.km)
                setTextColor(getColor(R.color.text))
                textSize = 24f
                setLineSpacing(8f, 1f)
                setBackgroundResource(R.drawable.bg_card)
                setPadding(36, 28, 36, 28)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 })
        }
        findViewById<View>(R.id.back).setOnClickListener { showHome(greet = false) }
    }

    override fun onDestroy() {
        endDrive()
        speaker.release()
        listener.release()
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
        const val FORK_WAIT_MS = 16000L
        val EXAMPLES = listOf("40분, 바다 느낌, 신호 적은 길", "한 시간, 고갯길 와인딩", "30분 호숫가 한 바퀴")
    }
}
