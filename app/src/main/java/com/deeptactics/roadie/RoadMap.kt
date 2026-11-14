package com.deeptactics.roadie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// 도로 조각 형식과 읽기. 값은 assets/roads/<지역>.json에 있고 tools/build_roads.py가 실제 지도 데이터로 재서 만든다
enum class Tag(val word: String) { SEA("바다"), LAKE("호숫가"), RIVER("강변"), MOUNTAIN("고갯길"), CITY("시내") }

enum class SceneKind { TUNNEL_EXIT, SEA, PASS, BRIDGE, LAKE, RIVER, VIEW }

class Node(val id: String, val name: String, val lat: Double, val lon: Double) {
    // 두 지점 사이 직선거리(km)
    fun km(o: Node): Double {
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(o.lat)
        val dl = Math.toRadians(o.lon - lon)
        val dp = p2 - p1
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 6371.0 * 2 * asin(sqrt(h))
    }
}

// at: 조각 시작(a)에서의 거리. 터널 출구면 at이 출구, len이 터널 길이. oneWay는 a→b 방향일 때만, reverseOnly는 b→a 방향일 때만 보인다.
// 터널이 아닌 장면은 a→b일 땐 at이 구간 시작, b→a일 땐 reverseOnly 쪽 at이 구간 끝(거꾸로 가면 그게 시작)
class Feature(
    val at: Double,
    val kind: SceneKind,
    val name: String,
    val line: String,
    val len: Double = 0.0,
    val oneWay: Boolean = false,
    val reverseOnly: Boolean = false,
)

class Edge(
    val a: Node,
    val b: Node,
    val road: String,
    val km: Double,
    val kmh: Double,
    val signals: Int,
    val curves: Int, // S자나 헤어핀 같은 굽이 수
    val climb: Int, // a→b로 갈 때 오르는 높이(m). 내려가면 음수
    val tags: Set<Tag>,
    val features: List<Feature> = emptyList(),
    val sea: Double = 0.0, // 길이 중 바다에 붙어 달리는 비율(잰 값)
    val lake: Double = 0.0, // 호숫가 비율
    val river: Double = 0.0, // 강변 비율
    val connector: Boolean = false, // 지금 위치와 지점을 잇는 구간. 지도 데이터가 없어 추정값이다
    @Volatile var shape: DoubleArray? = null, // a→b 도로 모양 [lat, lon, lat, lon, ...]. 연결 구간은 Connectors가 나중에 채운다
)

// 구글 폴리라인 형식(소수 5자리)을 푼다. tools/geo.py의 encode와 짝
object Polyline {
    fun decode(s: String): DoubleArray {
        val out = ArrayList<Double>()
        var i = 0
        var lat = 0
        var lon = 0
        fun next(): Int {
            var shift = 0
            var v = 0
            while (true) {
                val b = s[i++].code - 63
                v = v or ((b and 0x1f) shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (v and 1 != 0) (v shr 1).inv() else v shr 1
        }
        while (i < s.length) {
            lat += next()
            lon += next()
            out += lat / 1e5
            out += lon / 1e5
        }
        return out.toDoubleArray()
    }
}

class Graph(nodes: Collection<Node>, val edges: List<Edge>) {
    val nodes: Map<String, Node> = nodes.associateBy { it.id }
    val adj: Map<String, List<Pair<Edge, Boolean>>> = HashMap<String, MutableList<Pair<Edge, Boolean>>>().also { m ->
        for (e in edges) {
            m.getOrPut(e.a.id) { mutableListOf() } += e to false
            m.getOrPut(e.b.id) { mutableListOf() } += e to true
        }
    }

    // from에서 가까운 순서
    fun near(from: Node, radiusKm: Double) = nodes.values.map { it to it.km(from) }.filter { it.second <= radiusKm }.sortedBy { it.second }

    companion object {
        val EMPTY = Graph(emptyList(), emptyList())
    }
}

object Roads {
    private val cache = HashMap<String, Pair<List<Node>, List<Edge>>>()

    fun parse(text: String): Pair<List<Node>, List<Edge>> {
        val root = JSONObject(text)
        val nodes = root.getJSONArray("nodes").let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i).let { Node(it.getString(0), it.getString(1), it.getDouble(2), it.getDouble(3)) } }
        }
        val byId = nodes.associateBy { it.id }
        val ea = root.getJSONArray("edges")
        val edges = (0 until ea.length()).map { i ->
            val e = ea.getJSONObject(i)
            val tags = e.getJSONArray("tags").let { t -> (0 until t.length()).map { Tag.valueOf(t.getString(it)) }.toSet() }
            val fa = e.getJSONArray("f")
            val feats = (0 until fa.length()).map { j ->
                val f = fa.getJSONArray(j)
                val flag = f.getString(5)
                Feature(f.getDouble(0), SceneKind.valueOf(f.getString(1)), f.getString(2), f.getString(3), f.getDouble(4), flag == "f", flag == "r")
            }
            Edge(
                byId.getValue(e.getString("a")), byId.getValue(e.getString("b")), e.getString("road"), e.getDouble("km"), e.getDouble("kmh"),
                e.getInt("sig"), e.getInt("cur"), e.getInt("climb"), tags, feats, e.optDouble("sea", 0.0), e.optDouble("lake", 0.0), e.optDouble("river", 0.0),
                shape = e.optString("g").takeIf { it.isNotEmpty() }?.let { Polyline.decode(it) },
            )
        }
        return nodes to edges
    }

    // 지도 데이터를 지역별 파일로 나눠 둔다. 지금 위치가 걸치는 지역만 읽는다(전국으로 늘어도 필요한 것만 메모리에 올린다)
    fun load(context: Context, lat: Double, lon: Double) = merge(
        JSONArray(context.assets.open("roads/index.json").bufferedReader().use { it.readText() }).let { idx ->
            (0 until idx.length()).map { idx.getJSONObject(it) }
        }.filter { r ->
            val b = r.getJSONArray("bbox")
            lat in b.getDouble(0)..b.getDouble(2) && lon in b.getDouble(1)..b.getDouble(3)
        }.map { r ->
            val file = r.getString("file")
            cache.getOrPut(file) { parse(context.assets.open("roads/$file").bufferedReader().use { it.readText() }) }
        },
    )

    fun merge(parts: List<Pair<List<Node>, List<Edge>>>) = Graph(parts.flatMap { it.first }, parts.flatMap { it.second })
}
