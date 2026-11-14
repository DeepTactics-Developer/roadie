package com.deeptactics.roadie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

// 둥근 마이크 버튼. 듣는 동안 물결이 퍼진다
class MicButton(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var listening = false
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val r = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = minOf(width, height) / 2f * 0.72f
        if (listening) {
            val t = SystemClock.uptimeMillis() % 1500 / 1500f
            for (k in 0 until 2) {
                val f = (t + k * 0.5f) % 1f
                p.color = 0xE8572A or ((1 - f) * 110).toInt().shl(24)
                canvas.drawCircle(cx, cy, rad * (1 + 0.38f * f), p)
            }
            postInvalidateOnAnimation()
        }
        p.color = if (listening) 0xFFE8572A.toInt() else if (isPressed) 0xFF3A3631.toInt() else 0xFF1E1C19.toInt()
        canvas.drawCircle(cx, cy, rad, p)

        // 마이크 모양
        val u = rad / 10
        p.color = 0xFFFFFFFF.toInt()
        r.set(cx - u * 2.2f, cy - u * 5f, cx + u * 2.2f, cy + u * 1.6f)
        canvas.drawRoundRect(r, u * 2.2f, u * 2.2f, p)
        s.color = 0xFFFFFFFF.toInt()
        s.strokeWidth = u * 0.9f
        r.set(cx - u * 3.6f, cy - u * 3.4f, cx + u * 3.6f, cy + u * 3.4f)
        canvas.drawArc(r, 15f, 150f, false, s)
        canvas.drawLine(cx, cy + u * 3.4f, cx, cy + u * 5.2f, s)
        canvas.drawLine(cx - u * 2f, cy + u * 5.2f, cx + u * 2f, cy + u * 5.2f, s)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
