package com.zzu.lite.data.repo

import com.zzu.lite.data.remote.InfoService
import com.zzu.lite.data.remote.PersonalService
import com.zzu.lite.data.remote.dto.ApiEnvelope
import com.zzu.lite.domain.OperationException
import com.zzu.lite.domain.ParsingException

/**
 * 个人信息 / 一卡通余额 / 教学周接口。
 */
class UserRepository(
    private val personal: PersonalService,
    private val info: InfoService
) {

    data class PersonalInfo(
        val uid: String,
        val name: String,
        val identity: String?,
        val college: String?
    )

    suspend fun personalInfo(): PersonalInfo {
        val resp = runCatching { personal.me() }.getOrElse {
            throw com.zzu.lite.domain.util.FriendlyNetwork.wrap(it, "获取个人信息")
        }
        requireCode(resp, "获取个人信息")
        val attrs = resp.data?.attributes
            ?: throw ParsingException("个人信息响应缺少数据")
        return PersonalInfo(
            uid = attrs.userUid ?: resp.data.username.orEmpty(),
            name = attrs.userName ?: "同学",
            identity = attrs.identityTypeName,
            college = attrs.organizationName
        )
    }

    /** 一卡通余额：info-card 列表下标 1 */
    suspend fun cardBalance(): Double? {
        val resp = runCatching { info.infoCards() }.getOrElse {
            throw com.zzu.lite.domain.util.FriendlyNetwork.wrap(it, "获取卡片信息")
        }
        requireCode(resp, "获取卡片信息")
        val cards = resp.data ?: return null
        val amount = cards.getOrNull(1)?.amount ?: return null
        return amount.trim().toDoubleOrNull()
    }

    private fun <T> requireCode(envelope: ApiEnvelope<T>, what: String) {
        if (envelope.code != 0) {
            throw OperationException("${what}失败：${envelope.message ?: "code=${envelope.code}"}")
        }
    }
}
