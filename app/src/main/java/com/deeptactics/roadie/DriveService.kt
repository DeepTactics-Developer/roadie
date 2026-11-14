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
    private var forkListenAfterTalk = false
    private var forkDeadline = 0L
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
            ACTION_LEFT -> Session.course?.fork?.let { resolveFork(it.left) }
            ACTION_RIGHT -> Session.course?.fork?.let { resolveFork(it.right) }
            else -> if (source == null) begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val plan = Session.plan ?: return stopSelf()
        instance = this
        startForeground(ONGOING_ID, ongoing(plan, "출발 준비"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        simulate = Session.simulate
        val c = if (simulate) plan.withJam() else plan
        Session.course = c
        source = if (simulate) SimulatedDrive(c, Session.warp) else CarDrive(this)
        val a = Arranger(::onApproach, ::onDrop).apply { scenes = c.scenes }
        arranger = a
        mixer = Mixer(this, a).also { it.start() }
        passed.clear()
        stats = DriveStats()
        forkAsked = false
        forkListenAfterTalk = false
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
            forkOpen = false
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
        val dest = RouteInfo(
            latitude = home.lat, longitude = home.lon, displayLongitude = null, displayLatitude = null,
            poiName = home.name, poiId = "", poiSubId = "0", address = home.name,
        )
        requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, wps))
    }

    private fun say(text: String) {
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

        // 말하는 동안 음악을 낮추고, 갈림길 질문이 끝나면 대답을 듣기 시작한다
        val talking = speaker.talking
        m.duck(talking)
        if (wasTalking && !talking && forkListenAfterTalk) {
            forkListenAfterTalk = false
            if (Session.forkOpen) listener.listen()
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

        val f = c.fork
        if (f != null && !forkAsked && src.km >= f.km - c.forkAskKm()) askFork(f)
        if (Session.forkOpen && f != null && (t > forkDeadline || src.km >= f.km - 0.05)) resolveFork(null)

        if (t - notifiedAt > 4000) {
            notifiedAt = t
            nm.notify(ONGOING_ID, ongoing(c, "%s · %.1f km".format(Session.targetName, Session.remainKm)))
        }

        if (src.km >= c.km && src.speedKmh == 0f) finish()
    }

    private fun askFork(f: Fork) {
        Log.d(MainActivity.TAG, "갈림길 질문 ${f.node.name}")
        forkAsked = true
        Session.forkOpen = true
        Session.heardText = null
        forkListenAfterTalk = true
        say(Lines.forkAsk(f))
        forkDeadline = SystemClock.elapsedRealtime() + FORK_WAIT_MS
        // 화면이 내비에 가려져 있어도 알림으로 버튼을 띄운다
        nm.notify(FORK_ID, forkNotice(f))
    }

    fun onHeard(text: String, final: Boolean) {
        Session.heardText = text
        val f = Session.course?.fork ?: return
        if (final) Lines.pick(text, f)?.let { resolveFork(it) }
    }

    fun resolveFork(choice: Choice?) {
        if (!Session.forkOpen) return
        Session.forkOpen = false
        forkListenAfterTalk = false
        listener.stop()
        nm.cancel(FORK_ID)
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
        Session.forkOpen = false
        nm.cancel(FORK_ID)
        RoadieApp.naviCall { cancelRoute() }

        val c = Session.course
        if (c != null && km > 1.0) {
            val scenes = passed.map { it.first }
            val before = records.km
            val crossed = ((before + km) / 100).toInt().takeIf { it > (before / 100).toInt() }?.let { it * 100 }
            val line = Lines.arrive(scenes, records.count == 0, crossed, records.visited(c.id))
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

    private fun forkNotice(f: Fork): Notification {
        channel(CH_FORK, "갈림길", NotificationManager.IMPORTANCE_HIGH)
        return Notification.Builder(this, CH_FORK)
            .setSmallIcon(R.drawable.ic_roadie)
            .setContentTitle("곧 ${f.node.name} 갈림길")
            .setContentText("말하거나 눌러서 골라 줘")
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(open())
            .setAutoCancel(true)
            .addAction(action(2, "← ${f.left.label}", ACTION_LEFT))
            .addAction(action(3, "${f.right.label} →", ACTION_RIGHT))
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
        const val FORK_WAIT_MS = 16000L
        const val ACTION_STOP = "roadie.STOP"
        const val ACTION_LEFT = "roadie.LEFT"
        const val ACTION_RIGHT = "roadie.RIGHT"
        const val CH_DRIVE = "drive"
        const val CH_FORK = "fork"
        const val CH_ARRIVE = "arrive"
        const val ONGOING_ID = 1
        const val FORK_ID = 2
        const val ARRIVE_ID = 3

        var instance: DriveService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DriveService::class.java))
        }
    }
}
