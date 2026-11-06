package com.zzu.lite.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzu.lite.LocalAppContainer
import com.zzu.lite.data.repo.UserRepository
import com.zzu.lite.domain.model.CourseLesson
import com.zzu.lite.domain.util.TimeUtil
import com.zzu.lite.ui.common.VmFactory
import com.zzu.lite.ui.theme.courseColor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class TodayUiState(
    val loading: Boolean = false,
    val personal: UserRepository.PersonalInfo? = null,
    val balance: Double? = null,
    val energyText: String? = null,
    val weekIndex: Int? = null,
    val semesterName: String? = null,
    val lessons: List<CourseLesson> = emptyList(),
    val fromCache: Boolean = false,
    val error: String? = null
)

class TodayViewModel(private val containerProvider: () -> com.zzu.lite.di.AppContainer) : ViewModel() {

    private val container by lazy { containerProvider() }

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state

    init {
        // 电费：订阅共享状态（服务页查询后这里实时更新；启动时也会回放上次缓存）
        viewModelScope.launch {
            container.roomEnergyState.collect { energy ->
                _state.update { it.copy(energyText = energy?.displayText()) }
            }
        }
    }

    /** 后台刷新电费（失败静默，保持上次值） */
    fun refreshEnergy() {
        viewModelScope.launch {
            runCatching {
                val room = container.ecardRepository.currentRoom()
                container.ecardRepository.roomEnergy(room)
            }.onSuccess { container.publishRoomEnergy(it) }
        }
    }

    fun load(forceRefresh: Boolean = false) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var error: String? = null
            try {
                val timetable = container.scheduleRepository.load(forceRefresh = forceRefresh)
                val weekdayToday = LocalDate.now().dayOfWeek.value
                val week = runCatching {
                    container.scheduleRepository.currentWeekIndex(timetable.semester)
                }.getOrNull()
                val day = if (week != null) timetable.weeks.week(week) else null
                val lessons = day?.dayLessons(weekdayToday).orEmpty()

                var personal: UserRepository.PersonalInfo? = null
                var balance: Double? = null
                coroutineScope {
                    val p = async { runCatching { container.userRepository.personalInfo() }.getOrNull() }
                    val b = async { runCatching { container.userRepository.cardBalance() }.getOrNull() }
                    personal = p.await()
                    balance = b.await()
                }
                _state.update {
                    it.copy(
                        loading = false,
                        personal = personal,
                        balance = balance,
                        weekIndex = week,
                        semesterName = timetable.semester.nameZh ?: timetable.semester.schoolYear,
                        lessons = lessons,
                        fromCache = timetable.fromCache
                    )
                }
                refreshEnergy()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
                // 有缓存时降级展示缓存
                val cached = runCatching { container.scheduleRepository.load(forceRefresh = false) }.getOrNull()
                if (cached != null) {
                    val weekdayToday = LocalDate.now().dayOfWeek.value
                    val week = runCatching {
                        container.scheduleRepository.currentWeekIndex(cached.semester)
                    }.getOrNull()
                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            weekIndex = week,
                            semesterName = cached.semester.nameZh ?: cached.semester.schoolYear,
                            lessons = cached.weeks.week(week ?: 1)?.dayLessons(weekdayToday).orEmpty(),
                            fromCache = true
                        )
                    }
                } else {
                    _state.update { it.copy(loading = false, error = error) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen() {
    val container = LocalAppContainer.current
    val vm: TodayViewModel = viewModel(factory = VmFactory.of { TodayViewModel { container } })
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load(forceRefresh = false) }

    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { vm.load(forceRefresh = true) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            val now = LocalDate.now()
            val name = state.personal?.name ?: "同学"
            Text(
                "你好，$name",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            val weekdayName = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)
            val weekText = state.weekIndex?.let { "第 $it 周" } ?: "假期中"
            Text(
                "$weekdayName · $weekText" + if (state.fromCache) " · 缓存" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val personal = state.personal
            if (personal?.college != null) {
                Text(
                    personal.college.orEmpty() + (personal.identity?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard(
                    modifier = Modifier.weight(1f),
                    title = "一卡通余额",
                    value = state.balance?.let { "¥%.2f".format(it) } ?: "--"
                )
                InfoCard(
                    modifier = Modifier.weight(1f),
                    title = "宿舍电量",
                    value = state.energyText ?: "--",
                    action = if (state.energyText == null) ({ }) else null,
                    actionLabel = if (state.energyText == null) "服务页查看" else null
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("今日课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (state.lessons.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(state.error ?: "今天没有课程，好好休息吧~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                state.lessons.forEach { lesson -> LessonTimelineItem(lesson) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String?,
    action: (() -> Unit)? = null,
    actionLabel: String? = null
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                value ?: "--",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (action != null && actionLabel != null) {
                TextButton(onClick = action, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(actionLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun LessonTimelineItem(lesson: CourseLesson) {
    val color = courseColor(lesson.courseName)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(lesson.courseName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(lesson.place, lesson.teacher).joinToString(" · ").ifBlank { "地点待定" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${TimeUtil.hhmm(lesson.startTime) ?: "--"}-${TimeUtil.hhmm(lesson.endTime) ?: "--"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "第${lesson.startUnit}-${lesson.endUnit}节",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
