package com.example.myapplication.core.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.permission.rememberLocationPermissionState
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg

/**
 * 可嵌入的地图面板：在任意作业页直接展示小车实时位置。
 *
 * 自己负责三件事，调用方不用管：
 * 1. **隐私同意门**（高德合规，未同意前不创建 MapView）——紧凑卡片内联处理
 * 2. **启动定位**（隐私+权限就绪后）
 * 3. **车端快照推入**（小车位置解析）
 *
 * 小车位置规则：无车端 GPS 时为行程估算（起点=作业人员位置，方向=作业人员方向，默认正北），
 * 图形为灰色虚线点并标注「非地图定位」。
 */
@Composable
fun MapPanel(
    vm: VehicleViewModel,
    mapVm: MapViewModel,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    title: String = "小车实时位置",
    showLegend: Boolean = true,
    showFollowSwitch: Boolean = false,
    extraMarkers: List<MapPin> = emptyList(),
) {
    val mapState by mapVm.uiState.collectAsStateWithLifecycle()
    val vehicleState by vm.uiState.collectAsStateWithLifecycle()
    val permission = rememberLocationPermissionState()
    val renderModel = mapVm.renderModel()

    // 车端快照推入（小车位置解析）
    LaunchedEffect(vehicleState.snapshot) {
        vehicleState.snapshot?.let { mapVm.onVehicleSnapshot(it) }
    }
    // 隐私+权限就绪后启动定位
    LaunchedEffect(mapState.privacyAccepted, permission.granted) {
        if (mapState.privacyAccepted && permission.granted) {
            mapVm.startLocating()
        }
    }

    SectionCard(title = title, modifier = modifier) {
        if (!mapState.privacyAccepted) {
            Text(
                text = "地图与定位由高德 SDK 提供，用于显示小车与作业人员位置，不用于安全判断。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryActionButton(text = "同意并启用地图", onClick = mapVm::acceptPrivacy)
        } else {
            if (!permission.granted) {
                Card(colors = CardDefaults.cardColors(containerColor = WarnOrangeBg)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("未授予定位权限", color = WarnOrange, fontWeight = FontWeight.Bold)
                        Text(
                            text = "地图可显示，但不显示作业人员定位点；小车估算点位不受影响。",
                            color = WarnOrange,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        PrimaryActionButton(text = "申请定位权限", onClick = permission::request)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            AMapCanvas(
                model = renderModel,
                onMapReady = { },
                extraMarkers = extraMarkers,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height),
            )

            if (showFollowSwitch) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("跟随作业人员", modifier = Modifier.weight(1f))
                    Switch(checked = mapState.followPhone, onCheckedChange = { mapVm.toggleFollow() })
                }
            }

            if (showLegend) {
                Spacer(Modifier.height(8.dp))
                MapLegend(vehicleHasGps = renderModel.vehicleHasGps)
            }

            Spacer(Modifier.height(6.dp))
            VehiclePositionDisclaimer(
                vehicleHasGps = renderModel.vehicleHasGps,
                stale = vehicleState.isStale(),
            )
        }
    }
}

/** 紧凑诊断行（地图页复用） */
@Composable
fun MapDiagRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
