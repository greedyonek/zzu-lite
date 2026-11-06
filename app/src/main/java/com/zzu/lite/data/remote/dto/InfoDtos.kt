package com.zzu.lite.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class PersonalAttributesDto(
    val userUid: String? = null,
    val userName: String? = null,
    val identityTypeName: String? = null,
    val organizationName: String? = null,
    val imageUrl: String? = null,
    val accountId: String? = null,
    val userId: String? = null
)

@Serializable
data class PersonalDataDto(
    val username: String? = null,
    val roles: List<String> = emptyList(),
    val attributes: PersonalAttributesDto? = null
)

@Serializable
data class InfoCardDto(
    val name: String? = null,
    val iconUrl: String? = null,
    val showNumber: String? = null,
    val amount: String? = null,
    val unit: String? = null,
    val detailUrl: String? = null
)

@Serializable
data class WeekOfTeachingInnerDto(
    val msg: String? = null,
    val code: Int? = null,
    val success: Boolean? = null,
    val data: WeekOfTeachingDataDto? = null
)

@Serializable
data class WeekOfTeachingDataDto(
    val date: List<String> = emptyList(),
    val semester: String? = null
)
