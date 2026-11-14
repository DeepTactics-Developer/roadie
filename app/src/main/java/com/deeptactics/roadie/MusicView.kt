package com.deeptactics.roadie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

// 스템별 음악 세기 막대. 글자 없이
class MusicView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var levels = FloatArray(5)
    var beat = 0f // 박 안에서의 위치 0~1

    private val shown = FloatArray(5)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(0xFF7986CB.toInt(), 0xFF4DB6AC.toInt(), 0xFF4FC3F7.toInt(), 0xFFFFCA28.toInt(), 0xFFFF7043.toInt())

    override fun onDraw(canvas: Canvas) {
        val n = 5
        val gap = width * 0.03f
        val bw = (width - gap * (n - 1)) / n
        val pulse = 1f - 0.18f * beat
        for (i in 0 until n) {
            shown[i] += (levels[i] - shown[i]) * 0.35f
            val h = maxOf(height * shown[i] * pulse, bw * 0.3f)
            val x = i * (bw + gap)
            p.color = colors[i] and 0x33FFFFFF
            canvas.drawRoundRect(x, 0f, x + bw, height.toFloat(), bw / 2, bw / 2, p)
            p.color = colors[i]
            canvas.drawRoundRect(x, height - h, x + bw, height.toFloat(), bw / 2, bw / 2, p)
        }
    }
}
