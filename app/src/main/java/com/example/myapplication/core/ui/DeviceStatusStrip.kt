package com.example.myapplication.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.VehicleUiState
import java.text.SimpleDateFormat
import java.util.Date
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
) {
    val snapshot = state.snapshot
    val stale = state.isStale()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (state.connection == ConnectionPhase.CONNECTED) {
                    "以设备返回值为准 · 位置由车轮编码器估算"
                } else {
                    "状态未确认，禁用运动操作"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.lastUpdateAt > 0) {
                Text(
                    text = "最后更新 ${timeFormat.format(Date(state.lastUpdateAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
