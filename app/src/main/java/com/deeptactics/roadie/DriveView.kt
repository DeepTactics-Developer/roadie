package com.deeptactics.roadie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

// 위: 코스 진행 바와 장면 마커, 아래: 레이어별 음악 막대(글자 없음)
class DriveView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var progress = 0f
    var markers = FloatArray(0)
    var levels = FloatArray(5)
    var beat = 0f // 박 안에서의 위치 0~1

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A313C.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFF4FC3F7.toInt(), 0xFFFFCA28.toInt(), 0xFFEF5350.toInt())

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val r = 24f
        val y = 40f
        canvas.drawRoundRect(r, y - 8, w - r, y + 8, 8f, 8f, track)
        canvas.drawRoundRect(r, y - 8, r + (w - 2 * r) * progress, y + 8, 8f, 8f, fill)
        for (m in markers) {
            dot.color = if (m <= progress) ACCENT else 0xFFFFFFFF.toInt()
            canvas.drawCircle(r + (w - 2 * r) * m, y, 22f, dot)
        }

        // 박자에 맞춰 살짝 들썩이게
        val pulse = 1f - 0.25f * beat
        val top = 120f
        val bottom = height - 20f
        val slot = w / 5
        for (i in 0 until 5) {
            val h = (bottom - top) * levels[i] * pulse
            bar.color = colors[i]
            bar.alpha = (80 + 175 * levels[i]).toInt()
            canvas.drawRoundRect(slot * i + slot * 0.2f, bottom - h, slot * i + slot * 0.8f, bottom, 16f, 16f, bar)
        }
    }

    companion object {
        const val ACCENT = 0xFF4FC3F7.toInt()
    }
}
