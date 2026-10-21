package com.example.myapplication.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.core.model.vehicle.LaneRange
import com.example.myapplication.core.model.vehicle.WorkRecord
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.di.UploadMode
import com.example.myapplication.core.ui.ContentCard
import com.example.myapplication.core.ui.EmptyStateCard
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.SafetyNoticeBanner
import com.example.myapplication.core.ui.BannerVariant
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.SkeletonList
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.feature.deployment.DeploymentActiveCard
import com.example.myapplication.feature.deployment.DeploymentRecordCard
import com.example.myapplication.feature.deployment.DeploymentViewModel
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg
import java.util.Locale

/**
 * 作业记录（M09）：记录开始时间、目标距离、设备结果、人工核验、异常备注。
 *
 * 顶部下拉切换两类记录：
 * - 「三角架作业记录」：本机 WorkRecord + 上传状态（原列表，不包含锥桶记录）；
 * - 「锥桶协同布置作业记录」：复用服务端现有 `listDeployments`，不新增/不修改服务端接口。
 *
 * **数据真实性**：记录始终在本机留一份；UPLOAD_MODE=HTTP 时同时上传服务端并展示服务端单号，
 * STUB 模式下标注"仅本机保存"，不得宣称已上传平台。
 */
@Composable
fun RecordsScreen(
    vm: VehicleViewModel,
    deploymentVm: DeploymentViewModel,
    onOpenDeploymentExec: (String) -> Unit,
) {
    val records by vm.records.collectAsStateWithLifecycle(initialValue = emptyList())
    val deploymentState by deploymentVm.uiState.collectAsStateWithLifecycle()
    val serverUpload = ServiceLocator.UPLOAD_MODE == UploadMode.HTTP

    var selectedTabKey by rememberSaveable { mutableStateOf(RecordTab.TRIANGLE.name) }
    val selectedTab = RecordTab.entries.firstOrNull { it.name == selectedTabKey } ?: RecordTab.TRIANGLE
    var recordMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { deploymentVm.loadActiveEvent(force = true) }
    // 切到锥桶记录页时按需拉取；不改服务端接口，复用现有 listDeployments
    LaunchedEffect(selectedTab) {
        if (selectedTab == RecordTab.CONE) deploymentVm.loadRecords()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("作业记录")

        // 记录类型下拉：三角架作业记录 / 锥桶协同布置作业记录
        Box {
            OutlinedButton(
                onClick = { recordMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selectedTab.label,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                )
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "选择记录类型")
            }
            DropdownMenu(
                expanded = recordMenuExpanded,
                onDismissRequest = { recordMenuExpanded = false },
            ) {
                RecordTab.entries.forEach { tab ->
                    DropdownMenuItem(
                        text = { Text(tab.label) },
                        trailingIcon = {
                            if (tab == selectedTab) {
                                Text("✓", color = SafeGreen, fontWeight = FontWeight.Bold)
                            }
                        },
                        onClick = {
                            selectedTabKey = tab.name
                            recordMenuExpanded = false
                        },
                    )
                }
            }
        }

        when (selectedTab) {
            RecordTab.TRIANGLE -> {
                if (records.isEmpty()) {
                    SectionCard(title = "暂无作业记录") {
                        Text(
                            "完成作业后，记录会自动保存。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(records, key = { it.id }) { record ->
                            RecordCard(record, TimeFormat.format("MM-dd HH:mm", record.startedAt))
                        }
                    }
                }

            }

            RecordTab.CONE -> {
                deploymentState.activeEvent?.let { event ->
                    DeploymentActiveCard(
                        event = event,
                        onOpen = { onOpenDeploymentExec(event.deploymentId) },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "锥桶协同布设记录",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = deploymentVm::loadRecords,
                        enabled = !deploymentState.loading,
                    ) {
                        Text(if (deploymentState.loading) "加载中…" else "刷新")
                    }
                }

                when {
                    deploymentState.loading && deploymentState.records.isEmpty() -> {
                        SkeletonList(count = 3, itemHeight = 96.dp)
                    }

                    deploymentState.error != null -> {
                        SectionCard(title = "加载失败") {
                            Text(deploymentState.error ?: "加载布设记录失败", color = WarningRed)
                            Spacer(Modifier.height(8.dp))
                            SecondaryActionButton("重试", onClick = deploymentVm::loadRecords)
                        }
                    }

                    deploymentState.records.isEmpty() -> {
                        EmptyStateCard(
                            title = "暂无锥桶布设记录",
                            message = "完成或终止布设后显示记录。",
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(deploymentState.records, key = { it.deploymentId }) { record ->
                                DeploymentRecordCard(
                                    record = record,
                                    onClick = { onOpenDeploymentExec(record.deploymentId) },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private enum class RecordTab(val label: String) {
    TRIANGLE("三角架作业记录"),
    CONE("锥桶协同布置作业记录"),
}

@Composable
private fun RecordCard(record: WorkRecord, timeText: String) {
    val serverUpload = ServiceLocator.UPLOAD_MODE == UploadMode.HTTP
    ContentCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(record.kind.label)
                        // 作业车道（第几车道）：发布时必填，旧记录可能无此字段
                        record.lane?.let { append(" · ${LaneRange.label(it)}") }
                        append(" · ${record.targetM} 米")
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (record.source == com.example.myapplication.core.model.vehicle.TaskSource.BUTTON) {
                    StatusChip("车身按钮", NeutralGrey, NeutralGreyBg)
                }
            }
            Text(
                "开始时间 $timeText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "设备结果：${WorkRecord.deviceResultLabel(record.deviceResult)}",
                    color = if (record.deviceResult == WorkRecord.RESULT_DONE) SafeGreen else WarningRed,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "人工核验：${WorkRecord.verifyResultLabel(record.verifyResult)}",
                    color = when (record.verifyResult) {
                        WorkRecord.VERIFY_PASS -> SafeGreen
                        WorkRecord.VERIFY_FAIL -> WarningRed
                        else -> NeutralGrey
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                "编码器估算 ${"%.2f".format(record.traveledM)} 米 / 目标 ${record.targetM} 米",
                style = MaterialTheme.typography.labelSmall,
                color = NeutralGrey,
            )
            if (record.route.isNotEmpty()) {
                Text(
                    "路线：${record.route.joinToString(" → ") { it.summary() }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "上传状态：",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
                val (ufg, ubg) = when (record.uploadState) {
                    WorkRecord.UPLOAD_UPLOADED -> SafeGreen to SafeGreenBg
                    WorkRecord.UPLOAD_FAILED -> WarningRed to WarningRedBg
                    else -> com.example.myapplication.ui.theme.WarnOrange to com.example.myapplication.ui.theme.WarnOrangeBg
                }
                StatusChip(WorkRecord.uploadStateLabel(record.uploadState, serverUpload), ufg, ubg)
            }
            if (record.uploadState == WorkRecord.UPLOAD_UPLOADED) {
                Text(
                    if (serverUpload) {
                        "服务端单号 ${record.serverId ?: "-"}"
                    } else {
                        "单号 ${record.serverId ?: "-"}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
            if (!record.deviceResultNote.isNullOrBlank()) {
                Text("设备备注：${record.deviceResultNote}", style = MaterialTheme.typography.labelSmall, color = NeutralGrey)
            }
            if (!record.verifyNote.isNullOrBlank()) {
                Text("核验备注：${record.verifyNote}", style = MaterialTheme.typography.labelSmall, color = NeutralGrey)
            }
            if (!record.abnormalNote.isNullOrBlank()) {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = WarningRedBg),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        "异常：${record.abnormalNote}",
                        color = WarningRed,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}
