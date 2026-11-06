package com.zzu.lite.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzu.lite.LocalAppContainer
import com.zzu.lite.data.remote.dto.SemesterDto
import com.zzu.lite.data.repo.ScheduleRepository
import com.zzu.lite.domain.model.CourseLesson
import com.zzu.lite.domain.model.TeachingWeeks
import com.zzu.lite.domain.util.TimeUtil
import com.zzu.lite.ui.common.VmFactory
import com.zzu.lite.ui.theme.courseColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TimetableUiState(
    val loading: Boolean = false,
    val semesters: List<SemesterDto> = emptyList(),
    val semester: SemesterDto? = null,
    val weeks: TeachingWeeks? = null,
    val currentWeek: Int? = null,
    val selectedWeek: Int = 1,
    val fromCache: Boolean = false,
    val error: String? = null
)

class TimetableViewModel(private val containerProvider: () -> com.zzu.lite.di.AppContainer) : ViewModel() {

    private val container by lazy { containerProvider() }

    private val _state = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = _state

    fun load(forceRefresh: Boolean = false, semesterId: Int? = null) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val data = container.scheduleRepository.load(forceRefresh = forceRefresh, semesterId = semesterId)
                val currentWeek = runCatching {
                    container.scheduleRepository.currentWeekIndex(data.semester)
                }.getOrNull()
                val defaultWeek = currentWeek ?: 1
                _state.update {
                    it.copy(
                        loading = false,
                        semesters = data.semesters,
                        semester = data.semester,
                        weeks = data.weeks,
                        currentWeek = currentWeek,
                        selectedWeek = defaultWeek.coerceAtMost(data.weeks.totalWeeks.coerceAtLeast(1)),
                        fromCache = data.fromCache
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun selectSemester(id: Int) {
        if (_state.value.semester?.id == id) return
        load(forceRefresh = false, semesterId = id)
        viewModelScope.launch { runCatching { container.cacheStore.saveSemesterId(id) } }
    }

    fun selectWeek(week: Int) {
        val total = _state.value.weeks?.totalWeeks ?: return
        _state.update { it.copy(selectedWeek = week.coerceIn(1, total)) }
    }
}

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen() {
    val container = LocalAppContainer.current
    val vm: TimetableViewModel = viewModel(factory = VmFactory.of { TimetableViewModel { container } })
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.load(forceRefresh = false) }

    var semesterMenu by remember { mutableStateOf(false) }
    var weekMenu by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<CourseLesson?>(null) }

    val weeks = state.weeks
    val pagerState = rememberPagerState(
        initialPage = (state.selectedWeek - 1).coerceAtLeast(0),
        pageCount = { weeks?.totalWeeks ?: 1 }
    )

    // 周次选择与滑页同步
    LaunchedEffect(state.selectedWeek) {
        val page = (state.selectedWeek - 1).coerceAtLeast(0)
        if (pagerState.currentPage != page && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(page)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        val target = pagerState.currentPage + 1
        if (target != state.selectedWeek) vm.selectWeek(target)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 学期选择
                        TextButton(onClick = { semesterMenu = true }) {
                            Text(
                                state.semester?.nameZh ?: state.semester?.schoolYear ?: "选择学期",
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(150.dp)
                            )
                        }
                        DropdownMenu(expanded = semesterMenu, onDismissRequest = { semesterMenu = false }) {
                            state.semesters.forEach { sem ->
                                DropdownMenuItem(
                                    text = { Text(sem.nameZh ?: "${sem.schoolYear}(${sem.id})") },
                                    onClick = {
                                        semesterMenu = false
                                        vm.selectSemester(sem.id)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        // 周次选择
                        TextButton(onClick = { weekMenu = true }) {
                            Text("第 ${state.selectedWeek} 周", fontWeight = FontWeight.Bold)
                        }
                        DropdownMenu(expanded = weekMenu, onDismissRequest = { weekMenu = false }) {
                            val total = weeks?.totalWeeks ?: 0
                            (1..total).forEach { w ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "第 $w 周" + if (w == state.currentWeek) "（本周）" else ""
                                        )
                                    },
                                    onClick = {
                                        weekMenu = false
                                        vm.selectWeek(w)
                                    }
                                )
                            }
                        }
                        if (state.fromCache) {
                            Text("缓存", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load(forceRefresh = true, semesterId = state.semester?.id) }) {
                        if (state.loading) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("刷新", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.error != null && weeks == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.error ?: "", textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { vm.load(forceRefresh = true) }) { Text("重试") }
                }
            } else if (weeks != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (pagerState.currentPage > 0) scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一周") }
                    Spacer(Modifier.weight(1f))
                    state.currentWeek?.let {
                        Text("今天是第 $it 周", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        if (pagerState.currentPage < weeks.totalWeeks - 1) scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一周") }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val weekIndex = page + 1
                    val week = weeks.week(weekIndex)
                    val semester = state.semester
                    TimetableGrid(
                        week = week,
                        weekIndex = weekIndex,
                        semesterStart = semester?.startDate,
                        isCurrentWeek = weekIndex == state.currentWeek,
                        onLessonClick = { detail = it }
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    detail?.let { lesson ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
            title = { Text(lesson.courseName) },
            text = {
                Column {
                    DetailRow("时间", "${lesson.startTime?.let(TimeUtil::hhmm) ?: "--"} - ${lesson.endTime?.let(TimeUtil::hhmm) ?: "--"}")
                    DetailRow("节次", "第 ${lesson.startUnit} - ${lesson.endUnit} 节")
                    DetailRow("地点", lesson.place ?: "待定")
                    DetailRow("教师", lesson.teacher ?: "--")
                    DetailRow("周次", "第 ${lesson.weekIndex} 周 · 星期${WEEKDAY_LABELS.getOrElse(lesson.weekday - 1) { "?" }}")
                    lesson.date?.let { DetailRow("日期", it) }
                    lesson.credits?.let { DetailRow("学分", "$it") }
                    lesson.lessonType?.let { DetailRow("类型", lessonTypeLabel(it)) }
                    lesson.state?.let { DetailRow("状态", it) }
                }
            }
        )
    }
}

private fun lessonTypeLabel(raw: String): String = when (raw.uppercase()) {
    "THEORY" -> "理论课"
    "EXPERIMENT" -> "实验课"
    "PRACTICE" -> "实践课"
    else -> raw
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 单周课表网格：左侧节次列 + 7 天列；课程块按起止节次纵向占格。
 */
@Composable
private fun TimetableGrid(
    week: com.zzu.lite.domain.model.TeachingWeek?,
    weekIndex: Int,
    semesterStart: String?,
    isCurrentWeek: Boolean,
    onLessonClick: (CourseLesson) -> Unit
) {
    val unitCount = week?.unitCount ?: 10
    val cellHeight = 58.dp
    val startDate = TimeUtil.parseDate(semesterStart)
    val today = LocalDate.now()
    val todayWeekday = today.dayOfWeek.value
    val todayInThisWeek = isCurrentWeek

    val gridScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(gridScroll)
            .padding(horizontal = 6.dp)
    ) {
        // 星期表头（含日期）
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.width(26.dp)) { }
            repeat(7) { idx ->
                val weekday = idx + 1
                val date = TimeUtil.dateOfWeek(startDate, weekIndex, weekday)
                val isToday = todayInThisWeek && weekday == todayWeekday
                Column(
                    modifier = Modifier.weight(1f).padding(1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "周${WEEKDAY_LABELS[idx]}",
                        fontSize = 11.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "",
                        fontSize = 9.sp,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            // 节次列
            Column(modifier = Modifier.width(26.dp)) {
                repeat(unitCount) { u ->
                    Box(
                        modifier = Modifier.height(cellHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${u + 1}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // 7 天列
            Row(modifier = Modifier.weight(1f)) {
                repeat(7) { idx ->
                    val weekday = idx + 1
                    DayColumn(
                        modifier = Modifier.weight(1f),
                        week = week,
                        weekday = weekday,
                        unitCount = unitCount,
                        cellHeight = cellHeight,
                        isToday = todayInThisWeek && weekday == todayWeekday,
                        onLessonClick = onLessonClick
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DayColumn(
    modifier: Modifier = Modifier,
    week: com.zzu.lite.domain.model.TeachingWeek?,
    weekday: Int,
    unitCount: Int,
    cellHeight: androidx.compose.ui.unit.Dp,
    isToday: Boolean,
    onLessonClick: (CourseLesson) -> Unit
) {
    Box(
        modifier = modifier
            .height(cellHeight * unitCount)
            .background(
                if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface
            )
    ) {
        // 背景格子线
        Column {
            repeat(unitCount) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cellHeight)
                        .padding(0.5.dp)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )
            }
        }
        // 课程块（起止节次占格）
        (1..unitCount).forEach { unit ->
            val lesson = week?.get(weekday, unit)
            if (lesson != null && lesson.startUnit == unit) {
                val span = (lesson.endUnit - lesson.startUnit + 1).coerceAtLeast(1)
                val color = courseColor(lesson.courseName)
                Surface(
                    color = color.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(1.dp)
                        .height(cellHeight * span - 2.dp)
                        .offset(y = cellHeight * (unit - 1))
                        .clickable { onLessonClick(lesson) }
                ) {
                    Column(modifier = Modifier.padding(3.dp)) {
                        Text(
                            lesson.courseName,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = color,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        lesson.place?.let {
                            Text(
                                it,
                                fontSize = 8.sp,
                                lineHeight = 10.sp,
                                color = color.copy(alpha = 0.85f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
