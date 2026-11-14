package com.deeptactics.roadie

import android.content.Context
import org.json.JSONObject

// 길 위의 도슨트. 지나는 곳의 짧은 이야기를 장면 사이 빈 구간에 들려준다.
// 이야기는 공개 자료를 보고 미리 써 둔 것(assets/docent.json)이고 지점 이름으로 찾는다
object Docent {
    var stories: Map<String, String> = emptyMap()

    fun load(context: Context) {
        stories = runCatching { parse(context.assets.open("docent.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyMap())
    }

    fun parse(text: String): Map<String, String> = JSONObject(text).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }

    // 읽는 데 걸리는 초. 한국어 음성이 1초에 6자쯤 읽는다
    fun sec(text: String) = text.length / 6.0 + 2

    // 다음 일(장면 대사, 갈림길 질문, 도착)까지 남은 초. 지도 속도 기준
    fun gap(c: Course, km: Double, forkPending: Boolean): Double {
        val scene = c.scenes.firstOrNull { it.km > km }?.let { c.planMinutes(km, it.km) * 60 - Arranger.LINE_SEC }
        val fork = c.fork?.takeIf { forkPending && it.km > km }?.let { c.planMinutes(km, it.km) * 60 - Course.FORK_ASK_SEC }
        val end = c.planMinutes(km, c.km) * 60
        return listOfNotNull(scene, fork, end).min()
    }

    // 지금 꺼낼 이야기(이름, 내용). 지점을 1km 앞두고부터 2km 지날 때까지, 끝까지 말할 틈이 있을 때만
    fun pick(c: Course, km: Double, told: Set<String>, gapSec: Double): Pair<String, String>? =
        c.legs.asSequence()
            .filter { it.to.name in stories && it.to.name !in told && km in it.toKm - 1.0..it.toKm + 2.0 }
            .map { it.to.name to stories.getValue(it.to.name) }
            .firstOrNull { gapSec > sec(it.second) + 8 }
}
