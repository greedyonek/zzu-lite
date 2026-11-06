package com.zzu.lite.data.repo

import com.zzu.lite.data.local.RoomStore
import com.zzu.lite.data.remote.ECardService
import com.zzu.lite.data.remote.dto.ECardEnvelope
import com.zzu.lite.data.remote.dto.LocationQueryBody
import com.zzu.lite.data.remote.dto.TidBody
import com.zzu.lite.data.remote.dto.UtilityTypeBody
import com.zzu.lite.domain.InvalidArgumentException
import com.zzu.lite.domain.NetworkException
import com.zzu.lite.domain.OperationException
import com.zzu.lite.domain.ParsingException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/** 宿舍电量查询结果；templates 为服务端返回的全部展示项（余额/电量等） */
@kotlinx.serialization.Serializable
data class RoomEnergy(
    val room: String,
    val remaining: Double?,
    val unit: String?,
    val accountName: String?,
    val statusName: String?,
    val templates: List<RoomTemplate> = emptyList()
) {
    /** 首页卡片主数值：优先电量，其次第一项模板 */
    fun displayText(): String =
        (templates.firstOrNull { it.label.contains("电量") || it.label.contains("quantity", ignoreCase = true) }
            ?: templates.firstOrNull())?.valueText
            ?: remaining?.let { "%.1f".format(it) + (unit?.let { u -> " $u" } ?: "") }
            ?: "--"
}

@kotlinx.serialization.Serializable
data class RoomTemplate(val label: String, val valueText: String)

/**
 * 一卡通（电费查询）仓库：host/open 换 tid -> getToken -> 业务接口。
 * accessToken 约 45 分钟有效，过期自动重登。
 */
class ECardRepository(
    private val ecard: ECardService,
    private val userTokenProvider: suspend () -> String,
    private val cache: RoomStore
) {

    private val mutex = Mutex()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var tokenAtMs: Long = 0L

    /** 电费房间层级：area -> building -> level -> room */
    data class RoomOption(val id: String, val name: String)

    suspend fun ensureAccessToken(): String = mutex.withLock {
        val cached = accessToken
        val age = System.currentTimeMillis() - tokenAtMs
        if (cached != null && age < TOKEN_TTL_MS) return cached

        val userToken = userTokenProvider()
        val resp = runCatching { ecard.hostOpen(token = userToken) }
            .getOrElse { throw NetworkException("一卡通登录失败：${it.message ?: it.javaClass.simpleName}") }
        val location = resp.headers()["Location"]
            ?: throw OperationException("一卡通登录失败：未获取到跳转地址（token 可能失效）")
        val tid = runCatching { URI(location).rawQuery }
            .getOrNull()
            ?.split('&')
            ?.mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx <= 0) null else kv.substring(0, idx) to kv.substring(idx + 1)
            }
            ?.firstOrNull { it.first == "tid" }
            ?.second
            ?: throw ParsingException("一卡通登录失败：跳转地址缺少 tid")

        val tokenResp = runCatching { ecard.getToken(TidBody(tid)) }
            .getOrElse { throw NetworkException("一卡通换取 token 失败：${it.message}") }
        val token = tokenResp.resultData?.accessToken?.takeIf { it.isNotBlank() }
            ?: throw ParsingException("一卡通响应缺少 accessToken")
        accessToken = token
        tokenAtMs = System.currentTimeMillis()
        token
    }

    /** 服务端默认绑定的宿舍房间（如 99-12--33-404） */
    suspend fun defaultRoom(): String {
        val token = ensureAccessToken()
        val resp = runCatching { ecard.config(UtilityTypeBody()) }
            .getOrElse { throw NetworkException("获取默认房间失败：${it.message}") }
        val room = resp.resultData?.location?.room?.takeIf { it.isNotBlank() }
            ?: throw friendlyEcardError(resp, "未绑定默认房间")
        return room.also { cache.saveRoom(it) }
    }

    /** 房间绑定记忆（本地），无则取服务端默认；畸形房间号（重复拼接）自动作废 */
    suspend fun currentRoom(): String {
        cache.savedRoom()?.let { saved ->
            val dashCount = saved.count { it == '-' }
            val doubleDash = saved.split("--").size - 1
            // 合法格式 area-building--level-room：至多 4 个单破折号 + 1 个双破折号
            if (dashCount <= 4 && doubleDash == 1) return saved
        }
        return defaultRoom()
    }

    /** 查询剩余电量/电费 */
    suspend fun roomEnergy(room: String): RoomEnergy {
        if (room.isBlank()) throw InvalidArgumentException("房间号不能为空")
        val token = ensureAccessToken()
        val parts = parseRoom(room)
        val body = LocationQueryBody(
            locationType = "room",
            area = parts.area,
            building = parts.building,
            level = parts.level,
            room = room
        )
        val resp = runCatching { ecard.account(body) }
            .getOrElse { throw NetworkException("查询电费失败：${it.message}") }
        val data = resp.resultData ?: throw friendlyEcardError(resp, "查询电费失败：响应缺少数据")
        val templates = data.templateList
            .filter { !it.value.isNullOrBlank() }
            .map { RoomTemplate(label = it.name ?: it.code ?: "数值", valueText = "${it.value}${it.unit?.let { u -> " $u" } ?: ""}") }
        val quantity = data.templateList.firstOrNull { it.code == "quantity" }
            ?: data.templateList.firstOrNull { !it.value.isNullOrBlank() }
        return RoomEnergy(
            room = room,
            remaining = quantity?.value?.trim()?.toDoubleOrNull(),
            unit = quantity?.unit,
            accountName = data.utilityUsername,
            statusName = data.accStatusName,
            templates = templates
        )
    }

    /** 层级枚举（房间改绑级联选择用）：roomId 为空查全部区域 */
    suspend fun roomOptions(roomId: String): List<RoomOption> {
        ensureAccessToken()
        val parts = parseRoom(roomId)
        val locationType = when {
            parts.area.isBlank() -> "bigArea"
            parts.building.isBlank() -> "building"
            parts.level.isBlank() -> "unit"
            else -> "room"
        }
        val body = LocationQueryBody(
            locationType = locationType,
            area = parts.area,
            building = parts.building,
            level = parts.level
        )
        val resp = runCatching { ecard.location(body) }
            .getOrElse { throw NetworkException("查询房间列表失败：${it.message}") }
        val list = resp.resultData?.locationList ?: return emptyList()
        return list.map { RoomOption(it.id, it.name) }
    }

    fun clearCache() {
        accessToken = null
        tokenAtMs = 0L
    }

    /** 供拦截器读取当前 accessToken（可能为 null，登录前） */
    fun accessTokenOrNull(): String? = accessToken

    /** 服务端拒绝 utilityType 时（valid_error_1004），通常意味着该账号未开通/未绑定电费账户 */
    private fun friendlyEcardError(envelope: ECardEnvelope<*>, fallback: String): OperationException {
        val msg = envelope.message.orEmpty()
        return if (envelope.code == "valid_error_1004" || msg.contains("utilityType") || msg.contains("缴费类型")) {
            OperationException("该账号暂未开通电费查询（校园卡系统未找到电费账户），可能需要先在官方渠道绑定宿舍")
        } else {
            OperationException("$fallback（${envelope.message ?: envelope.errormsg ?: "未知原因"}）")
        }
    }

    private fun ensureTokenValid(envelope: ECardEnvelope<*>, what: String) {
        // 一卡通失败常以 resultData 缺失呈现，这里仅做消息透出
        if (envelope.message?.contains("登录", ignoreCase = true) == true) {
            throw OperationException("${what}失败：${envelope.message}")
        }
    }

    /** 解析房间 ID：`{area}-{building}--{level}-{room}` */
    data class RoomParts(val area: String, val building: String, val level: String, val room: String)

    private fun parseRoom(roomId: String): RoomParts {
        val left = roomId.substringBefore("--")
        val right = roomId.substringAfter("--", "")
        val area = left.substringBefore("-")
        val building = left.substringAfter("-", "")
        val level = right.substringBefore("-", "").ifBlank { right.substringBefore("-") }
        return RoomParts(area, building, level, roomId)
    }

    private companion object {
        const val TOKEN_TTL_MS = 45L * 60L * 1000L
    }
}
