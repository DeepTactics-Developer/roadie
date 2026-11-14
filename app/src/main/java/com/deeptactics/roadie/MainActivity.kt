package com.deeptactics.roadie

import android.app.Activity
import android.app.NotificationManager
import android.car.Car
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// 화면 담당. 주행은 DriveService가 하고, 여기는 Session에 쓰인 상태를 보여 주기만 한다.
// 내비가 앞으로 나와서 이 화면이 가려져도 주행은 계속된다
class MainActivity : Activity() {
    private val speaker get() = RoadieApp.speaker
    private val listener get() = RoadieApp.listener
    private val records get() = RoadieApp.records

    // 홈에서 고른 것
    private var wish = Wish()
    private var heard: String? = null
    private var skip = 0
    private var course: Course? = null

    private var screen = ""
    private var dropSeen = 0
    private var forkShown = false
    private val handler = Handler(Looper.getMainLooper())
    private val ui = object : Runnable {
        override fun run() {
            sync()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
        }
        RoadieApp.requestHere()
        RoadieApp.loadGraph(this)
        wish = defaultWish()
        recompose()
        dropSeen = Session.dropSeq
    }

    override fun onResume() {
        super.onResume()
        RoadieApp.onHere = { onHere() }
        RoadieApp.requestHere()
        handler.post(ui)
    }

    override fun onPause() {
        RoadieApp.onHere = null
        handler.removeCallbacks(ui)
        super.onPause()
    }

    // 지금 위치에 맞는 지도로 코스를 짠다
    private fun recompose(skipTo: Int = 0) {
        RoadieApp.loadGraph(this)
        course = Composer.compose(RoadieApp.graph, RoadieApp.here.node, wish, records.taste(), skipTo)
    }

    // 내비가 위치를 새로 알려 주면 홈에서는 코스를 다시 짠다
    private fun onHere() {
        if (screen == "home" && Session.phase == Session.Phase.HOME) {
            skip = 0
            RoadieApp.loadGraph(this)
            if (heard == null) wish = defaultWish()
            recompose()
            showCourse()
        }
    }


    // 지금 보여 줄 화면을 서비스 상태에 맞춘다
    private fun sync() {
        when (Session.phase) {
            Session.Phase.DRIVING -> {
                if (screen != "drive") showDrive()
                renderDrive()
            }
            Session.Phase.ARRIVED -> if (screen != "arrive" && Session.result != null) showArrive()
            Session.Phase.HOME -> when (screen) {
                "" -> showHome(greet = true)
                "drive" -> showHome(greet = false)
            }
        }
    }

    private fun face() = findViewById<CarFace>(R.id.face)

    private fun setScreen(name: String, layout: Int) {
        screen = name
        Session.homeText = null
        Session.homeListening = null
        Session.homeFail = null
        setContentView(layout)
        face()?.talkingOf = { speaker.talking }
    }

    private fun say(text: String) {
        findViewById<TextView>(R.id.bubble)?.text = text
        speaker.say(text)
    }

    // 홈

    private fun showHome(greet: Boolean) {
        setScreen("home", R.layout.home)
        face()?.mood = CarFace.Mood.HAPPY
        findViewById<TextView>(R.id.record).text = recordLine()
        if (greet) {
            val drives = records.drives()
            say(Lines.greeting(records.km, records.count, drives.firstOrNull(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY), Taste.favorite(records.taste())))
        } else say(Lines.PROMPT)

        Session.homeText = { text, final ->
            findViewById<TextView>(R.id.heard)?.text = "“$text”"
            if (final) onWish(text)
        }
        Session.homeListening = { on ->
            findViewById<MicButton>(R.id.mic)?.listening = on
            face()?.mood = if (on) CarFace.Mood.LISTENING else CarFace.Mood.HAPPY
        }
        Session.homeFail = { say(Lines.NOT_HEARD) }

        val chips = findViewById<LinearLayout>(R.id.chips)
        for (ex in examples()) {
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
            recompose(skip)
            course?.let { say(Lines.composed(wish, it)) }
            showCourse()
        }
        findViewById<Switch>(R.id.simulate).apply {
            isChecked = Session.simulate
            setOnCheckedChangeListener { _, on -> Session.simulate = on }
        }
        findViewById<TextView>(R.id.warp).apply {
            text = "${Session.warp.toInt()}배속"
            setOnClickListener {
                Session.warp = when (Session.warp) { 1.0 -> 10.0; 10.0 -> 20.0; 20.0 -> 40.0; else -> 1.0 }
                text = "${Session.warp.toInt()}배속"
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
        findViewById<TextView>(R.id.where).text = "출발 · 지금 위치 " + RoadieApp.here.label
        val c = course
        findViewById<RouteView>(R.id.route).course = c
        findViewById<TextView>(R.id.wish).text = wish.summary()
        findViewById<View>(R.id.start).apply {
            isEnabled = c != null
            alpha = if (c != null) 1f else 0.35f
        }
        if (c == null) {
            findViewById<TextView>(R.id.name).text = "이 근처엔 아직 코스가 없어"
            findViewById<TextView>(R.id.stats).text = "지도를 준비한 지역(수도권, 강릉) 안에서 써 줘"
            findViewById<TextView>(R.id.scenes).text = ""
            return
        }
        findViewById<TextView>(R.id.name).text = c.name
        val shares = listOf(Tag.SEA, Tag.RIVER, Tag.LAKE, Tag.MOUNTAIN).filter { c.share(it) >= 0.1 }.joinToString(" · ") { "${it.word} ${(c.share(it) * 100).toInt()}%" }
        findViewById<TextView>(R.id.stats).text = "약 %d분 · %.1f km · 신호 %d개".format(c.minutes.toInt(), c.km, c.signals) +
            if (shares.isEmpty()) "" else "\n$shares"
        val f = c.fork
        val conn = c.legs.filter { it.edge.connector }.sumOf { it.edge.km }
        findViewById<TextView>(R.id.scenes).text = (c.scenes.map { "●  ${it.name}" } +
            listOfNotNull(f?.let { "◆  ${it.node.name}에서 갈림길 (${it.left.label} / ${it.right.label})" }) +
            listOf("↳  연결 구간 %.1f km (지도 분석 없음)".format(conn))).joinToString("\n")
    }

    // 지금 위치 근처 도로에 많은 성격 순서(두 조각 이상 있는 것만)
    private fun availableTags(): List<Tag> {
        val here = RoadieApp.here.node
        val near = RoadieApp.graph.edges.filter { it.a.km(here) < 30 || it.b.km(here) < 30 }
        return listOf(Tag.SEA, Tag.RIVER, Tag.MOUNTAIN, Tag.LAKE).sortedByDescending { t -> near.count { t in it.tags } }.filter { t -> near.count { t in it.tags } >= 2 }
    }

    // 처음 보여 줄 코스의 바람. 말하기 전에도 근처에 실제로 있는 성격으로 짠다(취향이 맞으면 취향대로)
    private fun defaultWish(): Wish {
        val avail = availableTags()
        val fav = Taste.favorite(records.taste())
        val tag = if (fav != null && fav in avail) fav else avail.firstOrNull()
        return Wish(Taste.suggest(records.taste(), records.drives()).minutes, setOfNotNull(tag), fewSignals = true)
    }

    // 지금 위치 근처에 많은 성격을 골라 예시 문장으로 보여 준다
    private fun examples(): List<String> {
        val texts = mapOf(
            Tag.SEA to "40분, 바다 느낌, 신호 적은 길",
            Tag.RIVER to "40분, 한강 야경, 신호 적은 길",
            Tag.MOUNTAIN to "한 시간, 고갯길 와인딩",
            Tag.LAKE to "30분 호숫가 한 바퀴",
        )
        return (availableTags().ifEmpty { listOf(Tag.MOUNTAIN) }).take(3).map { texts.getValue(it) }
    }

    private fun onWish(text: String) {
        heard = text
        wish = Wish.parse(text)
        skip = 0
        recompose()
        say(course?.let { Lines.composed(wish, it) } ?: "이 근처엔 아직 코스가 없어.")
        showCourse()
    }

    // 주행

    private fun startDrive() {
        if (!Session.simulate && checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Car.PERMISSION_SPEED), REQ_SPEED)
            return
        }
        listener.stop()
        Session.plan = course ?: return
        Session.result = null
        Session.phase = Session.Phase.DRIVING
        runCatching { DriveService.start(this) }.onFailure {
            Log.w(TAG, "주행 서비스 시작 실패", it)
            Session.phase = Session.Phase.HOME
            say("주행을 시작하지 못했어.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_SPEED && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDrive()
    }

    private fun showDrive() {
        val c = Session.course ?: Session.plan ?: course ?: return
        setScreen("drive", R.layout.drive)
        forkShown = false
        dropSeen = Session.dropSeq
        face()?.driving = true
        findViewById<RouteView>(R.id.route).apply {
            course = c
            km = 0.0
        }
        findViewById<TextView>(R.id.course).text = c.name
        findViewById<View>(R.id.stop).setOnClickListener { DriveService.instance?.finish() ?: run { Session.phase = Session.Phase.HOME } }
        findViewById<View>(R.id.left).setOnClickListener { Session.course?.fork?.let { DriveService.instance?.resolveFork(it.left) } }
        findViewById<View>(R.id.right).setOnClickListener { Session.course?.fork?.let { DriveService.instance?.resolveFork(it.right) } }
    }

    private fun renderDrive() {
        val s = Session
        val c = s.course ?: return
        findViewById<TextView>(R.id.nextName).text = s.targetName
        findViewById<TextView>(R.id.remain).text = "%.1f km".format(s.remainKm)
        findViewById<TextView>(R.id.speed).text = "%.0f km/h".format(s.speed)
        findViewById<TextView>(R.id.bubble).text = s.bubble
        findViewById<RouteView>(R.id.route).apply {
            if (course !== c) {
                course = c
                this@MainActivity.findViewById<TextView>(R.id.course).text = c.name
            }
            km = s.km
            invalidate()
        }
        findViewById<MusicView>(R.id.music).apply {
            levels = s.levels
            beat = s.beat
            invalidate()
        }

        face()?.mood = when {
            s.forkOpen -> if (s.listening) CarFace.Mood.LISTENING else CarFace.Mood.CURIOUS
            s.part == Part.DROP -> CarFace.Mood.EXCITED
            s.part == Part.PRE || s.part.building -> CarFace.Mood.CURIOUS
            s.speed < 5 -> CarFace.Mood.SLEEPY
            else -> CarFace.Mood.HAPPY
        }

        // 갈림길 선택 화면
        if (s.forkOpen) {
            val f = c.fork
            if (!forkShown && f != null) {
                forkShown = true
                findViewById<TextView>(R.id.left).text = "←  ${f.left.label}"
                findViewById<TextView>(R.id.right).text = "${f.right.label}  →"
                findViewById<TextView>(R.id.forkTitle).text = "곧 ${f.node.name} 갈림길"
                findViewById<View>(R.id.forkPanel).visibility = View.VISIBLE
            }
            findViewById<TextView>(R.id.forkHint).text = when {
                s.listening -> "듣고 있어. 말해 줘"
                s.heardText != null -> "“${s.heardText}”"
                else -> "말하거나 눌러서 골라 줘"
            }
        } else if (forkShown) {
            forkShown = false
            findViewById<View>(R.id.forkPanel).visibility = View.GONE
        }

        // 장면 이름
        if (s.dropSeq != dropSeen) {
            dropSeen = s.dropSeq
            findViewById<TextView>(R.id.popup).apply {
                text = s.dropName
                animate().alpha(1f).setDuration(300).withEndAction {
                    animate().alpha(0f).setStartDelay(2000).setDuration(500)
                }
            }
        }
    }

    // 도착

    private fun showArrive() {
        val r = Session.result ?: return
        setScreen("arrive", R.layout.arrive)
        getSystemService(NotificationManager::class.java).cancel(DriveService.ARRIVE_ID)
        face()?.mood = CarFace.Mood.HAPPY
        val clock = SimpleDateFormat("HH:mm", Locale.KOREA)
        findViewById<TextView>(R.id.bubble).text = r.line
        findViewById<TextView>(R.id.title).text = r.course.name
        findViewById<TextView>(R.id.stats).text = "%.1f km · %d분 주행".format(r.km, r.minutes) + if (r.simulated) " (시뮬레이션 ${r.simMinutes}분)" else ""
        findViewById<TextView>(R.id.note).text = "“${r.note}”"
        findViewById<TextView>(R.id.scenes).text = r.passed.joinToString("\n") { (s, at) -> "${clock.format(Date(at))}   ${s.name}" }.ifEmpty { "-" }
        findViewById<RouteView>(R.id.route).apply {
            labels = false
            showFork = false
            course = r.course
            km = r.km
        }
        findViewById<TextView>(R.id.record).text = recordLine()
        findViewById<View>(R.id.home).setOnClickListener {
            heard = null
            Session.result = null
            Session.phase = Session.Phase.HOME
            showHome(greet = false)
        }
    }

    private fun showDiary() {
        setScreen("diary", R.layout.diary)
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

    companion object {
        const val TAG = "ROADIE"
        const val TICK_MS = 50L
        const val REQ_SPEED = 1
        const val REQ_NOTI = 2
    }
}
