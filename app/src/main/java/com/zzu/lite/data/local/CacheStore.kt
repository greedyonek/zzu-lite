package com.zzu.lite.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "zzu_lite_cache")

/**
 * 课表/学期/个人信息缓存：以 JSON 文本存于 DataStore，支持离线查看。
 */
class CacheStore(private val context: Context, private val json: kotlinx.serialization.json.Json) : RoomStore {

    private object Keys {
        val SEMESTERS = stringPreferencesKey("semesters_json")
        val CURRENT_SEMESTER = stringPreferencesKey("current_semester_json")
        val COURSE_TABLE = stringPreferencesKey("course_table_json")
        val COURSE_TABLE_SEMESTER_ID = stringPreferencesKey("course_table_semester_id")
        val SAVED_SEMESTER_ID = stringPreferencesKey("saved_semester_id")
        val SAVED_ROOM = stringPreferencesKey("saved_room")
        val UPDATED_AT = longPreferencesKey("timetable_updated_at")
        val ENERGY = stringPreferencesKey("energy_cache_json")
        val ALERT_ENABLED = booleanPreferencesKey("energy_alert_enabled")
        val LAST_ALERT_VALUE = stringPreferencesKey("energy_last_alert_value")
        val LAST_ALERT_KIND = stringPreferencesKey("energy_last_alert_kind")
        val LAST_ALERT_AT = longPreferencesKey("energy_last_alert_at")
    }

    suspend fun saveTimetable(
        semestersJson: String,
        currentSemesterJson: String,
        courseTableJson: String,
        semesterId: Int
    ) {
        context.dataStore.edit { p ->
            p[Keys.SEMESTERS] = semestersJson
            p[Keys.CURRENT_SEMESTER] = currentSemesterJson
            p[Keys.COURSE_TABLE] = courseTableJson
            p[Keys.COURSE_TABLE_SEMESTER_ID] = semesterId.toString()
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    data class TimetableCache(
        val semestersJson: String,
        val currentSemesterJson: String,
        val courseTableJson: String,
        val semesterId: Int,
        val updatedAt: Long
    )

    suspend fun readTimetable(): TimetableCache? {
        val p = context.dataStore.data.first()
        val sem = p[Keys.SEMESTERS] ?: return null
        val cur = p[Keys.CURRENT_SEMESTER] ?: return null
        val table = p[Keys.COURSE_TABLE] ?: return null
        val id = p[Keys.COURSE_TABLE_SEMESTER_ID]?.toIntOrNull() ?: return null
        return TimetableCache(sem, cur, table, id, p[Keys.UPDATED_AT] ?: 0L)
    }

    /** 用户手动选择的学期（重进应用保持） */
    suspend fun savedSemesterId(): Int? = context.dataStore.data.first()[Keys.SAVED_SEMESTER_ID]?.toIntOrNull()

    suspend fun saveSemesterId(id: Int) {
        context.dataStore.edit { it[Keys.SAVED_SEMESTER_ID] = id.toString() }
    }

    /** 电费房间绑定 */
    override suspend fun savedRoom(): String? = context.dataStore.data.first()[Keys.SAVED_ROOM]

    override suspend fun saveRoom(room: String?) {
        context.dataStore.edit { p ->
            if (room.isNullOrBlank()) p -= Keys.SAVED_ROOM else p[Keys.SAVED_ROOM] = room
        }
    }

    suspend fun updatedAt(): Long = context.dataStore.data.first()[Keys.UPDATED_AT] ?: 0L

    /** 最近一次电费查询结果（首页离线展示用） */
    suspend fun saveEnergy(energy: com.zzu.lite.data.repo.RoomEnergy) {
        runCatching {
            context.dataStore.edit {
                it[Keys.ENERGY] = json.encodeToString(com.zzu.lite.data.repo.RoomEnergy.serializer(), energy)
            }
        }
    }

    suspend fun loadEnergy(): com.zzu.lite.data.repo.RoomEnergy? =
        context.dataStore.data.first()[Keys.ENERGY]?.let { s ->
            runCatching { json.decodeFromString(com.zzu.lite.data.repo.RoomEnergy.serializer(), s) }.getOrNull()
        }

    suspend fun clearEnergy() {
        context.dataStore.edit { it -= Keys.ENERGY }
    }

    /** 电费不足提醒开关（默认开） */
    suspend fun energyAlertEnabled(): Boolean = context.dataStore.data.first()[Keys.ALERT_ENABLED] ?: true

    suspend fun setEnergyAlertEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALERT_ENABLED] = enabled }
    }

    /** 上次提醒时的指标值/类型（用于判断"跨过阈值"）与时间戳 */
    suspend fun lastAlertValue(): Double? =
        context.dataStore.data.first()[Keys.LAST_ALERT_VALUE]?.toDoubleOrNull()

    suspend fun lastAlertKind(): String? = context.dataStore.data.first()[Keys.LAST_ALERT_KIND]

    suspend fun lastAlertAt(): Long = context.dataStore.data.first()[Keys.LAST_ALERT_AT] ?: 0L

    suspend fun saveAlertState(value: Double?, kind: String?, at: Long) {
        context.dataStore.edit { p ->
            if (value == null) p -= Keys.LAST_ALERT_VALUE else p[Keys.LAST_ALERT_VALUE] = value.toString()
            if (kind == null) p -= Keys.LAST_ALERT_KIND else p[Keys.LAST_ALERT_KIND] = kind
            p[Keys.LAST_ALERT_AT] = at
        }
    }

    suspend fun clearTimetable() {
        context.dataStore.edit { p ->
            p -= Keys.COURSE_TABLE
            p -= Keys.SEMESTERS
            p -= Keys.UPDATED_AT
        }
    }
}
