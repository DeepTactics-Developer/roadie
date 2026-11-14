package com.deeptactics.roadie

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.roundToInt

// 주행의 본체. 화면이 내비에 가려지거나 내려가도 음악, 음성, 갈림길 질문이 이어지게 서비스로 돌린다.
// 화면(MainActivity)은 Session에 쓰인 상태를 보여 주기만 한다
class DriveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val speaker get() = RoadieApp.speaker
    private val listener get() = RoadieApp.listener
    private val records get() = RoadieApp.records
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private var source: DriveSource? = null
    private var mixer: Mixer? = null
    private var arranger: Arranger? = null
    private val passed = mutableListOf<Pair<Scene, Long>>()
    private var stats = DriveStats()
    private var simulate = true
    private var startedAt = 0L
    private var lastTick = 0L
    private var forkAsked = false
    private var listenAfterTalk = false
    private var askDeadline = 0L
    private var lateAsked = false
    private var detoured = false
    private val told = HashSet<String>() // 이미 들려준 이야기
    private var telling = false
    private var tellAt = 0L
    private var saidAt = 0L
    private var detour: Course? = null // 막힐 때 길을 바꾸면 갈 코스. 있으면 지금 질문은 정체 질문
    private var wasTalking = false
    private var jamSaid = false
    private var slowSince = 0L
    private var lastPart = Part.IDLE
    private var lastDrop: Scene? = null
    private var notifiedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            step()
            if (source != null) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (source != null) finish() else stopSelf()
            ACTION_LEFT -> answer(true)
            ACTION_RIGHT -> answer(false)
            else -> if (source == null) begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val plan = Session.plan ?: return stopSelf()
        instance = this
        startForeground(ONGOING_ID, ongoing(plan, "출발 준비"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        simulate = Session.simulate
        val c = if (simulate) plan.withJam().withCongestion() else plan
        Session.course = c
        source = if (simulate) SimulatedDrive(c, Session.warp) else CarDrive(this)
        val a = Arranger(::onApproach, ::onDrop).apply { scenes = c.scenes }
        arranger = a
        mixer = Mixer(this, a).also { it.start() }
        passed.clear()
        stats = DriveStats()
        forkAsked = false
        listenAfterTalk = false
        lateAsked = false
        detoured = false
        told.clear()
        telling = false
        detour = null
        jamSaid = false
        slowSince = 0L
        lastDrop = null
        lastPart = Part.IDLE
        startedAt = System.currentTimeMillis()
        lastTick = SystemClock.elapsedRealtime()

        Session.apply {
            phase = Session.Phase.DRIVING
            result = null
            km = 0.0
            speed = 0f
            ask = null
            heardText = null
        }
        say(Lines.START)
        routeTo(c)
        handler.post(tick)
    }

    // 코스를 내비 경유지 경로로 넘긴다. 출발지로 돌아오는 순환이라 목적지는 출발지
    private fun routeTo(c: Course) = RoadieApp.naviCall(NAVI_VOICE_MS) {
        val home = c.start
        val wps = c.waypoints.mapIndexed { i, n ->
            RequestWaypointInfo(n.lat, n.lon, n.name, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND, "", "0", n.name, "")
        }
        // 목적지가 출발 위치와 같으면 내비가 마지막 구간을 없애 버려서, 100m쯤 옆으로 비켜 둔다
        val dest = RouteInfo(
            latitude = home.lat + HERE_SHIFT, longitude = home.lon + HERE_SHIFT, displayLongitude = null, displayLatitude = null,
            poiName = home.name, poiId = "", poiSubId = "0", address = home.name,
        )
        requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, wps))
    }

    private fun say(text: String) {
        saidAt = SystemClock.elapsedRealtime()
        Session.bubble = text
        speaker.say(text)
    }

    private fun step() {
        val src = source ?: return
        val a = arranger ?: return
        val m = mixer ?: return
        val c = Session.course ?: return
        val t = SystemClock.elapsedRealtime()
        val dt = (t - lastTick) / 1000.0
        src.update(dt)
        lastTick = t
        stats.add(dt * ((src as? SimulatedDrive)?.timeScale() ?: 1.0), src.speedKmh)

        val now = m.now()
        a.update(now, src.km, src.speedKmh, src::etaSec)

        // 말하는 동안 음악을 낮추고, 질문이 끝나면 대답을 듣기 시작한다
        val talking = speaker.talking
        m.duck(talking)
        if (wasTalking && !talking && listenAfterTalk) {
            listenAfterTalk = false
            if (Session.ask != null) listener.listen()
        }
        wasTalking = talking

        val target = a.target
        Session.apply {
            km = src.km
            speed = src.speedKmh
            part = a.part
            targetName = target?.name ?: "${c.start.name}으로"
            remainKm = ((target?.km ?: c.km) - src.km).coerceAtLeast(0.0)
            levels = m.levels()
            beat = (now % (Arranger.BAR / 4)).toFloat() / (Arranger.BAR / 4)
        }

        // 갑자기 멈추듯 막히면 한마디(긴 정체는 아래에서 따로 묻는다)
        if (src.speedKmh < 8 && src.km > 0.3 && c.km - src.km > 0.5) {
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

        val f = c.fork
        if (f != null && !forkAsked && src.km >= f.km - c.forkAskKm()) askFork(f)
        if (Session.ask != null && detour == null && f != null && (t > askDeadline || src.km >= f.km - 0.05)) resolveFork(null)

        // 막혀서 예정보다 많이 늦어지면, 그대로 갈지 길을 바꿔 시간을 맞출지 묻는다.
        // 갈림길 질문이 끝난 뒤, 다음 장면이 1km 넘게 남았을 때만
        if (!lateAsked && !telling && Session.ask == null && f == null && src.km > 0.3 && src.speedKmh < 20 && (target?.let { it.km - src.km > 1.0 } != false)) {
            val late = stats.total - c.planMinutes(0.0, src.km) * 60
            if (late > maxOf(LATE_ASK_SEC, c.minutes * 60 * 0.12)) askLate(c, src.km, late)
        }
        if (Session.ask != null && detour != null && t > askDeadline) resolveLate(null)

        // 장면 사이가 비면 지나는 곳 이야기. 끝까지 말할 틈이 있을 때만 꺼낸다
        if (telling && !speaker.busy && t - tellAt > 1500) telling = false
        if (!telling && Session.ask == null && !speaker.busy && t - saidAt > 4000 && a.part in QUIET) {
            Docent.pick(c, src.km, told, Docent.gap(c, src.km, f != null && !forkAsked))?.let { (name, text) ->
                Log.d(MainActivity.TAG, "도슨트 $name")
                told += name
                telling = true
                tellAt = t
                say(text)
            }
        }
        // 질문하거나 이야기하는 동안 시뮬레이션은 1배로. 대답하기 전에 정체가 끝나거나 이야기 도중 장소를 지나치지 않게
        (src as? SimulatedDrive)?.hold = (Session.ask != null && detour != null) || telling

        if (t - notifiedAt > 4000) {
            notifiedAt = t
            nm.notify(ONGOING_ID, ongoing(c, "%s · %.1f km".format(Session.targetName, Session.remainKm)))
        }

        if (src.km >= c.km && src.speedKmh == 0f) finish()
    }

    // 묻고, 말이 끝나면 대답을 듣는다. 화면이 내비에 가려져 있어도 알림으로 버튼을 띄운다
    private fun ask(q: Ask, line: String) {
        Session.ask = q
        Session.heardText = null
        listenAfterTalk = true
        say(line)
        askDeadline = SystemClock.elapsedRealtime() + ASK_WAIT_MS
        nm.notify(ASK_ID, askNotice(q))
    }

    private fun closeAsk() {
        Session.ask = null
        listenAfterTalk = false
        listener.stop()
        nm.cancel(ASK_ID)
    }

    // 화면이나 알림 버튼. 왼쪽 오른쪽
    fun answer(left: Boolean) {
        if (detour != null) resolveLate(!left) else Session.course?.fork?.let { resolveFork(if (left) it.left else it.right) }
    }

    private fun askFork(f: Fork) {
        Log.d(MainActivity.TAG, "갈림길 질문 ${f.node.name}")
        forkAsked = true
        ask(Ask("곧 ${f.node.name} 갈림길", f.left.label, f.right.label, "말하거나 눌러서 골라 줘", arrows = true), Lines.forkAsk(f))
    }

    private fun askLate(c: Course, km: Double, lateSec: Double) {
        lateAsked = true
        val late = (lateSec / 60).roundToInt()
        val rest = c.planMinutes(km, c.km)
        val d = Composer.shortcut(RoadieApp.graph, c, km, rest - lateSec / 60)
        Log.d(MainActivity.TAG, "정체 ${late}분 늦음, 남은 %.0f분, 바꾸면 %s".format(rest, d?.let { "%.0f분".format(it.planMinutes(km, it.km)) }))
        if (d == null) return say(Lines.lateOnly(late))
        detour = d
        val save = (rest - d.planMinutes(km, d.km)).roundToInt()
        val skipped = c.scenes.filter { it.km > km }.map { it.name } - d.scenes.filter { it.km > km }.map { it.name }.toSet()
        val hint = if (skipped.isEmpty()) "말하거나 눌러서 골라 줘" else "길을 바꾸면 못 보는 곳: " + skipped.joinToString(", ")
        ask(Ask("막혀서 ${late}분 늦어지는 중", "그대로 가기", "길 바꾸기 (${save}분 단축)", hint), Lines.lateAsk(late, save))
    }

    fun onHeard(text: String, final: Boolean) {
        Session.heardText = text
        if (!final) return
        if (detour != null) Lines.pickLate(text)?.let { resolveLate(it) }
        else Session.course?.fork?.let { f -> Lines.pick(text, f)?.let { resolveFork(it) } }
    }

    // change: 길을 바꾸면 true, 그대로면 false, 대답 없으면 null
    private fun resolveLate(change: Boolean?) {
        val d = detour ?: return
        if (Session.ask == null) return
        closeAsk()
        detour = null
        val src = source ?: return
        Log.d(MainActivity.TAG, "정체 선택 ${if (change == true) "길 바꾸기" else "그대로"}${if (change == null) " (기본)" else ""}")
        if (change != true) return say(if (change == null) Lines.LATE_DEFAULT else Lines.LATE_KEPT)
        say(Lines.LATE_CHANGED)
        detoured = true
        Session.course = d
        (src as? SimulatedDrive)?.course = d
        arranger?.replace(d.scenes, src.km)
        routeTo(d)
    }

    fun resolveFork(choice: Choice?) {
        if (Session.ask == null) return
        closeAsk()
        val c = Session.course ?: return
        val f = c.fork ?: return
        val src = source ?: return
        val ch = choice ?: f.planned
        stats.picks += ch.label
        Log.d(MainActivity.TAG, "갈림길 선택 ${ch.label} ${if (choice == null) "(기본)" else ""}")
        say(if (choice != null) Lines.forkPicked(ch) else Lines.forkDefault(ch))

        var next = c.branch(ch)
        // 정체를 아직 안 겪었으면 새 길에도 넣는다
        if (simulate && c.segments.none { it.jam && it.untilKm < src.km }) next = next.withJam(src.km)
        if (simulate && !lateAsked) next = next.withCongestion(src.km)
        Session.course = next
        (src as? SimulatedDrive)?.course = next
        arranger?.replace(next.scenes, src.km)
        routeTo(next)
    }

    private fun onApproach(scene: Scene) {
        Log.d(MainActivity.TAG, "접근 ${scene.name}: ${scene.line}")
        say(scene.line)
    }

    private fun onDrop(scene: Scene) {
        Log.d(MainActivity.TAG, "드롭 ${scene.name}")
        passed += scene to System.currentTimeMillis()
        lastDrop = scene
        speaker.stop()
        Session.dropName = scene.name
        Session.dropSeq++
    }

    // 도착하거나 그만 달릴 때
    fun finish() {
        handler.removeCallbacks(tick)
        speaker.stop()
        listener.stop()
        val src = source
        val km = src?.km ?: 0.0
        src?.stop()
        source = null
        mixer?.release()
        mixer = null
        arranger = null
        Session.ask = null
        detour = null
        nm.cancel(ASK_ID)
        RoadieApp.naviCall { cancelRoute() }

        val c = Session.course
        if (c != null && km > 1.0) {
            val scenes = passed.map { it.first }
            val before = records.km
            val crossed = ((before + km) / 100).toInt().takeIf { it > (before / 100).toInt() }?.let { it * 100 }
            val line = (if (detoured) Lines.DETOURED + " " else "") + Lines.arrive(scenes, records.count == 0, crossed, records.visited(c.id))
            val note = Taste.note(c, km, stats)
            val drivenMin = (stats.total / 60).toInt()
            records.add(c.id, Drive(Records.stamp(), c.name, km, drivenMin, scenes.map { it.name }, note))
            records.saveTaste(Taste.learn(records.taste(), c, stats))
            Session.result = Result(c, km, passed.toList(), line, note, drivenMin, ((System.currentTimeMillis() - startedAt) / 60000).toInt(), simulate)
            Session.phase = Session.Phase.ARRIVED
            say(line)
            nm.notify(ARRIVE_ID, arriveNotice(c))
        } else {
            Session.phase = Session.Phase.HOME
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        instance = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mixer?.release()
        mixer = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    // 알림

    private fun channel(id: String, name: String, importance: Int) {
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(code: Int, label: String, what: String) = Notification.Action.Builder(
        Icon.createWithResource(this, R.drawable.ic_roadie), label,
        PendingIntent.getService(this, code, Intent(this, DriveService::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
    ).build()

    private fun ongoing(c: Course, text: String): Notification {
        channel(CH_DRIVE, "드라이브", NotificationManager.IMPORTANCE_LOW)
        return Notification.Builder(this, CH_DRIVE)
            .setSmallIcon(R.drawable.ic_roadie)
            .setContentTitle("로디와 드라이브 · ${c.name}")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open())
            .addAction(action(1, "그만 달리기", ACTION_STOP))
            .build()
    }

    private fun askNotice(q: Ask): Notification {
        channel(CH_FORK, "갈림길", NotificationManager.IMPORTANCE_HIGH)
        return Notification.Builder(this, CH_FORK)
            .setSmallIcon(R.drawable.ic_roadie)
            .setContentTitle(q.title)
            .setContentText(q.hint)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(open())
            .setAutoCancel(true)
            // 차량 알림은 버튼을 오른쪽부터 놓아서 거꾸로 넣는다
            .addAction(action(3, q.right, ACTION_RIGHT))
            .addAction(action(2, q.left, ACTION_LEFT))
            .build()
    }

    private fun arriveNotice(c: Course): Notification {
        channel(CH_ARRIVE, "도착", NotificationManager.IMPORTANCE_DEFAULT)
        return Notification.Builder(this, CH_ARRIVE)
            .setSmallIcon(R.drawable.ic_roadie)
            .setContentTitle("도착했어")
            .setContentText("${c.name} · 오늘의 드라이브 보기")
            .setContentIntent(open())
            .setAutoCancel(true)
            .build()
    }

    companion object {
        const val TICK_MS = 50L
        // 내비 안내 음성은 요청 뒤 늦으면 6초쯤 지나서 나온다
        const val NAVI_VOICE_MS = 7000L
        const val ASK_WAIT_MS = 16000L
        val QUIET = setOf(Part.IDLE, Part.CRUISE_A, Part.CRUISE_B)
        const val LATE_ASK_SEC = 240.0
        const val HERE_SHIFT = 0.0015
        const val ACTION_STOP = "roadie.STOP"
        const val ACTION_LEFT = "roadie.LEFT"
        const val ACTION_RIGHT = "roadie.RIGHT"
        const val CH_DRIVE = "drive"
        const val CH_FORK = "fork"
        const val CH_ARRIVE = "arrive"
        const val ONGOING_ID = 1
        const val ASK_ID = 2
        const val ARRIVE_ID = 3

        var instance: DriveService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DriveService::class.java))
        }
    }
}
