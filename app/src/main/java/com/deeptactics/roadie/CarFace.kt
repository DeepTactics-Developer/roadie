package com.deeptactics.roadie

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

// 반려차 얼굴. 전조등이 눈, 그릴이 입이다. 말하면 입이 움직이고 기분에 따라 눈이 바뀐다
class CarFace(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    enum class Mood { CALM, HAPPY, EXCITED, SLEEPY, CURIOUS, LISTENING }

    var mood = Mood.CALM
    var talking = false
    var talkingOf: (() -> Boolean)? = null
    var driving = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val r = RectF()
    private val path = Path()
    private var blinkAt = 0L
    private val born = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - born) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w / 1.3f, h) // 기준 크기
        val cx = w / 2
        // 달릴 땐 통통 튀고, 신나면 더 튄다
        val bounce = when {
            mood == Mood.EXCITED -> abs(sin(t * 7f)) * s * 0.04f
            driving -> abs(sin(t * 5f)) * s * 0.012f
            mood == Mood.SLEEPY -> sin(t * 1.5f) * s * 0.01f
            else -> sin(t * 2f) * s * 0.006f
        }
        val tilt = if (mood == Mood.CURIOUS) -7f else 0f
        canvas.save()
        canvas.rotate(tilt, cx, h * 0.6f)
        canvas.translate(0f, -bounce)

        val bodyW = s * 1.1f
        val bodyTop = h * 0.42f
        val bodyBottom = h * 0.86f
        val left = cx - bodyW / 2
        val right = cx + bodyW / 2

        // 그림자
        p.shader = null
        p.color = 0x55000000
        canvas.drawOval(left + s * 0.05f, h * 0.9f + bounce, right - s * 0.05f, h * 0.97f + bounce, p)

        // 바퀴
        p.color = 0xFF0D1218.toInt()
        r.set(left + s * 0.08f, bodyBottom - s * 0.06f, left + s * 0.3f, bodyBottom + s * 0.08f)
        canvas.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
        r.set(right - s * 0.3f, bodyBottom - s * 0.06f, right - s * 0.08f, bodyBottom + s * 0.08f)
        canvas.drawRoundRect(r, s * 0.05f, s * 0.05f, p)

        // 지붕과 앞유리
        path.reset()
        path.moveTo(left + s * 0.2f, bodyTop + s * 0.02f)
        path.lineTo(left + s * 0.3f, h * 0.16f)
        path.quadTo(cx, h * 0.12f, right - s * 0.3f, h * 0.16f)
        path.lineTo(right - s * 0.2f, bodyTop + s * 0.02f)
        path.close()
        p.color = 0xFF2E7FB0.toInt()
        canvas.drawPath(path, p)
        path.reset()
        path.moveTo(left + s * 0.26f, bodyTop)
        path.lineTo(left + s * 0.34f, h * 0.2f)
        path.quadTo(cx, h * 0.165f, right - s * 0.34f, h * 0.2f)
        path.lineTo(right - s * 0.26f, bodyTop)
        path.close()
        p.color = 0xFF16273A.toInt()
        canvas.drawPath(path, p)
        stroke.color = 0x40FFFFFF
        stroke.strokeWidth = s * 0.02f
        canvas.drawLine(left + s * 0.4f, h * 0.24f, left + s * 0.33f, bodyTop - s * 0.03f, stroke)

        // 몸통
        p.shader = LinearGradient(0f, bodyTop, 0f, bodyBottom, 0xFF5CCBFA.toInt(), 0xFF2B8FC4.toInt(), Shader.TileMode.CLAMP)
        r.set(left, bodyTop, right, bodyBottom)
        canvas.drawRoundRect(r, s * 0.14f, s * 0.14f, p)
        p.shader = null

        // 볼
        if (mood == Mood.HAPPY || mood == Mood.EXCITED) {
            p.color = 0xAAFF8A80.toInt()
            canvas.drawCircle(left + s * 0.14f, bodyTop + s * 0.25f, s * 0.06f, p)
            canvas.drawCircle(right - s * 0.14f, bodyTop + s * 0.25f, s * 0.06f, p)
        }

        // 눈(전조등)
        val eyeY = bodyTop + s * 0.15f
        val eyeR = s * if (mood == Mood.EXCITED) 0.11f else 0.095f
        val now = SystemClock.uptimeMillis()
        if (now > blinkAt + 4000) blinkAt = now + (Math.random() * 2500).toLong()
        val blink = now in blinkAt..blinkAt + 140
        for (side in listOf(-1, 1)) {
            val ex = cx + side * s * 0.3f
            val er = if (mood == Mood.CURIOUS && side == 1) eyeR * 1.2f else eyeR
            when {
                mood == Mood.HAPPY || (mood == Mood.EXCITED && blink) -> {
                    stroke.color = 0xFFFFFFFF.toInt()
                    stroke.strokeWidth = s * 0.03f
                    r.set(ex - er, eyeY - er * 0.5f, ex + er, eyeY + er * 1.2f)
                    canvas.drawArc(r, 200f, 140f, false, stroke)
                }
                blink -> {
                    stroke.color = 0xFFFFFFFF.toInt()
                    stroke.strokeWidth = s * 0.03f
                    canvas.drawLine(ex - er, eyeY, ex + er, eyeY, stroke)
                }
                else -> {
                    p.color = 0xFFFFFFFF.toInt()
                    canvas.drawCircle(ex, eyeY, er, p)
                    val look = when (mood) {
                        Mood.LISTENING -> 0f to -er * 0.35f
                        Mood.CURIOUS -> er * 0.3f to 0f
                        else -> sin(t * 0.7f) * er * 0.25f to er * 0.1f
                    }
                    p.color = 0xFF10202E.toInt()
                    canvas.drawCircle(ex + look.first, eyeY + look.second, er * 0.5f, p)
                    p.color = 0xFFFFFFFF.toInt()
                    canvas.drawCircle(ex + look.first + er * 0.18f, eyeY + look.second - er * 0.18f, er * 0.16f, p)
                    if (mood == Mood.SLEEPY) {
                        // 눈꺼풀
                        p.color = 0xFF4DB8EA.toInt()
                        canvas.drawRect(ex - er - 2, eyeY - er - 2, ex + er + 2, eyeY + er * 0.1f, p)
                    }
                }
            }
        }

        // 입(그릴). 말할 때 열렸다 닫혔다
        val open = if (talking || talkingOf?.invoke() == true) 0.3f + 0.7f * abs(sin(t * 13f)) * (0.6f + 0.4f * abs(sin(t * 3.1f))) else 0f
        val mouthW = s * if (mood == Mood.HAPPY || mood == Mood.EXCITED) 0.36f else 0.3f
        val mouthY = bodyTop + s * 0.32f
        val mouthH = s * (0.035f + 0.09f * open)
        p.color = 0xFF10202E.toInt()
        r.set(cx - mouthW / 2, mouthY - mouthH / 2, cx + mouthW / 2, mouthY + mouthH / 2)
        canvas.drawRoundRect(r, mouthH / 2, mouthH / 2, p)
        if (!(talking || talkingOf?.invoke() == true) && (mood == Mood.HAPPY || mood == Mood.EXCITED || mood == Mood.CALM)) {
            stroke.color = 0xFF10202E.toInt()
            stroke.strokeWidth = s * 0.022f
            r.set(cx - mouthW / 2, mouthY - s * 0.08f, cx + mouthW / 2, mouthY + s * 0.04f)
            canvas.drawArc(r, 20f, 140f, false, stroke)
        }

        // 번호판 자리에 이름
        p.color = 0xFFEAF6FD.toInt()
        r.set(cx - s * 0.13f, bodyBottom - s * 0.11f, cx + s * 0.13f, bodyBottom - s * 0.03f)
        canvas.drawRoundRect(r, s * 0.015f, s * 0.015f, p)
        p.color = 0xFF10202E.toInt()
        p.textSize = s * 0.055f
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        canvas.drawText("ROADIE", cx, bodyBottom - s * 0.05f, p)

        // 듣는 중이면 옆으로 소리 물결
        if (mood == Mood.LISTENING) {
            stroke.color = 0xAAFFB74D.toInt()
            stroke.strokeWidth = s * 0.02f
            for (k in 0 until 3) {
                val rr = s * (0.1f + k * 0.07f) + (t * 40f % 20f)
                for (side in listOf(-1, 1)) {
                    r.set(cx + side * (bodyW / 2 + s * 0.02f) - rr, eyeY - rr, cx + side * (bodyW / 2 + s * 0.02f) + rr, eyeY + rr)
                    canvas.drawArc(r, if (side < 0) 150f else -30f, 60f, false, stroke)
                }
            }
        }
        // 신나면 반짝이
        if (mood == Mood.EXCITED) {
            p.color = 0xFFFFE082.toInt()
            for (k in 0 until 4) {
                val a = t * 2 + k * 1.57f
                star(canvas, cx + sin(a) * s * 0.75f, h * 0.3f + sin(a * 1.3f) * s * 0.2f, s * 0.04f * (0.6f + 0.4f * abs(sin(t * 4 + k))))
            }
        }
        if (mood == Mood.SLEEPY) {
            p.color = 0xAAFFFFFF.toInt()
            p.textSize = s * (0.08f + 0.02f * sin(t))
            canvas.drawText("z", right - s * 0.05f, h * 0.18f - (t * 10f % 20f), p)
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }

    private fun star(canvas: Canvas, x: Float, y: Float, k: Float) {
        path.reset()
        path.moveTo(x, y - k * 2)
        path.quadTo(x, y, x + k * 2, y)
        path.quadTo(x, y, x, y + k * 2)
        path.quadTo(x, y, x - k * 2, y)
        path.quadTo(x, y, x, y - k * 2)
        canvas.drawPath(path, p)
    }
}
