package com.zzu.lite.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzu.lite.LocalAppContainer
import com.zzu.lite.ui.common.VmFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val remember: Boolean = true,
    val loading: Boolean = false,
    val needSms: Boolean = false,
    val smsCode: String = "",
    val cooldown: Int = 0,
    val error: String? = null
)

class LoginViewModel(private val containerProvider: () -> com.zzu.lite.di.AppContainer) : ViewModel() {

    private val container by lazy { containerProvider() }
    private val repo by lazy { container.authRepository }

    private val _state = MutableStateFlow(
        LoginUiState(
            username = container.tokenStore.username.orEmpty(),
            remember = container.tokenStore.rememberPassword
        )
    )
    val state: StateFlow<LoginUiState> = _state

    fun update(block: (LoginUiState) -> LoginUiState) = _state.update(block)

    /** 登录按钮：无 MFA 直接登录；有 MFA 进入验证码阶段（自动发码） */
    fun submit() {
        val s = _state.value
        if (s.loading) return
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "请输入学号和密码") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val needMfa = repo.beginLogin(s.username.trim(), s.password)
                if (needMfa) {
                    _state.update { it.copy(loading = false, needSms = true, cooldown = 60) }
                    startCooldown()
                } else {
                    finishLogin(s)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "登录失败") }
            }
        }
    }

    /** 校验验证码并完成登录 */
    fun verify() {
        val s = _state.value
        if (s.loading) return
        val code = s.smsCode.trim()
        if (code.length < 4) {
            _state.update { it.copy(error = "请输入收到的验证码") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                repo.verifySms(code)
                finishLogin(s)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "验证失败") }
            }
        }
    }

    /** 重发验证码 */
    fun resend() {
        val s = _state.value
        if (s.cooldown > 0 || s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                repo.sendSms()
                _state.update { it.copy(loading = false, cooldown = 60, smsCode = "") }
                startCooldown()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "发送失败") }
            }
        }
    }

    private suspend fun finishLogin(s: LoginUiState) {
        try {
            // 先保存凭据（onTokensChanged 会读 username），再完成登录
            container.tokenStore.saveCredentials(s.username.trim(), s.password, s.remember)
            repo.completeLogin()
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "登录失败") }
        }
    }

    private fun startCooldown() {
        viewModelScope.launch {
            while (_state.value.cooldown > 0) {
                delay(1000)
                _state.update { it.copy(cooldown = (it.cooldown - 1).coerceAtLeast(0)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen() {
    val container = LocalAppContainer.current
    val vm: LoginViewModel = viewModel(factory = VmFactory.of { LoginViewModel { container } })
    val state by vm.state.collectAsState()
    val snackState = remember { SnackbarHostState() }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let { snackState.showSnackbar(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(48.dp))
            Text("ZZU_Lite", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("郑州大学生活服务", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))

            if (!state.needSms) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { v -> vm.update { it.copy(username = v) } },
                    label = { Text("学号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { v -> vm.update { it.copy(password = v) } },
                    label = { Text("密码（统一身份认证密码）") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = state.remember,
                        onCheckedChange = { v -> vm.update { it.copy(remember = v) } }
                    )
                    Text("记住密码（用于登录过期后自动续登）", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { vm.submit() },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("登录")
                    }
                }
            } else {
                Text("需要短信验证", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "验证码已发送至统一认证平台绑定的手机号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = state.smsCode,
                    onValueChange = { v -> vm.update { it.copy(smsCode = v.filter { c -> c.isDigit() }.take(6)) } },
                    label = { Text("短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { vm.resend() }, enabled = state.cooldown <= 0) {
                    Text(if (state.cooldown > 0) "重新发送（${state.cooldown}s）" else "重新发送验证码")
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.verify() },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("验证并登录")
                    }
                }
                TextButton(onClick = { vm.update { LoginUiState(username = it.username, remember = it.remember) } }) {
                    Text("返回重新登录")
                }
            }
            Spacer(Modifier.height(40.dp))
            Text(
                "仅本地保存凭据，不向任何第三方上传数据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
