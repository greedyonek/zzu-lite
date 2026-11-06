package com.zzu.lite.data.remote.dto

import kotlinx.serialization.Serializable

/** 一卡通响应壳：业务看 resultData 是否存在 */
@Serializable
data class ECardEnvelope<T>(
    val resultData: T? = null,
    val success: Boolean? = null,
    val message: String? = null,
    val code: String? = null,
    val errormsg: String? = null
)

@Serializable
data class TidBody(val tid: String)

@Serializable
data class ECardTokenData(
    val accessToken: String = "",
    val refreshToken: String = ""
)

@Serializable
data class UtilityTypeBody(val utilityType: String = "electric")

@Serializable
data class ECardConfigLocationDto(val room: String? = null)

@Serializable
data class ECardConfigData(val location: ECardConfigLocationDto? = null)

@Serializable
data class ECardLocationItemDto(val id: String = "", val name: String = "")

@Serializable
data class ECardLocationData(val locationList: List<ECardLocationItemDto> = emptyList())

/**
 * 电费位置查询/账户查询通用请求体。
 * locationType: bigArea / building / unit / room；account 查询时用完整 room 字段。
 */
@Serializable
data class LocationQueryBody(
    val utilityType: String = "electric",
    val locationType: String = "",
    val bigArea: String = "",
    val area: String = "",
    val building: String = "",
    val unit: String = "",
    val level: String = "",
    val room: String = "",
    val subArea: String = ""
)

@Serializable
data class ECardTemplateDto(
    val code: String? = null,
    val name: String? = null,
    /** 实测为布尔（true），历史文档描述为 Int，这里用 JsonElement 兼容两种 */
    val show: kotlinx.serialization.json.JsonElement? = null,
    val value: String? = null,
    val unit: String? = null
)

@Serializable
data class ECardAccountData(
    val accStatusName: String? = null,
    val accountNum: kotlinx.serialization.json.JsonElement? = null,
    val balance: kotlinx.serialization.json.JsonElement? = null,
    val utilityUsername: String? = null,
    val utilityStatusName: String? = null,
    val templateList: List<ECardTemplateDto> = emptyList()
)
