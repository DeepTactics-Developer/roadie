package com.deeptactics.roadie

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 기기 안에만 저장하는 기록
class Records(context: Context) {
    private val p = context.getSharedPreferences("roadie", Context.MODE_PRIVATE)

    val km get() = p.getFloat("km", 0f)
    val count get() = p.getInt("count", 0)

    fun visited(courseId: String) = courseId in p.getStringSet("courses", emptySet())!!

    fun add(km: Double, courseId: String, scenes: List<String>) {
        val e = p.edit()
            .putFloat("km", this.km + km.toFloat())
            .putInt("count", count + 1)
            .putStringSet("courses", p.getStringSet("courses", emptySet())!! + courseId)
            .putStringSet("scenes", p.getStringSet("scenes", emptySet())!! + scenes)
        if (!p.contains("first")) e.putString("first", SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date()))
        e.apply()
    }
}
