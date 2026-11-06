package com.zzu.lite

import com.zzu.lite.data.local.CredentialsStore
import com.zzu.lite.di.AuthHeaderInterceptor
import com.zzu.lite.domain.util.Jwt
import com.zzu.lite.domain.util.TimeUtil
import com.zzu.lite.data.repo.ClassroomRepository
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
 * 空教室仓库层真实接口验证（复用已保存 token）。
 */
class ClassroomApiTest {

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

    private val store = FakeCredentialsStore(deviceId = "ZZULite-Test-1.0")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val easService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(
                AuthHeaderInterceptor(
                    userTokenProvider = { store.userToken },
                    ecardTokenProvider = { null }
                )
            )
            .build()
        Retrofit.Builder()
            .baseUrl("https://jwxt.zzu.edu.cn/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(com.zzu.lite.data.remote.EasService::class.java)
    }

    @Test
    fun `空教室全链路查询`() = runBlocking {
        val tokenFile = File(System.getProperty("zz.codeFile") ?: ".").resolveSibling("zzu_tokens.txt")
        if (tokenFile.exists()) {
            val lines = tokenFile.readLines()
            if (lines.size >= 2 && Jwt.isValidFor(lines[0])) store.saveTokens(lines[0], lines[1])
        }
        assumeTrue("没有可用 token，跳过", !store.userToken.isNullOrBlank())

        val repo = ClassroomRepository(easService)
        val campuses = repo.campuses()
        println("校区: " + campuses.joinToString { "${it.id}:${it.nameZh}" })
        org.junit.Assert.assertTrue("校区不应为空", campuses.isNotEmpty())

        val units = repo.courseUnits()
        println("节次数: ${units.size}（第1节 ${TimeUtil.hhmm(units.first().startTime)}）")
        org.junit.Assert.assertEquals("应为 10 节", 10, units.size)

        val mainCampus = campuses.first()
        val buildings = repo.buildings(mainCampus.id)
        println("主校区楼栋数: ${buildings.size}，样例: ${buildings.take(5).joinToString { it.nameZh ?: "?" }}")

        // 今天第 3-4 节、主校区、北3 教学楼
        val targetBuilding = buildings.firstOrNull { it.nameZh?.startsWith("北3") == true }
        val result = repo.availableRooms(
            date = TimeUtil.today(),
            campusId = mainCampus.id,
            buildingId = targetBuilding?.id,
            unitNumbers = setOf(3, 4)
        )
        println("查询: ${result.date} 第3-4节 ${targetBuilding?.nameZh ?: "全部楼栋"}")
        println("扫描 ${result.totalFetched} 间，空闲 ${result.available.size} 间")
        result.available.take(8).forEach {
            println("  - ${it.buildingName ?: ""} ${it.room.nameZh} (${it.room.code ?: "?"}) 容量=${it.room.seatsForLesson ?: 0}")
        }
        org.junit.Assert.assertTrue("白天 3-4 节应有空教室", result.available.isNotEmpty())
        println("==> 空教室全链路通过 ✅")
    }
}
