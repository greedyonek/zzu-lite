package com.zzu.lite.di

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 按接口放宽超时：空教室的教室列表是 1MB+ 大响应（1600+ 教室含占用），
 * 手机网络下 20 秒全局读超时不够，单独放宽到 45 秒。
 */
class BigResponseTimeoutInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return if (request.url.encodedPath.contains("/room/place/rooms")) {
            chain.withReadTimeout(45, TimeUnit.SECONDS)
                .withWriteTimeout(20, TimeUnit.SECONDS)
                .proceed(request)
        } else {
            chain.proceed(request)
        }
    }
}
