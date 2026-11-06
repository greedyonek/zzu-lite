package com.zzu.lite.data.repo

import com.zzu.lite.data.local.CacheStore
import com.zzu.lite.data.remote.EasService
import com.zzu.lite.data.remote.InfoService
import com.zzu.lite.data.remote.dto.EasEnvelope
import com.zzu.lite.data.remote.dto.LessonDatumDto
import com.zzu.lite.data.remote.dto.SemesterDto
import com.zzu.lite.domain.DataNotFoundException
import com.zzu.lite.domain.NetworkException
import com.zzu.lite.domain.OperationException
import com.zzu.lite.domain.ParsingException
import com.zzu.lite.domain.model.TeachingWeek
import com.zzu.lite.domain.model.TeachingWeeks
import com.zzu.lite.domain.util.FriendlyNetwork
import com.zzu.lite.domain.util.TimeUtil
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 课表仓库：学期列表 + 课表 + 教学周网格，含离线缓存。
 */
class ScheduleRepository(
    private val eas: EasService,
    private val info: InfoService,
    private val cache: CacheStore,
    private val json: Json
) {

    data class TimetableData(
        val semesters: List<SemesterDto>,
        val semester: SemesterDto,
        val weeks: TeachingWeeks,
        val fromCache: Boolean = false,
        val updatedAt: Long = 0L
    )

    /** 课表缓存更新后的回调（用于刷新桌面小组件等） */
    var onTimetableChanged: (() -> Unit)? = null

    /** 拉取（或读缓存）某学期的完整课表；semesterId 为空时用当前学期 */
    suspend fun load(forceRefresh: Boolean = false, semesterId: Int? = null): TimetableData {
        if (!forceRefresh) {
            readCache(semesterId)?.let { cached ->
                return cached
            }
        }
        return fetch(semesterId)
    }

    private suspend fun fetch(semesterId: Int?): TimetableData {
        val semesters = unwrapEas(
            runCatching { eas.allSemesters() }.getOrElse { throw FriendlyNetwork.wrap(it, "获取学期列表") },
            "获取学期列表"
        )
        if (semesters.isEmpty()) throw DataNotFoundException("没有学期数据")

        val semester = if (semesterId != null) {
            semesters.firstOrNull { it.id == semesterId } ?: throw DataNotFoundException("学期不存在：$semesterId")
        } else {
            val current = unwrapEas(
                runCatching { eas.currentSemester() }.getOrElse { throw FriendlyNetwork.wrap(it, "获取当前学期") },
                "获取当前学期"
            )
            // 服务端偶发返回历史学期：与学期列表比对，取列表中最新的匹配项
            semesters.firstOrNull { it.id == current.id } ?: current
        }

        val table = unwrapEas(
            runCatching { eas.courseTable(semester.id) }.getOrElse { throw FriendlyNetwork.wrap(it, "获取课表") },
            "获取课表"
        )

        val weeks = TeachingWeeks.build(semester.weekIndices, table)
        val data = TimetableData(semesters, semester, weeks)

        runCatching {
            cache.saveTimetable(
                semestersJson = json.encodeToString(ListSerializer(SemesterDto.serializer()), semesters),
                currentSemesterJson = json.encodeToString(SemesterDto.serializer(), semester),
                courseTableJson = json.encodeToString(ListSerializer(LessonDatumDto.serializer()), table),
                semesterId = semester.id
            )
            onTimetableChanged?.invoke()
        }
        return data
    }

    private suspend fun readCache(semesterId: Int?): TimetableData? {
        val c = runCatching { cache.readTimetable() }.getOrNull() ?: return null
        val semesters = runCatching {
            json.decodeFromString(ListSerializer(SemesterDto.serializer()), c.semestersJson)
        }.getOrNull() ?: return null
        val semester = runCatching {
            json.decodeFromString(SemesterDto.serializer(), c.currentSemesterJson)
        }.getOrNull() ?: return null
        if (semesterId != null && semester.id != semesterId) {
            // 缓存中无该学期课表，需联网
            return null
        }
        val table = runCatching {
            json.decodeFromString(ListSerializer(LessonDatumDto.serializer()), c.courseTableJson)
        }.getOrNull() ?: return null
        val weeks = TeachingWeeks.build(semester.weekIndices, table)
        return TimetableData(semesters, semester, weeks, fromCache = true, updatedAt = c.updatedAt)
    }

    /**
     * 今天是第几教学周：优先 portal-api 接口，失败时按学期起始日推算。
     */
    suspend fun currentWeekIndex(semester: SemesterDto): Int? {
        runCatching { info.weekOfTeaching(TimeUtil.today()) }.getOrNull()?.let { resp ->
            if (resp.code == 0) {
                val inner = resp.data
                val raw = inner?.data?.date?.firstOrNull()
                val parsed = raw?.trim()?.toIntOrNull()
                if (parsed != null && parsed > 0) return parsed
            }
        }
        return TimeUtil.weekIndexOf(TimeUtil.parseDate(semester.startDate), semester.weekIndices.size)
    }

    private fun <T> unwrapEas(envelope: EasEnvelope<T>, what: String): T {
        if (envelope.result != 0) {
            throw OperationException("${what}失败：${envelope.msg ?: envelope.message ?: "result=${envelope.result}"}")
        }
        return envelope.data ?: throw ParsingException("${what}响应缺少数据")
    }
}
