package com.zzu.lite.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzu.lite.LocalAppContainer
import com.zzu.lite.data.remote.PortalInfo
import com.zzu.lite.data.repo.ECardRepository
import com.zzu.lite.data.repo.RoomEnergy
import com.zzu.lite.domain.PortalNotDetectedException
import com.zzu.lite.domain.util.TimeUtil
import com.zzu.lite.ui.common.VmFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServicesUiState(
    // 校园网
    val portalState: PortalState = PortalState.Idle,
    val portalAccount: String = "",
    val portalPassword: String = "",
    val ispSuffix: String = "",
    val portalMessage: String? = null,
    // 电费
    val energyLoading: Boolean = false,
    val room: String? = null,
    val energy: RoomEnergy? = null,
    val energyError: String? = null,
    // 房间级联选择
    val roomPicker: RoomPickerState? = null,
    // 其他
    val busy: Boolean = false,
    val energyAlertEnabled: Boolean = true
)

sealed class PortalState {
    data object Idle : PortalState()
    data object Detecting : PortalState()
    data object NotCampus : PortalState()
    data class Ready(val info: PortalInfo) : PortalState()
    data object Authenticating : PortalState()
}

data class RoomPickerState(
    val options: List<ECardRepository.RoomOption> = emptyList(),
    val selected: List<String> = emptyList(),  // 逐级选中的 id：area, building, level...
    val loading: Boolean = false
)

class ServicesViewModel(private val containerProvider: () -> com.zzu.lite.di.AppContainer) : ViewModel() {

    private val container by lazy { containerProvider() }
    private val _state = MutableStateFlow(ServicesUiState())
    val state: StateFlow<ServicesUiState> = _state

    init {
        // 预填校园网认证账号（与统一认证一致）
        val store = container.tokenStore
        _state.update {
            it.copy(
                portalAccount = store.username.orEmpty(),
                portalPassword = if (store.rememberPassword) store.password.orEmpty() else ""
            )
        }
        // 读取电费提醒开关
        viewModelScope.launch {
            val enabled = runCatching { container.cacheStore.energyAlertEnabled() }.getOrDefault(true)
            _state.update { it.copy(energyAlertEnabled = enabled) }
        }
    }

    fun update(block: (ServicesUiState) -> ServicesUiState) = _state.update(block)

    // ---------- 校园网 ----------

    /** 探测：连接校园网未认证时会定位到网关；已认证/非校园网提示 NotCampus */
    fun detectPortal(autoAuth: Boolean = false) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, portalState = PortalState.Detecting, portalMessage = null) }
        viewModelScope.launch {
            try {
                val info = container.portalClient.discover()
                _state.update { it.copy(busy = false, portalState = PortalState.Ready(info)) }
                if (autoAuth) authenticate()
            } catch (e: PortalNotDetectedException) {
                _state.update {
                    it.copy(busy = false, portalState = PortalState.NotCampus, portalMessage = "当前网络无需认证（已在线或不在校园网内）")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, portalState = PortalState.Idle, portalMessage = "探测失败：${e.message}")
                }
            }
        }
    }

    fun authenticate() {
        val s = _state.value
        val info = (s.portalState as? PortalState.Ready)?.info ?: return
        if (s.busy) return
        if (s.portalAccount.isBlank() || s.portalPassword.isBlank()) {
            _state.update { it.copy(portalMessage = "请填写校园网账号和密码") }
            return
        }
        _state.update { it.copy(busy = true, portalState = PortalState.Authenticating, portalMessage = null) }
        viewModelScope.launch {
            try {
                val result = container.portalClient.auth(info, s.portalAccount.trim(), s.portalPassword, s.ispSuffix.ifBlank { null })
                if (result.success) {
                    _state.update { it.copy(busy = false, portalState = PortalState.NotCampus, portalMessage = "认证成功：${result.msg ?: "已上线"}") }
                } else {
                    _state.update { it.copy(busy = false, portalState = PortalState.Ready(info), portalMessage = "认证失败：${result.msg ?: "未知原因"}") }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, portalState = PortalState.Ready(info), portalMessage = "认证请求失败：${e.message}")
                }
            }
        }
    }

    // ---------- 电费 ----------

    fun loadEnergy() {
        if (_state.value.energyLoading) return
        _state.update { it.copy(energyLoading = true, energyError = null) }
        viewModelScope.launch {
            try {
                val room = container.ecardRepository.currentRoom()
                val energy = container.ecardRepository.roomEnergy(room)
                container.publishRoomEnergy(energy)
                _state.update { it.copy(energyLoading = false, room = room, energy = energy) }
            } catch (e: Exception) {
                _state.update { it.copy(energyLoading = false, energyError = e.message ?: "查询失败") }
            }
        }
    }

    /** 打开房间级联选择器（从区域开始） */
    fun openRoomPicker() {
        _state.update { it.copy(roomPicker = RoomPickerState(loading = true)) }
        viewModelScope.launch {
            try {
                val options = container.ecardRepository.roomOptions("")
                _state.update { it.copy(roomPicker = RoomPickerState(options = options)) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(roomPicker = null, energyError = "加载区域失败：${e.message}")
                }
            }
        }
    }

    /** 级联下钻；当下一级无子项时视为最终选择。房间 ID 拼接规则：area-building--level-room */
    fun pickRoomOption(option: ECardRepository.RoomOption) {
        val picker = _state.value.roomPicker ?: return
        val selected = picker.selected + option.id
        val roomId = buildRoomId(selected)
        _state.update { it.copy(roomPicker = picker.copy(selected = selected, loading = true)) }
        viewModelScope.launch {
            try {
                if (selected.size >= 4) {
                    // 到底：保存绑定并查询
                    container.cacheStore.saveRoom(roomId)
                    val energy = runCatching { container.ecardRepository.roomEnergy(roomId) }.getOrNull()
                    _state.update {
                        it.copy(
                            roomPicker = null,
                            room = roomId,
                            energy = energy,
                            energyError = if (energy == null) "已绑定房间 $roomId，但查询失败，请稍后重试" else null
                        )
                    }
                    return@launch
                }
                val next = container.ecardRepository.roomOptions(roomId)
                if (next.isEmpty()) {
                    // 该层级没有下一级：把当前 roomId 当作最终房间
                    container.cacheStore.saveRoom(roomId)
                    val energy = runCatching { container.ecardRepository.roomEnergy(roomId) }.getOrNull()
                    _state.update {
                        it.copy(
                            roomPicker = null,
                            room = roomId,
                            energy = energy,
                            energyError = if (energy == null) "已绑定房间 $roomId，但查询失败，请稍后重试" else null
                        )
                    }
                } else {
                    _state.update { it.copy(roomPicker = RoomPickerState(options = next, selected = selected)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(roomPicker = picker.copy(loading = false), energyError = "查询失败：${e.message}") }
            }
        }
    }

    fun closeRoomPicker() = _state.update { it.copy(roomPicker = null) }

    /**
     * 拼接房间 ID：area-building--level-room。
     * 服务端各级可能返回裸段 ID（"97"）也可能返回完整路径 ID（"99-97--189"），
     * 含 "--" 的一律视为完整房间 ID 直接使用，避免重复拼接（实测踩坑）。
     */
    private fun buildRoomId(selected: List<String>): String {
        if (selected.isEmpty()) return ""
        val last = selected.last()
        if (selected.size >= 2 && last.contains("--")) return last
        val base = when {
            selected.size >= 2 && selected[1].contains("-") -> selected[1]
            selected.size >= 2 -> "${selected[0]}-${selected[1]}"
            else -> selected[0]
        }
        return when {
            selected.size == 1 -> selected[0]
            selected.size == 2 -> base
            else -> {
                val level = selected[2]
                val withLevel = if (level.contains("--")) level else "$base--$level"
                if (selected.size >= 4) {
                    val roomPart = selected[3]
                    if (roomPart.contains("--")) roomPart else "$withLevel-$roomPart"
                } else {
                    withLevel
                }
            }
        }
    }

    // ---------- 设置 ----------

    fun logout() {
        container.logout()
    }

    fun clearTimetableCache() {
        viewModelScope.launch {
            runCatching { container.cacheStore.clearTimetable() }
        }
    }

    /** 电费不足提醒开关：持久化 + 增删后台巡检任务 */
    fun setEnergyAlert(enabled: Boolean) {
        _state.update { it.copy(energyAlertEnabled = enabled) }
        viewModelScope.launch {
            runCatching {
                container.cacheStore.setEnergyAlertEnabled(enabled)
                val ctx = container.appContext()
                if (enabled) {
                    com.zzu.lite.notify.EnergyCheckScheduler.ensure(ctx)
                } else {
                    com.zzu.lite.notify.EnergyCheckScheduler.cancel(ctx)
                }
            }
        }
    }
}

private val ISP_OPTIONS = listOf("" to "校园网", "@cmcc" to "移动", "@unicom" to "联通", "@telecom" to "电信")

@Composable
fun ServicesScreen() {
    val container = LocalAppContainer.current
    val vm: ServicesViewModel = viewModel(factory = VmFactory.of { ServicesViewModel { container } })
    val state by vm.state.collectAsState()

    // 首次进入自动探测校园网状态
    LaunchedEffect(Unit) {
        if (state.portalState is PortalState.Idle) vm.detectPortal()
        vm.loadEnergy()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("服务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        PortalCard(state, vm)
        Spacer(Modifier.height(12.dp))
        EnergyCard(state, vm)
        Spacer(Modifier.height(12.dp))
        SettingsCard(state, vm)
        Spacer(Modifier.height(24.dp))
    }

    state.roomPicker?.let { picker ->
        AlertDialog(
            onDismissRequest = { vm.closeRoomPicker() },
            confirmButton = {
                TextButton(onClick = { vm.closeRoomPicker() }) { Text("取消") }
            },
            title = { Text("选择宿舍房间") },
            text = {
                if (picker.loading) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "已选：${picker.selected.joinToString(" / ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        picker.options.forEach { opt ->
                            TextButton(
                                onClick = { vm.pickRoomOption(opt) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(opt.name, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                            }
                        }
                        if (picker.options.isEmpty()) {
                            Text("没有更多选项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun PortalCard(state: ServicesUiState, vm: ServicesViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("校园网", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                when (val ps = state.portalState) {
                    is PortalState.Idle -> StatusDot(MaterialTheme.colorScheme.outline)
                    is PortalState.Detecting, is PortalState.Authenticating ->
                        CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    is PortalState.NotCampus -> StatusDot(MaterialTheme.colorScheme.primary, "已在线")
                    is PortalState.Ready -> StatusDot(MaterialTheme.colorScheme.error, "待认证")
                }
            }
            Spacer(Modifier.height(8.dp))
            state.portalMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = state.portalAccount,
                onValueChange = { v -> vm.update { it.copy(portalAccount = v) } },
                label = { Text("校园网账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.portalPassword,
                onValueChange = { v -> vm.update { it.copy(portalPassword = v) } },
                label = { Text("校园网密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ISP_OPTIONS.forEach { (suffix, label) ->
                    FilterChip(
                        selected = state.ispSuffix == suffix,
                        onClick = { vm.update { it.copy(ispSuffix = suffix) } },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.detectPortal() }, enabled = !state.busy) {
                    Text("重新探测")
                }
                Button(
                    onClick = {
                        when (state.portalState) {
                            is PortalState.Ready -> vm.authenticate()
                            else -> vm.detectPortal(autoAuth = true)
                        }
                    },
                    enabled = !state.busy
                ) { Text("一键认证") }
            }
        }
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color, label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .width(8.dp)
                .height(8.dp)
                .background(color = color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        label?.let {
            Spacer(Modifier.width(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EnergyCard(state: ServicesUiState, vm: ServicesViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("宿舍电费", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (state.energyLoading) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val energy = state.energy
            if (energy != null) {
                val primary = energy.templates.firstOrNull {
                    it.label.contains("电量") || it.label.contains("quantity", ignoreCase = true)
                } ?: energy.templates.firstOrNull()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        primary?.valueText ?: "--",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                energy.templates.filter { it != primary }.forEach { t ->
                    Text(
                        "${t.label}：${t.valueText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "房间 ${energy.room}" + (energy.accountName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    state.energyError ?: "尚未获取到房间信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadEnergy() }, enabled = !state.energyLoading) { Text("刷新") }
                OutlinedButton(onClick = { vm.openRoomPicker() }, enabled = !state.energyLoading) { Text("改绑房间") }
            }
        }
    }
}

@Composable
private fun SettingsCard(state: ServicesUiState, vm: ServicesViewModel) {
    var confirmLogout by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("电费不足提醒", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "低于 10 元时通知（每小时检查一次）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = state.energyAlertEnabled,
                    onCheckedChange = { enabled ->
                        vm.setEnergyAlert(enabled)
                        if (enabled && !notifGranted && android.os.Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
            if (state.energyAlertEnabled && !notifGranted) {
                TextButton(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) {
                    Text("通知权限未授予，点此开启", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { vm.clearTimetableCache() }) { Text("清除课表缓存") }
            TextButton(onClick = { confirmLogout = true }) { Text("退出登录") }
            Spacer(Modifier.height(4.dp))
            Text(
                "ZZU_Lite v1.1 · 接口实现参考开源项目 ZZU.Py（MIT）\n凭据仅保存在本机加密存储中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("将清除本机保存的账号、密码与登录凭据，确认退出？") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } }
        )
    }
}
