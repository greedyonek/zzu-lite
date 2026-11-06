package com.zzu.lite.data.remote

import com.zzu.lite.data.remote.dto.ApiEnvelope
import com.zzu.lite.data.remote.dto.CourseDto
import com.zzu.lite.data.remote.dto.ECardAccountData
import com.zzu.lite.data.remote.dto.ECardConfigData
import com.zzu.lite.data.remote.dto.ECardEnvelope
import com.zzu.lite.data.remote.dto.ECardLocationData
import com.zzu.lite.data.remote.dto.ECardTokenData
import com.zzu.lite.data.remote.dto.EasEnvelope
import com.zzu.lite.data.remote.dto.InfoCardDto
import com.zzu.lite.data.remote.dto.LessonDatumDto
import com.zzu.lite.data.remote.dto.LocationQueryBody
import com.zzu.lite.data.remote.dto.LoginData
import com.zzu.lite.data.remote.dto.MfaDetectData
import com.zzu.lite.data.remote.dto.MfaGidBody
import com.zzu.lite.data.remote.dto.MfaInitData
import com.zzu.lite.data.remote.dto.MfaSendData
import com.zzu.lite.data.remote.dto.MfaValidBody
import com.zzu.lite.data.remote.dto.MfaValidData
import com.zzu.lite.data.remote.dto.PersonalDataDto
import com.zzu.lite.data.remote.dto.SemesterDto
import com.zzu.lite.data.remote.dto.TidBody
import com.zzu.lite.data.remote.dto.UtilityTypeBody
import com.zzu.lite.data.remote.dto.WeekOfTeachingInnerDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

object ApiConst {
    /** 与 ZZU.Py 保持一致的伪装 UA */
    const val UA_OKHTTP = "okhttp/3.12.1"
    const val UA_APP = "SWSuperApp/1.1.1()"
    const val APP_ID = "com.supwisdom.zzu"
    const val OS_TYPE = "android"
    const val DEFAULT_ATTEST = "https://cas.s.zzu.edu.cn/attest/api/guard"
}

/** CAS 统一认证（App 端 JWT 流程）。注意：登录/MFA 参数全部在 query string，body 为空。 */
interface CasService {

    /** 返回 PEM 公钥文本（非 JSON）。 */
    @GET("token/jwt/publicKey")
    suspend fun publicKey(@Header("User-Agent") ua: String = ApiConst.UA_OKHTTP): Response<ResponseBody>

    @POST("token/password/passwordLogin")
    suspend fun passwordLogin(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("appId") appId: String = ApiConst.APP_ID,
        @Query("osType") osType: String = ApiConst.OS_TYPE,
        @Query("geo") geo: String = "",
        @Query("deviceId") deviceId: String,
        @Query("clientId") clientId: String = "",
        @Query("mfaState") mfaState: String = "",
        @Header("User-Agent") ua: String = ApiConst.UA_APP
    ): ApiEnvelope<LoginData>

    @POST("token/mfa/detect")
    suspend fun mfaDetect(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("deviceId") deviceId: String,
        @Header("User-Agent") ua: String = ApiConst.UA_APP
    ): ApiEnvelope<MfaDetectData>

    @GET("token/mfa/initByType/securephone")
    suspend fun mfaInit(
        @Query("state") state: String,
        @Header("User-Agent") ua: String = ApiConst.UA_APP
    ): ApiEnvelope<MfaInitData>

    /** URL 为动态的 attestServerUrl + /api/guard/securephone/send */
    @POST
    suspend fun mfaSend(
        @Url url: String,
        @Body body: MfaGidBody,
        @Header("User-Agent") ua: String = ApiConst.UA_APP
    ): ApiEnvelope<MfaSendData>

    @POST
    suspend fun mfaValid(
        @Url url: String,
        @Body body: MfaValidBody,
        @Header("User-Agent") ua: String = ApiConst.UA_APP
    ): ApiEnvelope<MfaValidData>
}

/** 新本科教务（jwxt.zzu.edu.cn）。Authorization/Cookie 头由拦截器注入。 */
interface EasService {

    @GET("eams-door/api/v1/portal/home/user-info")
    suspend fun userInfo(): EasEnvelope<kotlinx.serialization.json.JsonElement>

    @GET("eams-micro-server/api/v1/semester/current-semester")
    suspend fun currentSemester(): EasEnvelope<SemesterDto>

    @GET("eams-door/api/v1/calendar/get-all-semesters")
    suspend fun allSemesters(): EasEnvelope<List<SemesterDto>>

    @GET("eams-micro-server/api/v1/lesson/student/course-table/{semesterId}")
    suspend fun courseTable(@Path("semesterId") semesterId: Int): EasEnvelope<List<LessonDatumDto>>

    // ---- 空教室（room 模块，实测自 2026-11） ----

    @GET("eams-micro-server/api/v1/room/place/campus")
    suspend fun campuses(): EasEnvelope<List<com.zzu.lite.data.remote.dto.CampusDto>>

    @GET("eams-micro-server/api/v1/room/place/building")
    suspend fun buildings(@Query("campusAssoc") campusId: Int): EasEnvelope<List<com.zzu.lite.data.remote.dto.BuildingDto>>

    @GET("eams-micro-server/api/v1/room/system/course-units")
    suspend fun courseUnits(): EasEnvelope<List<com.zzu.lite.data.remote.dto.CourseUnitDto>>

    @POST("eams-micro-server/api/v1/room/place/rooms")
    suspend fun rooms(@Body body: com.zzu.lite.data.remote.dto.RoomsQueryBody): EasEnvelope<com.zzu.lite.data.remote.dto.RoomsPageDto>
}

/** info.s.zzu.edu.cn（portal-api），需要 X-Id-Token 头。 */
interface InfoService {

    @GET("portal-api/v1/thrid-adapter/get-person-info-card-list")
    suspend fun infoCards(): ApiEnvelope<List<InfoCardDto>>

    @GET("portal-api/v1/calendar/share/schedule/getWeekOfTeaching")
    suspend fun weekOfTeaching(@Query("today") today: String): ApiEnvelope<WeekOfTeachingInnerDto>
}

/** authx-service.s.zzu.edu.cn，需要 X-Id-Token 头。 */
interface PersonalService {

    @GET("personal/api/v1/personal/me/user")
    suspend fun me(): ApiEnvelope<PersonalDataDto>
}

/** 一卡通（ecard.v.zzu.edu.cn）。hostOpen 必须使用禁跟随重定向的客户端。 */
interface ECardService {

    @GET("server/auth/host/open")
    suspend fun hostOpen(
        @Query("host") host: String = "11",
        @Query("org") org: String = "2",
        @Query("token") token: String
    ): Response<Void>

    @POST("server/auth/getToken")
    suspend fun getToken(@Body body: TidBody): ECardEnvelope<ECardTokenData>

    @POST("server/utilities/config")
    suspend fun config(@Body body: UtilityTypeBody): ECardEnvelope<ECardConfigData>

    @POST("server/utilities/location")
    suspend fun location(@Body body: LocationQueryBody): ECardEnvelope<ECardLocationData>

    @POST("server/utilities/account")
    suspend fun account(@Body body: LocationQueryBody): ECardEnvelope<ECardAccountData>
}

/** 课程 DTO 的简洁别名（repo 内部构建网格用） */
typealias CourseTableDatum = LessonDatumDto
