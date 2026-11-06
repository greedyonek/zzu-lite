package com.zzu.lite.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CampusDto(
    val id: Int,
    val nameZh: String? = null,
    val code: String? = null
)

@Serializable
data class BuildingDto(
    val id: Int,
    val nameZh: String? = null,
    val code: String? = null,
    val campusAssoc: Int? = null
)

/** 节次定义：startTime/endTime 为 HHMM 整数（如 800、1145） */
@Serializable
data class CourseUnitDto(
    val indexNo: Int,
    val nameZh: String? = null,
    val startTime: Int? = null,
    val endTime: Int? = null,
    val dayPart: String? = null
)

@Serializable
data class RoomOccupationVm(
    val roomName: String? = null,
    val date: String? = null,
    val startTime: Int? = null,
    val endTime: Int? = null,
    val startTimeString: String? = null,
    val endTimeString: String? = null,
    val activityName: String? = null,
    val teacherName: String? = null
)

@Serializable
data class ClassroomDto(
    val id: Int,
    val nameZh: String? = null,
    val code: String? = null,
    val buildingId: Int? = null,
    val campusNameZh: String? = null,
    val floor: Int? = null,
    val seatsForLesson: Int? = null,
    val enabled: Boolean? = null,
    val virtual: Boolean? = null,
    val roomOccupationInfoVms: List<RoomOccupationVm>? = null
)

@Serializable
data class RoomsPageDto(
    val data: List<ClassroomDto> = emptyList(),
    val total: Int? = null
)

@Serializable
data class RoomsQueryBody(
    val date: String,
    val currentPage: Int = 1,
    val pageSize: Int = 2000
)

