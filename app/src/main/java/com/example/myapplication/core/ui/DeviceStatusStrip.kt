package com.example.myapplication.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.VehicleUiState
import java.util.Locale

/**
 * 通用状态条（M02）：连接 / 车辆模式 / 运动状态 / 灯光模式 —— **灯与运动分开显示**（规格第 4 节）。
 * 通信中断时标明最后更新时间；数据过期标注"非实时"（M02/M09）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceStatusStrip(
    state: VehicleUiState,
    modifier: Modifier = Modifier,
    /** 手动刷新进行中：按钮显示进度并禁用 */
    refreshing: Boolean = false,
    /** 手动刷新状态；null 时不显示刷新按钮（未接入手动刷新的页面保持原样） */
    onRefresh: (() -> Unit)? = null,
) {
    val snapshot = state.snapshot
    val stale = state.isStale()
    // 已连接或已断开（状态未确认）时允许刷新；连接中/未连接不可刷新
    val canRefresh = state.connection == ConnectionPhase.CONNECTED ||
        state.connection == ConnectionPhase.LOST

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionChip(state.connection)
            snapshot?.let {
                VehicleModeChip(it.mode)
                MotionChip(it.motion)
                LightModeChip(it.light)
                if (stale) {
                    StatusChip(
                        "非实时",
                        com.example.myapplication.ui.theme.WarnOrange,
                        com.example.myapplication.ui.theme.WarnOrangeBg,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.lastUpdateAt > 0) {
                    "最后更新${TimeFormat.format("HH:mm:ss", state.lastUpdateAt)}"
                } else {
                    ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onRefresh != null) {
                TextButton(
                    onClick = onRefresh,
                    enabled = canRefresh && !refreshing,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(if (refreshing) "刷新中…" else "刷新状态")
                }
            }
        }
    }
}
