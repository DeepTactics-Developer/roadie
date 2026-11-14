package com.deeptactics.roadie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos

// 코스를 지도 위에 그린다. 배경 지도(바다, 물, 녹지, 도로)는 코스가 바뀔 때만 그림으로 만들어 두고,
// 그 위에 경로, 장면, 갈림길, 지금 위치를 매번 그린다
class RouteView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var course: Course? = null
        set(v) {
            if (field === v) return
            field = v
            base = null
            altTrack = null
            invalidate()
        }
    var km = -1.0 // 0 이상이면 지나온 길과 지금 위치를 그린다
    var labels = true
    var showFork = true
    var driven = false // 도착 화면. 달린 길을 앱 색으로 진하게

    private fun c(id: Int) = context.getColor(id)
    private val land = c(R.color.map_land)
    private val green = c(R.color.map_green)
    private val water = c(R.color.map_water)
    private val road = c(R.color.map_road)
    private val casing = c(R.color.map_casing)
    private val accent = c(R.color.accent)
    private val passedColor = c(R.color.ink3)
    private val ink = c(R.color.ink)
    private val warn = c(R.color.warn)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = 0xF2FFFFFF.toInt()
        isFakeBoldText = true
    }
    private val path = Path()

    private var altTrack: Track? = null
    private var base: Bitmap? = null
    private var baseOf: BaseMap? = null

    // 투영. 경도는 위도에 따라 줄여서 모양이 찌그러지지 않게
    private var minLat = 0.0
    private var minLon = 0.0
    private var k = 1.0
    private var scale = 1.0
    private var ox = 0f
    private var oy = 0f

    private fun x(lon: Double) = ox + ((lon - minLon) * k * scale).toFloat()
    private fun y(lat: Double) = height - oy - ((lat - minLat) * scale).toFloat()

    private fun fit(c: Course, alt: List<Leg>) {
        val t = c.track
        var s = t.lat.min()
        var n = t.lat.max()
        var w = t.lon.min()
        var e = t.lon.max()
        for (l in alt) for (i in l.shape().indices step 2) {
            val sh = l.shape()
            s = minOf(s, sh[i]); n = maxOf(n, sh[i]); w = minOf(w, sh[i + 1]); e = maxOf(e, sh[i + 1])
        }
        k = cos(Math.toRadians((s + n) / 2))
        val pad = minOf(width, height) * 0.12f
        val gw = ((e - w) * k).coerceAtLeast(1e-3)
        val gh = (n - s).coerceAtLeast(1e-3)
        scale = minOf((width - 2 * pad) / gw, (height - 2 * pad) / gh)
        minLat = s
        minLon = w
        ox = ((width - gw * scale) / 2).toFloat()
        oy = ((height - gh * scale) / 2).toFloat()
    }

    private fun shapePath(p: DoubleArray, close: Boolean) {
        path.moveTo(x(p[1]), y(p[0]))
        for (i in 2 until p.size step 2) path.lineTo(x(p[i + 1]), y(p[i]))
        if (close) path.close()
    }

    // 배경 지도를 그림 한 장으로
    private fun drawBase(m: BaseMap?): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(land)
        if (m == null) return bmp
        // 화면에 보이는 위경도 범위
        val s = minLat - (oy / scale)
        val n = minLat + (height - oy) / scale
        val w = minLon - ox / (k * scale)
        val e = minLon + (width - ox) / (k * scale)
        fun areas(list: List<Area>, color: Int) {
            fill.color = color
            for (a in list) {
                if (!a.rings[0].overlaps(s, w, n, e)) continue
                path.reset()
                path.fillType = Path.FillType.EVEN_ODD
                for (r in a.rings) shapePath(r.pts, true)
                cv.drawPath(path, fill)
            }
        }
        areas(m.green, green)
        areas(m.sea, water)
        areas(m.water, water)
        val u = resources.displayMetrics.density
        line.pathEffect = null
        line.color = water
        line.strokeWidth = 3f * u
        for (r in m.river) if (r.overlaps(s, w, n, e)) {
            path.reset(); shapePath(r.pts, false); cv.drawPath(path, line)
        }
        // 도로는 테두리를 먼저 깔고 위에 흰 길을. 큰 길이 위로 오게 작은 길부터
        val widths = floatArrayOf(7f, 5f, 3.6f, 2.2f)
        for (pass in 0..1) for ((cls, sh) in m.roads) {
            if (!sh.overlaps(s, w, n, e)) continue
            line.color = if (pass == 0) casing else road
            line.strokeWidth = (widths[cls.coerceIn(0, 3)] + if (pass == 0) 2.2f else 0f) * u
            path.reset(); shapePath(sh.pts, false); cv.drawPath(path, line)
        }
        return bmp
    }

    // 코스 위 km 지점의 화면 좌표
    private fun at(c: Course, km: Double): Pair<Float, Float> = c.track.at(km).let { (la, lo) -> x(lo) to y(la) }

    // a~b km 구간 경로
    private fun trackPath(c: Course, a: Double, b: Double) {
        val t = c.track
        path.reset()
        val (sx, sy) = at(c, a)
        path.moveTo(sx, sy)
        for (i in t.km.indices) if (t.km[i] > a && t.km[i] < b) path.lineTo(x(t.lon[i]), y(t.lat[i]))
        val (ex, ey) = at(c, b)
        path.lineTo(ex, ey)
    }

    private fun label(cv: Canvas, s: String, px: Float, py: Float, size: Float) {
        text.textSize = size
        halo.textSize = size
        halo.strokeWidth = size * 0.35f
        cv.drawText(s, px, py, halo)
        text.color = ink
        cv.drawText(s, px, py, text)
    }

    override fun onDraw(canvas: Canvas) {
        val c = course ?: return
        if (width == 0 || height == 0) return
        val fork = c.fork?.takeIf { showFork }
        val alt = fork?.let { if (it.left === it.planned) it.right else it.left }?.tail.orEmpty()
        val m = BaseMap.current
        if (base == null || baseOf !== m) {
            fit(c, alt)
            base = drawBase(m)
            baseOf = m
        }
        canvas.drawBitmap(base!!, 0f, 0f, null)
        if (m == null) postInvalidateDelayed(300) // 배경 지도를 읽는 중

        val u = resources.displayMetrics.density
        // 갈림길의 다른 길
        if (alt.isNotEmpty()) {
            path.reset()
            val t = altTrack ?: Track(alt).also { altTrack = it }
            path.moveTo(x(t.lon[0]), y(t.lat[0]))
            for (j in 1 until t.lat.size) path.lineTo(x(t.lon[j]), y(t.lat[j]))
            line.color = warn
            line.strokeWidth = 5f * u
            line.pathEffect = DashPathEffect(floatArrayOf(12f * u, 9f * u), 0f)
            canvas.drawPath(path, line)
        }

        // 코스. 흰 테두리 위에 색 선, 지도 분석이 없는 연결 구간은 점선
        for (pass in 0..1) for (l in c.legs) {
            trackPath(c, l.fromKm, l.toKm)
            line.pathEffect = if (l.edge.connector && pass == 1) DashPathEffect(floatArrayOf(6f * u, 7f * u), 0f) else null
            line.color = if (pass == 0) 0xFFFFFFFF.toInt() else accent
            line.strokeWidth = (if (pass == 0) 13f else 7f) * u
            if (!(l.edge.connector && pass == 0)) canvas.drawPath(path, line)
        }
        line.pathEffect = null

        // 지나온 길은 흐리게
        if (km > 0) {
            trackPath(c, 0.0, km.coerceAtMost(c.km))
            line.color = if (driven) accent else passedColor
            line.strokeWidth = (if (driven) 8f else 7f) * u
            canvas.drawPath(path, line)
        }

        // 출발지(돌아올 곳)
        val (hx, hy) = at(c, 0.0)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(hx, hy, 14f * u, fill)
        fill.color = ink
        canvas.drawCircle(hx, hy, 9f * u, fill)
        if (labels) label(canvas, "출발", hx + 18f * u, hy + 7f * u, 19f * u)

        // 갈림길
        if (fork != null) {
            val (fx, fy) = at(c, fork.km)
            path.reset()
            val r = 13f * u
            path.moveTo(fx, fy - r); path.lineTo(fx + r, fy); path.lineTo(fx, fy + r); path.lineTo(fx - r, fy); path.close()
            line.color = 0xFFFFFFFF.toInt()
            line.strokeWidth = 3f * u
            canvas.drawPath(path, line)
            fill.color = warn
            canvas.drawPath(path, fill)
        }

        // 장면
        for (s in c.scenes) {
            val (sx, sy) = at(c, s.km)
            val passed = km >= 0 && km >= s.km
            fill.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(sx, sy, 13f * u, fill)
            fill.color = if (passed) passedColor else kindColor(context, s.kind)
            canvas.drawCircle(sx, sy, 9f * u, fill)
            if (labels) label(canvas, s.name, sx + 18f * u, sy + 7f * u, 19f * u)
        }

        // 지금 위치
        if (km >= 0) {
            val (cx, cy) = at(c, km.coerceAtMost(c.km))
            fill.color = (accent and 0x00FFFFFF) or 0x33000000
            canvas.drawCircle(cx, cy, 26f * u, fill)
            fill.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx, cy, 14f * u, fill)
            fill.color = ink
            canvas.drawCircle(cx, cy, 9f * u, fill)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        base = null
    }

    companion object {
        fun kindColor(context: Context, k: SceneKind) = context.getColor(
            when (k) {
                SceneKind.SEA, SceneKind.TUNNEL_EXIT -> R.color.scene_sea
                SceneKind.LAKE -> R.color.scene_lake
                SceneKind.RIVER -> R.color.scene_river
                SceneKind.PASS, SceneKind.VIEW -> R.color.scene_mountain
                SceneKind.BRIDGE -> R.color.scene_bridge
            },
        )
    }
}
