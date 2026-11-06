package com.zzu.lite.domain.model

import kotlinx.serialization.Serializable

/**
 * 展示用课程条目：由 LessonDatumDto + ScheduleDto 合成。
 */
@Serializable
data class CourseLesson(
    val courseName: String,
    val courseCode: String? = null,
    val teacher: String? = null,
    val place: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val startUnit: Int = 0,
    val endUnit: Int = 0,
    val weekday: Int = 0,
    val weekIndex: Int = 0,
    val lessonType: String? = null,
    val state: String? = null,
    val date: String? = null,
    val credits: Double? = null
)

/**
 * 某个教学周的课表网格：7 天 × unitCount 节，对齐 ZZU.Py TeachingWeek。
 * weekday/unit 均从 1 开始。
 */
class TeachingWeek(val weekIndex: Int, val unitCount: Int = 10) {

    private val grid: Array<Array<CourseLesson?>> = Array(7) { arrayOfNulls(unitCount) }

    /** 1-based 访问；越界返回 null */
    operator fun get(weekday: Int, unit: Int): CourseLesson? {
        if (weekday !in 1..7 || unit !in 1..unitCount) return null
        return grid[weekday - 1][unit - 1]
    }

    /** 某天的全部课程（按开始节次排序，去重），用于“今日”时间线 */
    fun dayLessons(weekday: Int): List<CourseLesson> {
        if (weekday !in 1..7) return emptyList()
        return grid[weekday - 1].filterNotNull().distinct().sortedBy { it.startUnit }
    }

    internal fun place(weekday: Int, unit: Int, lesson: CourseLesson) {
        if (weekday in 1..7 && unit in 1..unitCount) {
            grid[weekday - 1][unit - 1] = lesson
        }
    }
}

/**
 * 一学期的全部教学周。索引从 0（第 1 周）。
 */
class TeachingWeeks(val weeks: List<TeachingWeek>) {

    val totalWeeks: Int get() = weeks.size

    fun week(weekIndex: Int): TeachingWeek? = weeks.getOrNull(weekIndex - 1)

    companion object {
        /**
         * 复刻 ZZU.Py 的课表展开逻辑：
         * 对 weekIndices 中每个周建立 7×N 网格；遍历 schedules，
         * 取 weekIndex 匹配的记录填充 startUnit..endUnit 每一节。
         */
        fun build(
            weekIndices: List<Int>,
            data: List<com.zzu.lite.data.remote.dto.LessonDatumDto>
        ): TeachingWeeks {
            val unitCount = (data.flatMap { it.schedules }.maxOfOrNull { it.endUnit } ?: 10)
                .coerceAtLeast(10)
            val weeks = weekIndices.map { w -> TeachingWeek(w, unitCount) }
            val byWeek = weeks.associateBy { it.weekIndex }
            val placedKeys = mutableSetOf<Pair<Int, Triple<Int, Int, String>>>()

            for (datum in data) {
                val courseName = datum.course?.nameZh.orEmpty().ifBlank { datum.course?.nameEn.orEmpty() }
                val teachers = datum.teacherAssignmentList.filterNotNull().joinToString("、")
                for (schedule in datum.schedules) {
                    val week = byWeek[schedule.weekIndex] ?: continue
                    val room = schedule.room
                    val place = schedule.customPlace ?: listOfNotNull(
                        room?.campus?.nameZh,
                        room?.building?.nameZh,
                        room?.nameZh
                    ).joinToString(" ").ifBlank { null }
                    val lesson = CourseLesson(
                        courseName = courseName,
                        courseCode = datum.course?.code,
                        teacher = schedule.teacherName ?: teachers.ifBlank { null },
                        place = place,
                        startTime = schedule.realStartTime ?: schedule.startTime,
                        endTime = schedule.realEndTime ?: schedule.endTime,
                        startUnit = schedule.startUnit,
                        endUnit = schedule.endUnit,
                        weekday = schedule.weekday,
                        weekIndex = schedule.weekIndex,
                        lessonType = schedule.lessonType,
                        state = schedule.state,
                        date = schedule.date,
                        credits = datum.course?.credits
                    )
                    // 服务端偶发合班重复记录：同一时段同课程只保留一条
                    val key = Triple(schedule.weekday, schedule.startUnit, courseName)
                    if (!placedKeys.add(week.weekIndex to key)) continue
                    for (unit in schedule.startUnit..schedule.endUnit) {
                        week.place(schedule.weekday, unit, lesson)
                    }
                }
            }
            return TeachingWeeks(weeks)
        }
    }
}
