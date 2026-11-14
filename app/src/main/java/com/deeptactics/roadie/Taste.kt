package com.deeptactics.roadie

import kotlin.math.abs

// 한 번 달리는 동안 모은 차량 데이터
class DriveStats {
    var total = 0.0 // 초
    var flowing = 0.0 // 30km/h 이상으로 일정하게 흐른 시간
    var jam = 0.0 // 10km/h 아래로 기어간 시간
    var lastSpeed = 0f
    val picks = mutableListOf<String>() // 갈림길에서 고른 길 성격

    fun add(dt: Double, speed: Float) {
        total += dt
        if (speed >= 30 && abs(speed - lastSpeed) < 3) flowing += dt
        if (speed in 0.5f..10f) jam += dt
        lastSpeed = speed
    }

    // 리듬 점수 0~1. 끊김 없이 흐른 비율
    val rhythm get() = if (total <= 0) 0.0 else (flowing / total).coerceIn(0.0, 1.0)
}

// 도로 테이스팅 노트와 취향 학습
object Taste {
    fun note(c: Course, upToKm: Double, st: DriveStats): String {
        val legs = c.legs.filter { it.fromKm < upToKm }
        if (legs.isEmpty()) return "아직 맛볼 만큼 달리지 못했어."
        val out = mutableListOf<String>()
        legs.maxByOrNull { it.edge.curves }?.takeIf { it.edge.curves >= 3 }?.let {
            out += "${phase(it, c)} ${it.edge.road}에서 굽이 ${it.edge.curves}연속"
        }
        legs.maxByOrNull { it.climb }?.takeIf { it.climb >= 100 }?.let { l ->
            val view = c.scenes.any { it.km in l.fromKm..l.toKm && it.kind in setOf(SceneKind.PASS, SceneKind.VIEW) }
            out += "${phase(l, c)} 오르막 ${l.climb}m" + if (view) " 끝에 시야가 탁 트임" else ""
        }
        legs.minByOrNull { it.climb }?.takeIf { it.climb <= -300 }?.let { out += "${phase(it, c)} ${-it.climb}m 긴 내리막" }
        c.scenes.firstOrNull { it.kind == SceneKind.TUNNEL_EXIT && it.km <= upToKm }?.let {
            out += "터널 ${"%.1f".format(it.km - (it.tunnelFrom ?: it.km))}km 빠져나오자 ${it.name.substringAfter(", ")}"
        }
        if (st.jam > 20) out += "정체 ${(st.jam / 60).toInt().coerceAtLeast(1)}분, 음악이 기다려 줌"
        val seaKm = legs.filter { Tag.SEA in it.edge.tags }.sumOf { it.edge.km }
        if (seaKm >= 5) out += "바다 곁으로 ${seaKm.toInt()}km"
        val signals = legs.sumOf { it.edge.signals }
        val per10 = signals / legs.sumOf { it.edge.km } * 10
        out += when {
            per10 < 1.5 -> "신호 ${signals}개뿐이라 리듬이 안 끊김"
            per10 > 4 -> "시내 신호가 잦은 편"
            else -> "신호 ${signals}개, 무난한 흐름"
        }
        return out.take(3).joinToString(", ")
    }

    private fun phase(l: Leg, c: Course) = when ((l.fromKm + l.toKm) / 2 / c.km) {
        in 0.0..0.34 -> "초반"
        in 0.34..0.67 -> "중반"
        else -> "후반"
    }

    // 리듬 있게 달린 길의 성격에 점수를 준다. 갈림길에서 직접 고른 쪽은 더
    fun learn(old: Map<Tag, Double>, c: Course, st: DriveStats): Map<Tag, Double> {
        val enjoy = 0.4 + 0.6 * st.rhythm
        return Tag.entries.associateWith { t ->
            val pick = st.picks.count { it == t.word } * 0.3
            (old[t] ?: 0.0) * 0.85 + c.share(t) * enjoy + pick
        }
    }

    // 홈에 미리 띄울 추천. 취향이 가장 높은 쪽으로, 평소 달린 시간만큼
    fun suggest(taste: Map<Tag, Double>, drives: List<Drive>): Wish {
        val top = taste.filterKeys { it != Tag.CITY }.maxByOrNull { it.value }?.takeIf { it.value > 0.2 }?.key ?: Tag.SEA
        val min = drives.take(5).map { it.minutes }.average().takeIf { !it.isNaN() }?.toInt()?.coerceIn(20, 90) ?: 40
        return Wish(minutes = min / 5 * 5, tags = setOf(top), fewSignals = true)
    }

    fun favorite(taste: Map<Tag, Double>) = taste.filterKeys { it != Tag.CITY }.maxByOrNull { it.value }?.takeIf { it.value > 0.5 }?.key
}
