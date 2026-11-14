package com.deeptactics.roadie

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos

// 코스를 지도처럼 그린다. 장면 위치, 갈림길, 지금 위치까지
class RouteView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var course: Course? = null
        set(v) {
            field = v
            invalidate()
        }
    var km = -1.0 // 0 이상이면 지금 위치를 찍는다
    var labels = true
    var showFork = true

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F7FA.toInt() }
    private val path = Path()

    private var minLat = 0.0
    private var maxLat = 0.0
    private var minLon = 0.0
    private var maxLon = 0.0
    private var scale = 1.0
    private var ox = 0f
    private var oy = 0f

    private fun fit(nodes: List<Node>) {
        minLat = nodes.minOf { it.lat }
        maxLat = nodes.maxOf { it.lat }
        minLon = nodes.minOf { it.lon }
        maxLon = nodes.maxOf { it.lon }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val pad = minOf(width, height) * 0.14f
        val gw = ((maxLon - minLon) * k).coerceAtLeast(1e-4)
        val gh = (maxLat - minLat).coerceAtLeast(1e-4)
        scale = minOf((width - 2 * pad) / gw, (height - 2 * pad) / gh)
        ox = (width - (gw * scale).toFloat()) / 2
        oy = (height - (gh * scale).toFloat()) / 2
    }

    private fun x(n: Node) = ox + ((n.lon - minLon) * cos(Math.toRadians((minLat + maxLat) / 2)) * scale).toFloat()
    private fun y(n: Node) = height - oy - ((n.lat - minLat) * scale).toFloat()

    // 코스 위 km 지점의 화면 좌표
    private fun at(c: Course, km: Double): Pair<Float, Float> {
        val leg = c.legs.firstOrNull { km <= it.toKm } ?: c.legs.last()
        val f = ((km - leg.fromKm) / leg.edge.km).coerceIn(0.0, 1.0).toFloat()
        return x(leg.from) + (x(leg.to) - x(leg.from)) * f to y(leg.from) + (y(leg.to) - y(leg.from)) * f
    }

    private fun stroke(legs: List<Leg>) {
        path.reset()
        path.moveTo(x(legs.first().from), y(legs.first().from))
        for (l in legs) path.lineTo(x(l.to), y(l.to))
    }

    override fun onDraw(canvas: Canvas) {
        val c = course ?: return
        val fork = c.fork?.takeIf { showFork }
        val alt = fork?.let { if (it.left === it.planned) it.right else it.left }
        fit(c.points + (alt?.tail?.map { it.to } ?: emptyList()))
        val u = minOf(width, height) / 100f

        if (alt != null) {
            line.color = 0x99FFB74D.toInt()
            line.strokeWidth = u * 1.4f
            line.pathEffect = DashPathEffect(floatArrayOf(u * 2.5f, u * 2f), 0f)
            stroke(alt.tail)
            canvas.drawPath(path, line)
            line.pathEffect = null
        }
        line.color = 0x664FC3F7
        line.strokeWidth = u * 2.6f
        stroke(c.legs)
        canvas.drawPath(path, line)

        // 지나온 부분
        if (km >= 0) {
            line.color = 0xFF4FC3F7.toInt()
            path.reset()
            path.moveTo(x(c.start), y(c.start))
            for (l in c.legs) {
                if (km >= l.toKm) path.lineTo(x(l.to), y(l.to)) else {
                    val (px, py) = at(c, km)
                    path.lineTo(px, py)
                    break
                }
            }
            canvas.drawPath(path, line)
        }

        // 출발지
        p.color = 0xFFF5F7FA.toInt()
        canvas.drawRoundRect(x(c.start) - u * 2.2f, y(c.start) - u * 2.2f, x(c.start) + u * 2.2f, y(c.start) + u * 2.2f, u, u, p)
        text.textSize = u * 3.6f
        if (labels) canvas.drawText("출발", x(c.start) + u * 3.5f, y(c.start) + u * 1.2f, text)

        // 갈림길
        if (fork != null) {
            val fx = x(fork.node)
            val fy = y(fork.node)
            p.color = 0xFFFFB74D.toInt()
            path.reset()
            path.moveTo(fx, fy - u * 3)
            path.lineTo(fx + u * 3, fy)
            path.lineTo(fx, fy + u * 3)
            path.lineTo(fx - u * 3, fy)
            path.close()
            canvas.drawPath(path, p)
        }

        // 장면
        for (s in c.scenes) {
            val (sx, sy) = at(c, s.km)
            val passed = km >= 0 && km >= s.km
            p.color = 0xFF0B0F17.toInt()
            canvas.drawCircle(sx, sy, u * 2.6f, p)
            p.color = if (passed) 0xFF5A6578.toInt() else kindColor(s.kind)
            canvas.drawCircle(sx, sy, u * 1.9f, p)
            if (labels) {
                text.color = if (passed) 0xFF8C98AD.toInt() else 0xFFF5F7FA.toInt()
                canvas.drawText(s.name, sx + u * 3.4f, sy + u * 1.2f, text)
                text.color = 0xFFF5F7FA.toInt()
            }
        }

        // 지금 위치
        if (km >= 0) {
            val (cx, cy) = at(c, km)
            p.color = 0x554FC3F7
            canvas.drawCircle(cx, cy, u * 4.5f, p)
            p.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx, cy, u * 2.2f, p)
        }
    }

    companion object {
        fun kindColor(k: SceneKind) = when (k) {
            SceneKind.SEA, SceneKind.TUNNEL_EXIT -> 0xFF4FC3F7.toInt()
            SceneKind.LAKE -> 0xFF80DEEA.toInt()
            SceneKind.PASS, SceneKind.VIEW -> 0xFF9CCC65.toInt()
            SceneKind.BRIDGE -> 0xFFCE93D8.toInt()
        }
    }
}
