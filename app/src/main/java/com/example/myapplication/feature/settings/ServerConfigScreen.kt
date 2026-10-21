package com.example.myapplication.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.BuildConfig
import com.example.myapplication.core.data.net.ServerProbe
import com.example.myapplication.core.data.net.ServerProbeResult
import com.example.myapplication.core.ui.MenuRow
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.permission.rememberLocalNetworkPermissionState
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.util.parseServerUrl
import com.example.myapplication.core.util.ServerUrlResult
import com.example.myapplication.di.ServerUrlApplyResult
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.feature.deployment.DeploymentViewModel
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg
import kotlinx.coroutines.launch

/**
 * 服务器地址（设置 → 服务器地址）：**运行时**切换服务端，不需要重新编译装包。
 *
 * 地址来源优先级：本页保存的值（DataStore）> 编译期默认
 * （`local.properties` 的 `SERVER_BASE_URL` → `BuildConfig.DEFAULT_SERVER_BASE_URL`）。
 *
 * 切换会产生两个副作用，都已由 [ServiceLocator.applyServerBaseUrl] 处理：
 * 旧服务端的登录会话作废（需重新登录）、当前设备连接断开。
 */
@Composable
fun ServerConfigScreen(
    vm: VehicleViewModel,
    deploymentVm: DeploymentViewModel,
    onBack: () -> Unit,
    /** 地址**确实变了**且已保存 → 由 AppNavHost 导航回登录页 */
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sl = remember(context) { ServiceLocator.get(context) }
    val probe = remember { ServerProbe() }
    // Android 17：局域网 HTTP 探测也需要本地网络权限；否则会误报「不可达」
    val localNetworkPermission = rememberLocalNetworkPermissionState()

    val currentUrl by sl.settingsRepository.serverBaseUrl
        .collectAsStateWithLifecycle(initialValue = sl.serverBaseUrl)
    val compileTimeDefault = remember {
        runCatching { BuildConfig.DEFAULT_SERVER_BASE_URL }.getOrDefault("")
    }

    var input by remember(currentUrl) { mutableStateOf(currentUrl) }
    var fieldError by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<ServerProbeResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val parsed = parseServerUrl(input)
    val normalized = (parsed as? ServerUrlResult.Ok)?.url

    val runProbe: (String) -> Unit = { url ->
        scope.launch {
            testing = true
            testResult = null
            testResult = probe.check(url)
            testing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("服务器地址", onBack = onBack)

        SectionCard(title = "当前地址") {
            Text(
                text = currentUrl,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            StatusChip(
                text = if (currentUrl == compileTimeDefault) "编译期默认" else "已自定义",
                fg = if (currentUrl == compileTimeDefault) NeutralGrey else InfoBlue,
                bg = if (currentUrl == compileTimeDefault) NeutralGreyBg else InfoBlueBg,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "编译期默认值：$compileTimeDefault\n（来自 local.properties 的 SERVER_BASE_URL）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "修改") {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    fieldError = null
                    testResult = null
                },
                label = { Text("服务端地址") },
                placeholder = { Text("192.168.43.120:8080") },
                // 允许多行粘贴，由 parseServerUrl 判定是否合法（多段会明确报错，不会猜）
                singleLine = false,
                maxLines = 3,
                isError = fieldError != null,
                supportingText = {
                    when {
                        fieldError != null -> Text(fieldError!!, color = WarningRed)
                        parsed is ServerUrlResult.Invalid -> Text(
                            parsed.reason,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        normalized != null && normalized != input -> Text(
                            "将保存为 $normalized",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton(
                    text = if (testing) "测试中…" else "测试连接",
                    onClick = {
                        val url = normalized ?: return@SecondaryActionButton
                        if (!localNetworkPermission.granted) {
                            localNetworkPermission.onRequestResult = { granted ->
                                if (granted) {
                                    runProbe(url)
                                } else {
                                    testResult = ServerProbeResult.Unreachable(
                                        "未授予本地网络权限（Android 17+）",
                                    )
                                }
                            }
                            localNetworkPermission.request()
                        } else {
                            runProbe(url)
                        }
                    },
                    enabled = !testing && !saving && normalized != null,
                    modifier = Modifier.weight(1f),
                )
                PrimaryActionButton(
                    text = if (saving) "保存中…" else "保存",
                    onClick = {
                        // 保存本身不需要网络；但保存后马上去登录/请求，先顺手申请一次权限
                        if (!localNetworkPermission.granted) {
                            localNetworkPermission.request()
                        }
                        scope.launch {
                            saving = true
                            when (sl.applyServerBaseUrl(input)) {
                                ServerUrlApplyResult.INVALID -> {
                                    fieldError = (parseServerUrl(input) as? ServerUrlResult.Invalid)?.reason
                                        ?: "地址不合法"
                                    saving = false
                                }

                                ServerUrlApplyResult.UNCHANGED -> {
                                    saving = false
                                    vm.showInfo("服务器地址", "地址未变化，已保存。")
                                }

                                ServerUrlApplyResult.CHANGED -> {
                                    // 必须在地址已生效之后：停掉旧连接、清空旧服务端设备/布设状态
                                    vm.onServerChanged()
                                    deploymentVm.onServerChanged()
                                    saving = false
                                    vm.showInfo(
                                        "服务器已切换",
                                        "已切换到：\n${sl.serverBaseUrl}\n\n" +
                                            "旧登录会话已失效，请重新登录。",
                                    )
                                    onSaved()
                                }
                            }
                        }
                    },
                    enabled = !saving && !testing && normalized != null,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            when (val r = testResult) {
                is ServerProbeResult.Ok -> StatusChip(
                    text = "已连接" + (r.version?.let { " · 服务端 $it" } ?: ""),
                    fg = SafeGreen,
                    bg = SafeGreenBg,
                )

                is ServerProbeResult.HttpError -> StatusChip(
                    text = "可达，但接口返回 HTTP ${r.code}",
                    fg = WarnOrange,
                    bg = WarnOrangeBg,
                )

                is ServerProbeResult.Unreachable -> StatusChip(
                    text = "不可达：${r.reason}",
                    fg = WarningRed,
                    bg = WarningRedBg,
                )

                null -> Unit
            }
        }

        SectionCard(title = "其它") {
            MenuRow("恢复编译期默认", onClick = {
                if (saving) return@MenuRow
                scope.launch {
                    saving = true
                    when (sl.resetServerBaseUrl()) {
                        ServerUrlApplyResult.CHANGED -> {
                            vm.onServerChanged()
                            deploymentVm.onServerChanged()
                            saving = false
                            vm.showInfo("服务器地址", "已恢复为 $compileTimeDefault")
                            onSaved()
                        }

                        else -> {
                            saving = false
                            vm.showInfo("服务器地址", "当前已是编译期默认地址。")
                        }
                    }
                }
            })
        }

        SafetyText(
            "切换服务器会断开当前设备连接并要求重新登录。\n" +
                "IP 直连（如 192.168.43.120:8080）不写 http:// 也能识别；IPv6 请写成 " +
                "[2408:xxxx::1]:8080。",
        )
        Spacer(Modifier.height(24.dp))
    }
}

