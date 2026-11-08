package com.zzu.lite.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zzu.lite.MainActivity
import com.zzu.lite.R
import com.zzu.lite.ZzuApp
import com.zzu.lite.data.repo.RoomEnergy
import java.util.concurrent.TimeUnit

/**
 * 电费不足提醒。
 * 指标优先级：剩余电量（度，房间实际消耗）> 电费余额（元）> remaining 兜底。
 * 策略：跨过阈值立即提醒一次；持续偏低时每 24 小时重提一次；回充到阈值以上自动复位。
 */
object EnergyAlert {

    const val THRESHOLD = 10.0
    private const val CHANNEL_ID = "energy_alert"
    private const val NOTIFICATION_ID = 1001
    private const val REPEAT_INTERVAL_MS = 24L * 3600_000L

    /** 提醒盯的指标（kind 用于区分阈值口径，避免电量/余额切换时误判"跨阈值"） */
    data class Metric(val kind: String, val label: String, val value: Double, val unit: String)

    private val numberRegex = Regex("""\d+(\.\d+)?""")

    private fun parseValue(text: String): Double? =
        numberRegex.find(text)?.value?.toDoubleOrNull()

    /** 选择提醒指标：电量优先，其次余额类，最后 remaining 兜底 */
    fun primaryMetric(energy: RoomEnergy): Metric? {
        val quantity = energy.templates.firstOrNull {
            it.label.contains("电量") || it.label.contains("quantity", ignoreCase = true)
        }
        if (quantity != null) {
            parseValue(quantity.valueText)?.let {
                return Metric("energy", "剩余电量", it, "度")
            }
        }
        val money = energy.templates.firstOrNull {
            it.label.contains("余额") || it.label.contains("电费") || it.valueText.contains("元")
        }
        if (money != null) {
            parseValue(money.valueText)?.let {
                return Metric("money", "电费余额", it, "元")
            }
        }
        return energy.remaining?.let { Metric("energy", "剩余电量", it, "度") }
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "电费不足提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "宿舍电量/电费低于阈值时提醒充值"
                }
            )
        }
    }

    /**
     * 对一次电费查询结果做提醒判断。
     * @return 是否实际发出了通知
     */
    suspend fun check(context: Context, energy: RoomEnergy): Boolean {
        val appContext = context.applicationContext
        val store = (appContext as ZzuApp).container.cacheStore
        if (!store.energyAlertEnabled()) return false

        val metric = primaryMetric(energy) ?: return false
        val now = System.currentTimeMillis()
        val last = store.lastAlertValue()
        val lastKind = store.lastAlertKind()
        val lastAt = store.lastAlertAt()

        val isLow = metric.value < THRESHOLD
        if (!isLow) {
            // 回充复位，下次再跌破时可再次提醒
            if (last != null) store.saveAlertState(null, null, 0L)
            return false
        }
        val crossing = last == null || lastKind != metric.kind || last >= THRESHOLD
        val stale = now - lastAt >= REPEAT_INTERVAL_MS
        if (!crossing && !stale) return false

        notify(appContext, metric, energy)
        store.saveAlertState(metric.value, metric.kind, now)
        return true
    }

    private fun notify(context: Context, metric: Metric, energy: RoomEnergy) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (metric.kind == "energy") "宿舍电量不足" else "宿舍电费不足"
        val text = "${metric.label} ${"%.2f".format(metric.value)} ${metric.unit}" +
            "（房间 ${energy.room}）已低于 ${"%.0f".format(THRESHOLD)} ${metric.unit}，请及时充值"
        val notification: Notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

/** 后台每小时巡检：拉一次电费并判断是否提醒。token 失效时静默跳过等下轮。 */
class EnergyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val container = (applicationContext as ZzuApp).container
            val room = container.ecardRepository.currentRoom()
            val energy = container.ecardRepository.roomEnergy(room)
            container.publishRoomEnergy(energy)
            EnergyAlert.check(applicationContext, energy)
            Result.success()
        } catch (e: Exception) {
            // 网络波动等：退避重试；无凭据时也走 retry（下轮再试，不会阻塞）
            Result.retry()
        }
    }
}

object EnergyCheckScheduler {

    private const val WORK_NAME = "energy_periodic_check"

    /** 每小时巡检一次（应用启动时由 AppContainer 调用） */
    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<EnergyCheckWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }
}
