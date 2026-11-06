package com.zzu.lite.domain.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object TimeUtil {

    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** "1010" -> "10:10"；空值返回 null。 */
    fun hhmm(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val v = raw.trim()
        return if (v.length == 4) "${v.substring(0, 2)}:${v.substring(2, 4)}" else v
    }

    /** 800 -> "08:00"（接口里节次时间是 HHMM 整数） */
    fun hhmm(raw: Int?): String? {
        if (raw == null || raw < 0) return null
        return hhmm("%04d".format(raw))
    }

    fun parseDate(raw: String?): LocalDate? = runCatching { LocalDate.parse(raw?.trim(), dateFmt) }.getOrNull()

    fun parseHhmm(raw: String?): LocalTime? {
        val s = hhmm(raw) ?: return null
        return runCatching { LocalTime.parse(s) }.getOrNull()
    }

    fun today(): String = LocalDate.now().format(dateFmt)

    /** 距离 [start] 日期第几周（1 起）；不在范围内返回 null。 */
    fun weekIndexOf(startDate: LocalDate?, totalWeeks: Int, today: LocalDate = LocalDate.now()): Int? {
        if (startDate == null || totalWeeks <= 0) return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, today)
        if (days < 0) return 1
        val week = (days / 7).toInt() + 1
        return week.coerceIn(1, totalWeeks)
    }

    /** 某教学周星期 [weekday]（1=周一）的日期。 */
    fun dateOfWeek(semesterStart: LocalDate?, week: Int, weekday: Int): LocalDate? {
        if (semesterStart == null) return null
        return semesterStart.plusDays(((week - 1) * 7 + (weekday - 1)).toLong())
    }
}
