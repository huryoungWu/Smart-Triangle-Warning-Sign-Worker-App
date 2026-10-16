package com.example.myapplication.feature.record

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.myapplication.core.ui.LOCKED_FEATURE_NOTICE
import com.example.myapplication.core.ui.LockedFeatureCard
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.SafetyNoticeBanner
import com.example.myapplication.core.ui.BannerVariant
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
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
 * **数据真实性**：记录始终在本机留一份；UPLOAD_MODE=HTTP 时同时上传服务端并展示服务端单号，
 * STUB 模式下标注"仅本机保存"，不得宣称已上传平台。
 */
@Composable
fun RecordsScreen(vm: VehicleViewModel) {
    val records by vm.records.collectAsStateWithLifecycle(initialValue = emptyList())
    val serverUpload = ServiceLocator.UPLOAD_MODE == UploadMode.HTTP

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("作业记录")

        SafetyNoticeBanner(
            variant = BannerVariant.INFO,
            text = if (serverUpload) {
                "记录保存在本机手机，并上传服务端（POST /api/v1/tasks）；服务端单号见各条记录。"
            } else {
                "记录保存在本机手机（${WorkRecord.LOCAL_ONLY_NOTICE}）；上传为演示占位，未接入真实服务器。"
            },
        )

        if (records.isEmpty()) {
            SectionCard(title = "暂无作业记录") {
                Text(
                    if (serverUpload) {
                        "完成一次定距布置或一键布置后，记录会自动保存到本机并上传服务端。"
                    } else {
                        "完成一次定距布置或一键布置后，记录会自动保存到本机（${WorkRecord.LOCAL_ONLY_NOTICE}）。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records, key = { it.id }) { record ->
                    RecordCard(record, TimeFormat.format("MM-dd HH:mm", record.startedAt))
                }
            }
        }

        LockedFeatureCard(
            title = "云端记录 / 事件编号",
            reason = LOCKED_FEATURE_NOTICE,
            onClick = { vm.showInfo("云端记录", LOCKED_FEATURE_NOTICE) },
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RecordCard(record: WorkRecord, timeText: String) {
    val serverUpload = ServiceLocator.UPLOAD_MODE == UploadMode.HTTP
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                        "服务端单号 ${record.serverId ?: "-"}（Mock 联调服务端）"
                    } else {
                        "演示占位单号 ${record.serverId ?: "-"}，未接入真实服务器"
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
