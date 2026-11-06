package com.zzu.lite.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.zzu.lite.MainActivity
import com.zzu.lite.ZzuApp
import com.zzu.lite.data.repo.ScheduleRepository
import com.zzu.lite.di.AppContainer
import com.zzu.lite.di.SessionState
import com.zzu.lite.domain.model.CourseLesson
import com.zzu.lite.domain.util.TimeUtil
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** 小组件展示的数据（缓存优先，8 秒内取不到就显示引导态） */
data class WidgetData(
    val loggedIn: Boolean,
    val hasCache: Boolean,
    val weekIndex: Int?,
    val weekdayName: String,
    val lessons: List<CourseLesson> = emptyList()
)

suspend fun loadWidgetData(container: AppContainer): WidgetData {
    val loggedIn = container.session.value is SessionState.LoggedIn
    val fallback = WidgetData(loggedIn = loggedIn, hasCache = false, weekIndex = null, weekdayName = weekdayName())
    return withTimeoutOrNull(8000L) {
        if (!loggedIn) return@withTimeoutOrNull fallback
        val cached = runCatching { container.scheduleRepository.load(forceRefresh = false) }.getOrNull()
            ?: return@withTimeoutOrNull fallback
        buildFromTimetable(container, cached)
    } ?: fallback
}

private suspend fun buildFromTimetable(
    container: AppContainer,
    cached: ScheduleRepository.TimetableData
): WidgetData {
    val week = runCatching { container.scheduleRepository.currentWeekIndex(cached.semester) }.getOrNull()
    val today = LocalDate.now().dayOfWeek.value
    val lessons = week?.let { w -> cached.weeks.week(w) }?.dayLessons(today).orEmpty()
    return WidgetData(
        loggedIn = true,
        hasCache = true,
        weekIndex = week,
        weekdayName = weekdayName(),
        lessons = lessons
    )
}

private fun weekdayName(): String =
    LocalDate.now().dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.CHINESE)

class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as ZzuApp).container
        val data = loadWidgetData(container)
        provideContent {
            GlanceTheme {
                WidgetContent(data)
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

@Composable
private fun WidgetContent(data: WidgetData) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        when {
            !data.loggedIn -> HintText("点按打开 ZZU_Lite 登录")
            !data.hasCache -> HintText("还没有课表缓存\n连接校园网打开应用刷新一次")
            else -> Column(modifier = GlanceModifier.fillMaxSize()) {
                Text(
                    text = (data.weekIndex?.let { "第 $it 周" } ?: "假期中") + " · " + data.weekdayName +
                        " ｜ 今日 ${data.lessons.size} 节",
                    style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                    maxLines = 1
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
                if (data.lessons.isEmpty()) {
                    HintText("今天没有课程，好好休息 🎉")
                } else {
                    val shown = data.lessons.take(4)
                    val nextStart = nextLessonStart(data.lessons)
                    shown.forEach { lesson ->
                        LessonRow(lesson, isNext = lesson.startTime == nextStart)
                    }
                    if (data.lessons.size > shown.size) {
                        Text(
                            text = "…还有 ${data.lessons.size - shown.size} 节",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                        )
                    }
                }
            }
        }
    }
}

/** 找到"当前或下一节"的开始时间：还没下课或尚未开始的课 */
private fun nextLessonStart(lessons: List<CourseLesson>): String? {
    val now = LocalTime.now()
    return lessons.firstOrNull { lesson ->
        val start = TimeUtil.parseHhmm(lesson.startTime)
        val end = TimeUtil.parseHhmm(lesson.endTime)
        start != null && (start.isAfter(now) || (end != null && now < end))
    }?.startTime
}

@Composable
private fun LessonRow(lesson: CourseLesson, isNext: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = TimeUtil.hhmm(lesson.startTime) ?: "--:--",
            style = TextStyle(
                color = if (isNext) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal
            ),
            modifier = GlanceModifier.width(46.dp)
        )
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = (if (isNext) "▶ " else "") + lesson.courseName,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal
                ),
                maxLines = 1
            )
            Text(
                text = listOfNotNull(lesson.place, lesson.teacher).joinToString(" · ").ifBlank { "地点待定" },
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        maxLines = 3
    )
}
