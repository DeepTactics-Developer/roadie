package com.deeptactics.roadie

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// 지도 화면의 배경(바다, 호수와 강, 녹지, 큰 도로). tools/basemap.py가 지역별로 만든 assets/roads/<지역>.map.json을 읽는다.
// 좌표는 [lat, lon, lat, lon, ...]. 그릴 때 화면 밖은 건너뛰도록 범위를 같이 둔다
class Shape(val pts: DoubleArray) {
    val minLat: Double
    val maxLat: Double
    val minLon: Double
    val maxLon: Double

    init {
        var a = Double.MAX_VALUE
        var b = -Double.MAX_VALUE
        var c = Double.MAX_VALUE
        var d = -Double.MAX_VALUE
        for (i in pts.indices step 2) {
            a = minOf(a, pts[i]); b = maxOf(b, pts[i])
            c = minOf(c, pts[i + 1]); d = maxOf(d, pts[i + 1])
        }
        minLat = a; maxLat = b; minLon = c; maxLon = d
    }

    fun overlaps(s: Double, w: Double, n: Double, e: Double) = maxLat >= s && minLat <= n && maxLon >= w && minLon <= e
}

// 면은 바깥 고리와 구멍들
class Area(val rings: List<Shape>)

class BaseMap(val sea: List<Area>, val water: List<Area>, val green: List<Area>, val river: List<Shape>, val roads: List<Pair<Int, Shape>>) {
    companion object {
        @Volatile var current: BaseMap? = null
            private set
        private var loading = ""

        // 지도 파일이 크니(수도권 1.6MB) 백그라운드에서 읽는다. 같은 지역이면 다시 읽지 않는다
        fun load(context: Context, lat: Double, lon: Double) {
            val idx = JSONArray(context.assets.open("roads/index.json").bufferedReader().use { it.readText() })
            val ids = (0 until idx.length()).map { idx.getJSONObject(it) }.filter { r ->
                val b = r.getJSONArray("bbox")
                lat in b.getDouble(0)..b.getDouble(2) && lon in b.getDouble(1)..b.getDouble(3)
            }.map { it.getString("id") }
            val key = ids.joinToString()
            if (key == loading) return
            loading = key
            val app = context.applicationContext
            Thread {
                val t0 = System.currentTimeMillis()
                val parts = ids.mapNotNull { id -> runCatching { parse(app.assets.open("roads/$id.map.json").bufferedReader().use { it.readText() }) }.getOrNull() }
                current = BaseMap(parts.flatMap { it.sea }, parts.flatMap { it.water }, parts.flatMap { it.green }, parts.flatMap { it.river }, parts.flatMap { it.roads })
                Log.d(MainActivity.TAG, "배경 지도 $key ${System.currentTimeMillis() - t0}ms")
            }.start()
        }

        fun parse(text: String): BaseMap {
            val o = JSONObject(text)
            fun areas(k: String) = o.getJSONArray(k).let { a ->
                (0 until a.length()).map { i -> a.getJSONArray(i).let { r -> Area((0 until r.length()).map { j -> Shape(Polyline.decode(r.getString(j))) }) } }
            }
            val river = o.getJSONArray("river").let { a -> (0 until a.length()).map { Shape(Polyline.decode(a.getString(it))) } }
            val roads = o.getJSONArray("roads").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { r -> r.getInt(0) to Shape(Polyline.decode(r.getString(1))) } } }
            return BaseMap(areas("sea"), areas("water"), areas("green"), river, roads)
        }
    }
}
