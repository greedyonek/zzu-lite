package com.zzu.lite.data.remote.dto

import kotlinx.serialization.Serializable

/** 教务系统通用响应壳：{result, msg/message, data}，result==0 成功 */
@Serializable
data class EasEnvelope<T>(
    val result: Int = -1,
    val msg: String? = null,
    val message: String? = null,
    val data: T? = null
)

@Serializable
data class SemesterDto(
    val id: Int,
    val code: String? = null,
    val nameZh: String? = null,
    val nameEn: String? = null,
    val schoolYear: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val weekStartOnSunday: Boolean? = null,
    val season: String? = null,
    val weekIndices: List<Int> = emptyList()
)

@Serializable
data class NamedRefDto(
    val id: Int? = null,
    val nameZh: String? = null,
    val nameEn: String? = null,
    val code: String? = null
)

@Serializable
data class CourseDto(
    val id: Int? = null,
    val code: String? = null,
    val nameZh: String? = null,
    val nameEn: String? = null,
    val credits: Double? = null
)

@Serializable
data class RoomDto(
    val nameZh: String? = null,
    val building: NamedRefDto? = null,
    val campus: NamedRefDto? = null
)

@Serializable
data class TextPairDto(
    val textZh: String? = null,
    val text: String? = null
)

@Serializable
data class ScheduleTextDto(
    val dateTimeText: TextPairDto? = null,
    val dateTimePlaceText: TextPairDto? = null,
    val dateTimePlacePersonText: TextPairDto? = null
)

@Serializable
data class ScheduleDto(
    val scheduleGroupId: Int? = null,
    val date: String? = null,
    val weekday: Int = 0,
    val startTime: String? = null,
    val endTime: String? = null,
    val realStartTime: String? = null,
    val realEndTime: String? = null,
    val teacherName: String? = null,
    val customPlace: String? = null,
    val room: RoomDto? = null,
    val startUnit: Int = 0,
    val endUnit: Int = 0,
    val state: String? = null,
    val weekIndex: Int = 0,
    val lessonType: String? = null,
    val periods: Int? = null
)

@Serializable
data class LessonDatumDto(
    val id: Int? = null,
    val code: String? = null,
    val course: CourseDto? = null,
    val teacherAssignmentList: List<String> = emptyList(),
    val scheduleText: ScheduleTextDto? = null,
    val schedules: List<ScheduleDto> = emptyList()
)
