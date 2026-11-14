package com.deeptactics.roadie

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// 연결 구간(지금 위치와 첫 지점, 마지막 지점과 지금 위치 사이)은 앱 지도에 길이 없어서 곧은 선으로밖에 못 그린다.
// 그림만이라도 실제 길을 따라가게 OSRM 공개 서버에 도로 모양을 물어본다. 인터넷이 없거나 실패하면 곧은 점선 그대로 둔다.
// ponytail: 공개 시연 서버라 호출 제한이 있다. 서비스로 내려면 자체 경로 서버로 바꿀 것
object Connectors {
    private val cache = ConcurrentHashMap<String, DoubleArray>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    private fun key(a: Node, b: Node) = String.format(Locale.US, "%.5f,%.5f>%.5f,%.5f", a.lat, a.lon, b.lat, b.lon)

    // 모양이 없는 연결 구간을 채운다. 하나라도 채우면 done을 부른다(백그라운드 스레드에서)
    fun ensure(c: Course, done: () -> Unit) {
        val need = c.legs.map { it.edge }.filter { it.connector && it.shape == null }
        if (need.isEmpty()) return
        for (e in need) cache[key(e.a, e.b)]?.let { e.shape = it }
        val left = need.filter { it.shape == null && asked.add(key(it.a, it.b)) }
        if (left.isEmpty()) {
            if (need.any { it.shape != null }) done()
            return
        }
        Thread {
            for (e in left) fetch(e.a, e.b)?.let {
                cache[key(e.a, e.b)] = it
                e.shape = it
            }
            done()
        }.start()
    }

    private fun fetch(a: Node, b: Node): DoubleArray? = runCatching {
        val url = URL(String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline", a.lon, a.lat, b.lon, b.lat))
        val text = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 4000
            readTimeout = 6000
            setRequestProperty("User-Agent", "roadie-prototype")
            inputStream.bufferedReader().use { it.readText() }
        }
        Polyline.decode(JSONObject(text).getJSONArray("routes").getJSONObject(0).getString("geometry"))
    }.onFailure { Log.w(MainActivity.TAG, "연결 구간 모양 못 받음: ${it.message}") }.getOrNull()
}
