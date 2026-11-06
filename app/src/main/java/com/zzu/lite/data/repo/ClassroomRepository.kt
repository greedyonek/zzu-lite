package com.zzu.lite.data.repo

import com.zzu.lite.data.remote.EasService
import com.zzu.lite.data.remote.dto.BuildingDto
import com.zzu.lite.data.remote.dto.CampusDto
import com.zzu.lite.data.remote.dto.CourseUnitDto
import com.zzu.lite.data.remote.dto.ClassroomDto
import com.zzu.lite.data.remote.dto.RoomsQueryBody
import com.zzu.lite.domain.DataNotFoundException
import com.zzu.lite.domain.NetworkException
import com.zzu.lite.domain.OperationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 空教室仓库：room place 端点族（supwisdom eams-micro-server，已实测）。
 * 教室列表一次拉全量（约 1600+），占用随日期返回，客户端按楼栋/节次过滤。
 */
class ClassroomRepository(private val eas: EasService) {

    data class AvailableRoom(
        val room: ClassroomDto,
        val buildingName: String?
    )

    data class RoomQueryResult(
        val date: String,
        val totalFetched: Int,
        val available: List<AvailableRoom>
    )

    private val mutex = Mutex()

    private var campusCache: List<CampusDto>? = null
    private val buildingCache = mutableMapOf<Int, List<BuildingDto>>()
    private var unitCache: List<CourseUnitDto>? = null
    private val roomCache = mutableMapOf<String, List<ClassroomDto>>()

    suspend fun campuses(): List<CampusDto> = mutex.withLock {
        campusCache ?: fetchCampuses().also { campusCache = it }
    }

    suspend fun buildings(campusId: Int): List<BuildingDto> = mutex.withLock {
        buildingCache.getOrPut(campusId) { fetchBuildings(campusId) }
    }

    suspend fun courseUnits(): List<CourseUnitDto> = mutex.withLock {
        unitCache ?: fetchUnits().also { unitCache = it }
    }

    /**
     * 查询指定日期、指定节次（可多个）都空闲的教室。
     * @param campusId 校区过滤（按楼栋所属校区）
     * @param buildingIds 楼栋过滤；null/空 表示该校区全部楼栋（支持"北核"等多楼栋分组）
     */
    suspend fun availableRooms(
        date: String,
        campusId: Int?,
        buildingIds: Set<Int>?,
        unitNumbers: Set<Int>
    ): RoomQueryResult {
        require(unitNumbers.isNotEmpty()) { "请至少选择一个节次" }
        val units = courseUnits()
        val selected = units.filter { it.indexNo in unitNumbers && it.startTime != null && it.endTime != null }
        if (selected.isEmpty()) throw OperationException("节次时间未定义，无法判断空闲")

        val allRooms = roomsOf(date)
        val buildingNames = if (campusId != null) {
            buildings(campusId).associateBy { it.id }
        } else emptyMap()

        val available = allRooms
            .asSequence()
            .filter { it.virtual != true }
            .filter { buildingIds.isNullOrEmpty() || it.buildingId in buildingIds }
            .filter { campusId == null || buildingNames.containsKey(it.buildingId) }
            .filter { room -> isFreeForUnits(room, selected) }
            .map { AvailableRoom(it, buildingNames[it.buildingId]?.nameZh) }
            .sortedWith(compareBy({ it.buildingName.orEmpty() }, { it.room.nameZh.orEmpty() }))
            .toList()

        return RoomQueryResult(date, allRooms.size, available)
    }

    /** 教室在所选节次内是否有课：任一占用与任一节次时间段重叠即视为占用 */
    private fun isFreeForUnits(room: ClassroomDto, selected: List<CourseUnitDto>): Boolean {
        val occupations = room.roomOccupationInfoVms.orEmpty()
        if (occupations.isEmpty()) return true
        return occupations.none { occ ->
            val s = occ.startTime ?: Int.MAX_VALUE
            val e = occ.endTime ?: Int.MIN_VALUE
            selected.any { u ->
                val us = u.startTime ?: return@any false
                val ue = u.endTime ?: return@any false
                s < ue && e > us
            }
        }
    }

    private suspend fun roomsOf(date: String): List<ClassroomDto> = mutex.withLock {
        roomCache.getOrPut(date) {
            val resp = runCatching { eas.rooms(RoomsQueryBody(date = date)) }
                .getOrElse { throw NetworkException("获取教室列表失败：${it.message}") }
            if (resp.result != 0) {
                throw OperationException("获取教室列表失败：${resp.msg ?: resp.message ?: "result=${resp.result}"}")
            }
            resp.data?.data ?: throw DataNotFoundException("教室列表为空")
        }
    }

    private suspend fun fetchCampuses(): List<CampusDto> {
        val resp = runCatching { eas.campuses() }
            .getOrElse { throw NetworkException("获取校区失败：${it.message}") }
        if (resp.result != 0) throw OperationException("获取校区失败：${resp.msg ?: resp.message}")
        return resp.data ?: throw DataNotFoundException("没有校区数据")
    }

    private suspend fun fetchBuildings(campusId: Int): List<BuildingDto> {
        val resp = runCatching { eas.buildings(campusId) }
            .getOrElse { throw NetworkException("获取楼栋失败：${it.message}") }
        if (resp.result != 0) throw OperationException("获取楼栋失败：${resp.msg ?: resp.message}")
        return resp.data.orEmpty()
    }

    private suspend fun fetchUnits(): List<CourseUnitDto> {
        val resp = runCatching { eas.courseUnits() }
            .getOrElse { throw NetworkException("获取节次失败：${it.message}") }
        if (resp.result != 0) throw OperationException("获取节次失败：${resp.msg ?: resp.message}")
        return resp.data ?: throw DataNotFoundException("没有节次数据")
    }
}

