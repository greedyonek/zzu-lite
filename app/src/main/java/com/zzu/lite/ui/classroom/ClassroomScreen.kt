package com.zzu.lite.ui.classroom

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzu.lite.LocalAppContainer
import com.zzu.lite.data.remote.dto.BuildingDto
import com.zzu.lite.data.remote.dto.CampusDto
import com.zzu.lite.data.remote.dto.CourseUnitDto
import com.zzu.lite.data.repo.ClassroomRepository
import com.zzu.lite.domain.util.FriendlyNetwork
import com.zzu.lite.domain.util.TimeUtil
import com.zzu.lite.ui.common.VmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class ClassroomUiState(
    val loading: Boolean = false,
    val querying: Boolean = false,
    val campuses: List<CampusDto> = emptyList(),
    val selectedCampusId: Int? = null,
    val buildings: List<BuildingDto> = emptyList(),
    /** 空集合 = 全部楼栋；北核/南核等分组展开成楼栋 ID 集合 */
    val selectedBuildingIds: Set<Int> = emptySet(),
    val northGroupIds: Set<Int> = emptySet(),
    val southGroupIds: Set<Int> = emptySet(),
    val units: List<CourseUnitDto> = emptyList(),
    val selectedUnits: Set<Int> = emptySet(),
    val dateOffsetDays: Int = 0,
    val result: ClassroomRepository.RoomQueryResult? = null,
    val error: String? = null,
    /** 查询成功后自动收起条件区，顶部栏可重新展开 */
    val showFilters: Boolean = true
)

class ClassroomViewModel(private val containerProvider: () -> com.zzu.lite.di.AppContainer) : ViewModel() {

    private val container by lazy { containerProvider() }

    private val _state = MutableStateFlow(ClassroomUiState())
    val state: StateFlow<ClassroomUiState> = _state

    fun update(block: (ClassroomUiState) -> ClassroomUiState) = _state.update(block)

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val campuses = container.classroomRepository.campuses()
                val units = container.classroomRepository.courseUnits()
                val defaultCampus = campuses.firstOrNull()?.id
                val buildings = defaultCampus?.let { container.classroomRepository.buildings(it) }.orEmpty()
                // 默认选中当前/下一节（按大节：起始节属于哪个大节就选整节组）
                val now = LocalTime.now()
                val defaultUnit = units.firstOrNull { u ->
                    val s = TimeUtil.parseHhmm(TimeUtil.hhmm(u.startTime))
                    val e = TimeUtil.parseHhmm(TimeUtil.hhmm(u.endTime))
                    s != null && e != null && now < e
                }?.indexNo ?: units.firstOrNull()?.indexNo
                _state.update {
                    it.copy(
                        loading = false,
                        campuses = campuses,
                        selectedCampusId = defaultCampus,
                        buildings = buildings,
                        northGroupIds = groupIds(buildings, "北"),
                        southGroupIds = groupIds(buildings, "南"),
                        units = units,
                        selectedUnits = bigUnit(defaultUnit)
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = (e as? com.zzu.lite.domain.NetworkException)?.message ?: e.message ?: "加载失败")
                }
            }
        }
    }

    /** 北1/南2 之类命名的楼栋分别聚成"北核/南核" */
    private fun groupIds(buildings: List<BuildingDto>, prefix: String): Set<Int> =
        buildings.filter { b ->
            b.nameZh?.let { it.startsWith(prefix) && it.length >= 2 && it[1].isDigit() } == true
        }.map { it.id }.toSet()

    /** 单节号 -> 所在大节（1-2/3-4/5-6/7-8/9-10）的整组 */
    private fun bigUnit(unitNo: Int?): Set<Int> {
        if (unitNo == null) return emptySet()
        val start = ((unitNo - 1) / 2) * 2 + 1
        return setOf(start, start + 1)
    }

    fun selectCampus(id: Int) {
        if (_state.value.selectedCampusId == id) return
        _state.update {
            it.copy(
                selectedCampusId = id,
                selectedBuildingIds = emptySet(),
                buildings = emptyList(),
                northGroupIds = emptySet(),
                southGroupIds = emptySet(),
                result = null,
                showFilters = true
            )
        }
        viewModelScope.launch {
            try {
                val buildings = container.classroomRepository.buildings(id)
                _state.update {
                    it.copy(
                        buildings = buildings,
                        northGroupIds = groupIds(buildings, "北"),
                        southGroupIds = groupIds(buildings, "南")
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "获取楼栋失败") }
            }
        }
    }

    fun query() {
        val s = _state.value
        if (s.querying) return
        val campusId = s.selectedCampusId ?: run {
            _state.update { it.copy(error = "请先选择校区") }
            return
        }
        if (s.selectedUnits.isEmpty()) {
            _state.update { it.copy(error = "请选择节次") }
            return
        }
        val date = LocalDate.now().plusDays(s.dateOffsetDays.toLong()).toString()
        _state.update { it.copy(querying = true, error = null) }
        viewModelScope.launch {
            try {
                val result = container.classroomRepository.availableRooms(
                    date = date,
                    campusId = campusId,
                    buildingIds = s.selectedBuildingIds.ifEmpty { null },
                    unitNumbers = s.selectedUnits
                )
                _state.update { it.copy(querying = false, result = result, showFilters = false) }
            } catch (e: Exception) {
                val msg = when (e) {
                    is com.zzu.lite.domain.NetworkException -> e.message ?: "网络失败"
                    is com.zzu.lite.domain.ZzuException -> e.message ?: "查询失败"
                    else -> FriendlyNetwork.wrap(e, "查询空教室").message
                }
                _state.update { it.copy(querying = false, error = msg) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomScreen() {
    val container = LocalAppContainer.current
    val vm: ClassroomViewModel = viewModel(factory = VmFactory.of { ClassroomViewModel { container } })
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("空教室", fontWeight = FontWeight.Bold)
                        state.result?.let { r ->
                            Text(
                                "${r.date} · ${r.available.size} 间空闲",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { vm.update { it.copy(showFilters = !it.showFilters) } }) {
                        Text(if (state.showFilters) "收起条件" else "筛选")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (state.loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
                return@Column
            }

            if (state.showFilters) {
                FilterPanel(state, vm)
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            val result = state.result
            if (result != null && !state.showFilters) {
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (result.available.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            "该时段没有符合条件的空教室，点右上角「筛选」换个条件试试",
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    ResultGroups(result)
                }
            } else if (result != null && state.showFilters) {
                Text(
                    "已出结果：${result.available.size} 间空闲，点右上角「收起条件」查看",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FilterPanel(state: ClassroomUiState, vm: ClassroomViewModel) {
    Column {
        // 校区
        Text("校区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.campuses.forEach { campus ->
                FilterChip(
                    selected = state.selectedCampusId == campus.id,
                    onClick = { vm.selectCampus(campus.id) },
                    label = { Text(campus.nameZh ?: "校区${campus.id}") }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // 楼栋（含北核/南核分组）
        if (state.buildings.isNotEmpty()) {
            Text("楼栋（不限则不选）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.selectedBuildingIds.isEmpty(),
                    onClick = { vm.update { it.copy(selectedBuildingIds = emptySet(), result = null) } },
                    label = { Text("全部") }
                )
                if (state.northGroupIds.isNotEmpty()) {
                    FilterChip(
                        selected = state.selectedBuildingIds == state.northGroupIds,
                        onClick = { vm.update { it.copy(selectedBuildingIds = state.northGroupIds, result = null) } },
                        label = { Text("北核") }
                    )
                }
                if (state.southGroupIds.isNotEmpty()) {
                    FilterChip(
                        selected = state.selectedBuildingIds == state.southGroupIds,
                        onClick = { vm.update { it.copy(selectedBuildingIds = state.southGroupIds, result = null) } },
                        label = { Text("南核") }
                    )
                }
                state.buildings.forEach { b ->
                    FilterChip(
                        selected = state.selectedBuildingIds == setOf(b.id),
                        onClick = { vm.update { it.copy(selectedBuildingIds = setOf(b.id), result = null) } },
                        label = { Text(b.nameZh ?: "${b.id}") }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // 日期
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.dateOffsetDays == 0,
                onClick = { vm.update { it.copy(dateOffsetDays = 0, result = null) } },
                label = { Text("今天") }
            )
            FilterChip(
                selected = state.dateOffsetDays == 1,
                onClick = { vm.update { it.copy(dateOffsetDays = 1, result = null) } },
                label = { Text("明天") }
            )
        }

        Spacer(Modifier.height(8.dp))
        // 大节（两节连上）：1-2 / 3-4 / 5-6 / 7-8 / 9-10
        Text("节次（可多选）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.units.sortedBy { it.indexNo }.chunked(2).forEach { pair ->
                val first = pair.first()
                val second = pair.getOrNull(1)
                val label = if (second != null) "${first.indexNo}-${second.indexNo}节" else "${first.indexNo}节"
                val timeRange = TimeUtil.hhmm(first.startTime) + "~" + (second?.let { TimeUtil.hhmm(it.endTime) } ?: TimeUtil.hhmm(first.endTime))
                val unitNos = pair.map { it.indexNo }.toSet()
                FilterChip(
                    selected = state.selectedUnits.containsAll(unitNos),
                    onClick = {
                        vm.update {
                            val sel = it.selectedUnits.toMutableSet()
                            if (sel.containsAll(unitNos)) sel.removeAll(unitNos) else sel.addAll(unitNos)
                            it.copy(selectedUnits = sel, result = null)
                        }
                    },
                    label = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label)
                            Text(timeRange, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }
        val selInfo = state.units.filter { it.indexNo in state.selectedUnits }
            .joinToString("、") { "${it.indexNo}" }
        Text(
            "已选节次：${selInfo.ifBlank { "无" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.query() },
            enabled = !state.querying,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state.querying) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("查询中…")
            } else {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("查询空教室")
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** 结果按楼栋（教室所在地）分组展示 */
@Composable
private fun ResultGroups(result: ClassroomRepository.RoomQueryResult) {
    val grouped = result.available.groupBy { it.buildingName ?: "其他" }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        grouped.forEach { (building, rooms) ->
            item(key = "header_$building") {
                Text(
                    "$building · ${rooms.size} 间",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(rooms.size, key = { rooms[it].room.id }) { idx ->
                AvailableRoomCard(rooms[idx])
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AvailableRoomCard(item: ClassroomRepository.AvailableRoom) {
    val room = item.room
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    listOfNotNull(item.buildingName, room.nameZh).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val extra = listOfNotNull(
                    room.campusNameZh,
                    room.floor?.takeIf { it > 0 }?.let { "${it}层" },
                    room.seatsForLesson?.takeIf { it > 0 }?.let { "容量 $it" }
                ).joinToString(" · ")
                if (extra.isNotBlank()) {
                    Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "空闲",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
