package com.example.myapplication.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.di.DeviceTransport
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.WarningRed
import kotlinx.coroutines.launch

/**
 * 登录页（主流程第一站，位于设备连接页之前）。
 *
 * - 联调模式（TRANSPORT=SERVER）：调用服务端 `POST /api/v1/auth/login` 获取 token，
 *   设备信息按登录用户获取（见 docs/后端接口设计文档.md 第 3/4 节）。
 * - **调试默认账号 `demo / 123456` 已预填**（Mock 服务端内置并绑定两台演示设备）。
 * - 「跳过登录」保留：不取 token，Mock 服务端按默认用户 demo 处理（正式服务端会拒绝无鉴权请求）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sl = remember(context) { ServiceLocator.get(context) }
    val auth = sl.authRepository
    val serverMode = sl.transport == DeviceTransport.SERVER

    // 服务端地址可运行时更改（设置 → 服务器地址），这里订阅它以跟随变化
    val serverUrl by sl.settingsRepository.serverBaseUrl
        .collectAsStateWithLifecycle(initialValue = sl.serverBaseUrl)

    // 调试默认账号（正式账号接入后替换预填值即可）
    var username by remember { mutableStateOf("demo") }
    var password by remember { mutableStateOf("123456") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("登录")

        Spacer(Modifier.height(8.dp))
        Text(
            text = "智能道路作业",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "请登录后选择作业类型（三脚架布设 / 锥桶协同）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        if (serverMode) {
            StatusChip("已接入服务端登录 · 设备按账号获取", InfoBlue, InfoBlueBg)
        } else {
            StatusChip("演示状态 · 未接入账号系统", InfoBlue, InfoBlueBg)
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Text(
                text = error!!,
                style = MaterialTheme.typography.bodySmall,
                color = WarningRed,
            )
        }

        Spacer(Modifier.height(8.dp))

        PrimaryActionButton(
            text = if (loading) "登录中…" else "登录",
            onClick = {
                error = null
                loading = true
                scope.launch {
                    auth.login(username.trim(), password)
                        .onSuccess {
                            loading = false
                            onLoggedIn()
                        }
                        .onFailure { e ->
                            loading = false
                            error = "登录失败：${e.message ?: "网络异常"}"
                        }
                }
            },
            enabled = !loading && username.isNotBlank() && password.isNotBlank(),
        )

        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("跳过登录（演示模式）")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = if (serverMode) {
                "调试默认账号：demo / 123456（已预填，Mock 服务端内置）。\n" +
                    "设备信息按登录账号获取；跳过登录时服务端按默认账号处理。\n" +
                    "当前服务端：$serverUrl（可在「设置 → 服务器地址」修改）"
            } else {
                "当前为演示模式（模拟设备），无需真实账号；\n" +
                    "切到联调模式（TRANSPORT = SERVER）后启用服务端登录。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
