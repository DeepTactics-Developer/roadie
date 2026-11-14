package com.deeptactics.roadie

import kotlin.math.min
import kotlin.math.sqrt

// 주행 데이터 공급. 화면 쪽에서 주기적으로 update(경과 초)를 부른다
interface DriveSource {
    val speedKmh: Float
    val km: Double
    fun update(dt: Double)
    fun stop() {}

    // 앞으로 달릴 모습을 아는 경우에만 targetKm까지 걸릴 초를 준다
    fun etaSec(targetKm: Double): Double? = null
}

// 코스 구간 속도대로 가짜 주행. warp배로 빨리 감다가 장면 앞에서는 1배로 늦춘다.
// plan이면 지도에 없는 정체를 모르는 채로 달린다(도착 예상용)
class SimulatedDrive(var course: Course, private val warp: Double, private val plan: Boolean = false) : DriveSource {
    override var speedKmh = 0f
        private set
    override var km = 0.0
        private set

    // 배속 중엔 평균 속도로 도착 시간을 잴 수 없어서, 같은 모델을 미리 돌려 본다
    override fun etaSec(targetKm: Double): Double {
        val ghost = SimulatedDrive(course, warp, plan = true)
        ghost.km = km
        ghost.speedKmh = speedKmh
        var t = 0.0
        while (ghost.km < targetKm && t < 900) {
            ghost.update(0.25)
            t += 0.25
        }
        return t
    }

    override fun update(dt: Double) {
        val rem = course.km - km
        if (rem <= 0) return
        val sdt = dt * timeScale()
        val target = maxOf(min(course.speedAt(km, plan), brakeLimit()), 3.0)
        val step = ACCEL * 3.6 * sdt
        speedKmh = (speedKmh + (target - speedKmh).coerceIn(-step, step)).toFloat()
        km = min(course.km, km + speedKmh / 3600.0 * sdt)
        if (course.km - km < 0.002) {
            km = course.km
            speedKmh = 0f
        }
    }

    // 앞에 느린 구간이나 도착지가 있으면 미리 감속한다
    private fun brakeLimit(): Double {
        var limit = sqrt(2 * ACCEL * (course.km - km) * 1000) * 3.6
        var start = 0.0
        for (s in course.segments) {
            if (start > km && !(plan && s.jam)) {
                val v = s.kmh / 3.6
                limit = min(limit, sqrt(v * v + 2 * ACCEL * (start - km) * 1000) * 3.6)
            }
            start = s.untilKm
        }
        return limit
    }

    // 다음 장면(없으면 도착지)까지 남은 거리에 따라 배속을 서서히 줄인다.
    // 급하게 바꾸면 음악 쪽 도착 예상 시간이 튄다
    fun timeScale(): Double {
        val next = course.scenes.firstOrNull { it.km > km }
        val rem = (next?.km ?: course.km) - km
        val zone = if (next != null) SLOW_ZONE_KM else 0.3
        return (1 + (rem - zone) / 0.25).coerceIn(1.0, warp)
    }

    companion object {
        const val ACCEL = 2.5 // m/s²
        const val SLOW_ZONE_KM = 1.0
    }
}
