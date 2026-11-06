package com.zzu.lite

import com.zzu.lite.data.local.CredentialsStore
import com.zzu.lite.data.local.RoomStore
import com.zzu.lite.data.remote.ApiConst
import com.zzu.lite.data.remote.CasService
import com.zzu.lite.data.remote.ECardService
import com.zzu.lite.data.remote.EasService
import com.zzu.lite.data.remote.InfoService
import com.zzu.lite.data.remote.PersonalService
import com.zzu.lite.di.AuthHeaderInterceptor
import com.zzu.lite.data.repo.AuthRepository
import com.zzu.lite.data.repo.ECardRepository
import com.zzu.lite.domain.model.TeachingWeeks
import com.zzu.lite.domain.util.TimeUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真实接口集成测试（需要网络与有效账号，CI 会因缺少 zz.user 属性自动跳过）。
 *
 * 用法：
 *   gradle test -Dzz.user=学号 -Dzz.pass=密码
 *   触发 MFA 时：把收到的 6 位验证码写入 -Dzz.codeFile 指定的文件（默认 workdir 下 zzu_code.txt），
 *   测试进程每 3 秒轮询一次，最长等待 3 分钟。
 */
class RealApiIntegrationTest {

    private class FakeCredentialsStore(override var deviceId: String) : CredentialsStore {
        override var userToken: String? = null
        override var refreshToken: String? = null
        override var username: String? = null
        override var password: String? = null
        override var rememberPassword: Boolean = false
        override fun saveTokens(userToken: String, refreshToken: String) {
            this.userToken = userToken
            this.refreshToken = refreshToken
        }
        override fun saveCredentials(username: String, password: String?, remember: Boolean) {}
        override fun clearAll() {
            userToken = null
            refreshToken = null
        }
    }

    private class FakeRoomStore : RoomStore {
        var room: String? = null
        override suspend fun savedRoom(): String? = room
        override suspend fun saveRoom(room: String?) { this.room = room }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val store = FakeCredentialsStore(deviceId = "ZZULite-Test-1.0")
    private val roomStore = FakeRoomStore()
    private val auth = AuthRepository(casService(), store)

    private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            AuthHeaderInterceptor(
                userTokenProvider = { store.userToken },
                ecardTokenProvider = { ecardRepo.accessTokenOrNull() }
            )
        )
        .build()

    private fun casService(): CasService =
        retrofit("https://cas.s.zzu.edu.cn/", OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build())
            .create(CasService::class.java)

    private val easService: EasService =
        retrofit("https://jwxt.zzu.edu.cn/", baseClient).create(EasService::class.java)

    private val infoService: InfoService =
        retrofit("https://info.s.zzu.edu.cn/", baseClient).create(InfoService::class.java)

    private val personalService: PersonalService =
        retrofit("https://authx-service.s.zzu.edu.cn/", baseClient).create(PersonalService::class.java)

    private val ecardService: ECardService =
        retrofit(
            "https://ecard.v.zzu.edu.cn/",
            baseClient.newBuilder().followRedirects(false).build()
        ).create(ECardService::class.java)

    /** 惰性初始化：ecardService 就绪后创建，拦截器延迟读取其 accessToken */
    private val ecardRepo: ECardRepository by lazy {
        ECardRepository(
            ecard = ecardService,
            userTokenProvider = { auth.ensureUserToken() },
            cache = roomStore
        )
    }

    @Test
    fun `登录并拉取课表 学期 个人信息 余额 电费`() = runBlocking {
        val user = System.getProperty("zz.user")
        val pass = System.getProperty("zz.pass")
        assumeTrue("缺少 -Dzz.user/-Dzz.pass，跳过真实接口测试", !user.isNullOrBlank() && !pass.isNullOrBlank())

        val tokenFile = File(System.getProperty("zz.codeFile") ?: "zzu_code.txt").resolveSibling("zzu_tokens.txt")
        val resumed = if (tokenFile.exists()) {
            val lines = tokenFile.readLines()
            if (lines.size >= 2 && com.zzu.lite.domain.util.Jwt.isValidFor(lines[0])) {
                store.saveTokens(lines[0], lines[1])
                println("==> [1] 复用已保存的 token（跳过登录/MFA）")
                true
            } else false
        } else false

        if (!resumed) {
            println("==> [1] beginLogin: ${user?.take(4)}****")
            val needMfa = auth.beginLogin(user!!, pass!!)
            println("==> MFA required = $needMfa")
            if (needMfa) {
                var code = System.getProperty("zz.code")
                if (code.isNullOrBlank()) {
                    val codeFile = File(System.getProperty("zz.codeFile") ?: "zzu_code.txt")
                    println("==> 请将短信验证码写入文件: ${codeFile.absolutePath}")
                    println("==> 等待验证码（最长 3 分钟，接受 4-6 位）...")
                    val deadline = System.currentTimeMillis() + 3 * 60_000L
                    while (code.isNullOrBlank() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(2000)
                        if (codeFile.exists()) {
                            val content = codeFile.readText().trim().take(8)
                            if (content.length in 4..6) {
                                code = content
                                break
                            }
                        }
                    }
                }
                assumeTrue("未获得验证码", !code.isNullOrBlank())
                auth.verifySms(code!!)
                println("==> MFA 验证通过")
            }

            val tokens = auth.completeLogin()
            tokenFile.writeText(tokens.idToken + "\n" + tokens.refreshToken)
            println("==> 登录成功：idToken 长度=${tokens.idToken.length}, refreshToken 长度=${tokens.refreshToken.length}")
            org.junit.Assert.assertTrue(tokens.idToken.contains("."))
        }

        println("==> [2] 学期列表")
        val semesters = easService.allSemesters()
        org.junit.Assert.assertEquals("学期列表 result 应为 0", 0, semesters.result)
        val semesterList = semesters.data.orEmpty()
        println("    学期数=${semesterList.size}, 最近=${semesterList.takeLast(2).map { it.nameZh }}")

        println("==> [3] 当前学期")
        val current = easService.currentSemester()
        org.junit.Assert.assertEquals("当前学期 result 应为 0", 0, current.result)
        val sem = current.data ?: throw AssertionError("当前学期为空")
        println("    当前学期=${sem.nameZh} id=${sem.id} 周次列表=${sem.weekIndices}")

        println("==> [4] 课表")
        val table = easService.courseTable(sem.id)
        org.junit.Assert.assertEquals("课表 result 应为 0", 0, table.result)
        val data = table.data.orEmpty()
        println("    课程（教学班）数=${data.size}")
        val weeks = TeachingWeeks.build(sem.weekIndices, data)
        println("    总教学周数=${weeks.totalWeeks}")
        weeks.weeks.firstNotNullOfOrNull { if (it.dayLessons(1).isNotEmpty()) it else null }?.let { w ->
            println("    第${w.weekIndex}周周一课程：")
            w.dayLessons(1).forEach {
                println("      - ${it.courseName} @ ${it.place} ${TimeUtil.hhmm(it.startTime)}-${TimeUtil.hhmm(it.endTime)} (${it.startUnit}-${it.endUnit}节)")
            }
        }
        org.junit.Assert.assertTrue("课表不应为空", data.isNotEmpty())

        println("==> [5] 个人信息")
        val me = personalService.me()
        org.junit.Assert.assertEquals("个人信息 code 应为 0", 0, me.code)
        println("    姓名=${me.data?.attributes?.userName} 学院=${me.data?.attributes?.organizationName} 身份=${me.data?.attributes?.identityTypeName}")

        println("==> [6] 一卡通余额")
        val cards = infoService.infoCards()
        org.junit.Assert.assertEquals("卡片列表 code 应为 0", 0, cards.code)
        val balance = cards.data?.getOrNull(1)?.amount
        println("    卡片列表=${cards.data?.map { "${it.name}=${it.amount}" }}")
        println("    余额（下标1）=$balance")
        org.junit.Assert.assertNotNull("余额应存在", balance)

        println("==> [7] 教学周序数")
        val wot = infoService.weekOfTeaching(TimeUtil.today())
        println("    响应=${wot.data?.data?.date} semester=${wot.data?.data?.semester}")

        println("==> [8] 一卡通电费")
        val accessToken = ecardRepo.ensureAccessToken()
        println("    accessToken 前20位=${accessToken.take(20)}")
        val cfgRaw = ecardService.config(com.zzu.lite.data.remote.dto.UtilityTypeBody())
        println("    config 原始响应: $cfgRaw")
        val locRaw = ecardService.location(com.zzu.lite.data.remote.dto.LocationQueryBody(locationType = "bigArea"))
        println("    location(bigArea) 原始响应: $locRaw")

        val room = runCatching { ecardRepo.defaultRoom() }.getOrNull()
        if (room == null) {
            println("    !! 该账号未绑定默认电费房间（服务端未返回），改用区域枚举验证接口链路")
            val areas = ecardRepo.roomOptions("")
            println("    区域枚举（前5项）=${areas.take(5)}，共 ${areas.size} 个")
            println("    （应用内可通过\"改绑房间\"级联选择器手动绑定宿舍）")
        } else {
            println("    默认房间=$room")
            val energy = ecardRepo.roomEnergy(room)
            println("    剩余电量=${energy.remaining} ${energy.unit ?: "度"} 户名=${energy.accountName}")
            org.junit.Assert.assertTrue("电量应可解析", energy.remaining != null)
        }

        println("==> 全部通过 ✅")
    }
}
