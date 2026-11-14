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
    // 패드, 베이스, 아르페지오, 리드, 드럼. 종이 바탕에 맞춘 차분한 색
    private val colors = intArrayOf(0xFF6F7C8A.toInt(), 0xFF24968A.toInt(), 0xFF3A86C8.toInt(), 0xFFC98A00.toInt(), 0xFFE8572A.toInt())

    override fun onDraw(canvas: Canvas) {
        val n = 5
        val gap = width * 0.08f
        val bw = (width - gap * (n - 1)) / n
        val pulse = 1f - 0.18f * beat
        for (i in 0 until n) {
            shown[i] += (levels[i] - shown[i]) * 0.35f
            val h = maxOf(height * shown[i] * pulse, height * 0.08f)
            val x = i * (bw + gap)
            val r = minOf(bw, height.toFloat()) * 0.1f
            p.color = 0xFFEEEAE1.toInt()
            canvas.drawRoundRect(x, 0f, x + bw, height.toFloat(), r, r, p)
            p.color = colors[i]
            canvas.drawRoundRect(x, height - h, x + bw, height.toFloat(), r, r, p)
        }
    }
}
