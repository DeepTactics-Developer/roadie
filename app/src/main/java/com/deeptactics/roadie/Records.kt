package com.deeptactics.roadie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 지난 드라이브 한 번
class Drive(val date: String, val name: String, val km: Double, val minutes: Int, val scenes: List<String>, val note: String)

// 기기 안에만 저장하는 기록
class Records(context: Context) {
    private val p = context.getSharedPreferences("roadie", Context.MODE_PRIVATE)

    val km get() = p.getFloat("km", 0f)
    val count get() = p.getInt("count", 0)
    val first get() = p.getString("first", null)

    fun visited(courseId: String) = courseId in p.getStringSet("courses", emptySet())!!

    // 최근 것이 앞
    fun drives(): List<Drive> {
        val a = JSONArray(p.getString("log", "[]"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val s = o.getJSONArray("scenes")
            Drive(o.getString("date"), o.getString("name"), o.getDouble("km"), o.getInt("min"), (0 until s.length()).map { s.getString(it) }, o.optString("note"))
        }.reversed()
    }

    fun add(courseId: String, d: Drive) {
        val log = JSONArray(p.getString("log", "[]")).put(
            JSONObject().put("date", d.date).put("name", d.name).put("km", d.km).put("min", d.minutes)
                .put("scenes", JSONArray(d.scenes)).put("note", d.note)
        )
        val e = p.edit()
            .putFloat("km", km + d.km.toFloat())
            .putInt("count", count + 1)
            .putStringSet("courses", p.getStringSet("courses", emptySet())!! + courseId)
            .putString("log", log.toString())
        if (!p.contains("first")) e.putString("first", d.date.take(10))
        e.apply()
    }

    fun taste(): Map<Tag, Double> = Tag.entries.associateWith { p.getFloat("taste_${it.name}", 0f).toDouble() }

    fun saveTaste(t: Map<Tag, Double>) {
        val e = p.edit()
        for ((k, v) in t) e.putFloat("taste_${k.name}", v.toFloat())
        e.apply()
    }

    companion object {
        fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
    }
}
