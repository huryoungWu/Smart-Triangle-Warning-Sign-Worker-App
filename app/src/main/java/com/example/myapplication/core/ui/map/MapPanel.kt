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
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.geo.MapRenderModel
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
    /** 用五角星标出「我的位置」（风险页需要；作业页保持只有小车点位） */
    showPhoneMarker: Boolean = false,
    cameraZoom: Float = DEFAULT_CAMERA_ZOOM,
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
                text = "地图仅用于显示位置，不用于安全判断。",
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
                            text = "地图可显示，但不显示手机定位点。",
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
                showPhoneMarker = showPhoneMarker,
                cameraZoom = cameraZoom,
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
        }
    }
}

/**
 * 只依赖定位的轻量地图面板（无 VehicleViewModel）：
 * 用于锥桶发布页「现场位置」和执行页「主/从桶实时位置」。
 *
 * - [extraMarkers] 可放主桶/从桶等点位；
 * - [cameraCenter] 非空时相机以它为中心（执行页用手机起点锚点，避免跟随当前手机）；
 * - [showPhoneMarker] 控制是否画「我的位置」五角星；
 * - 其他隐私/定位权限门与 [MapPanel] 一致。
 */
@Composable
fun PhoneMapPanel(
    mapVm: MapViewModel,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    title: String = "现场位置",
    extraMarkers: List<MapPin> = emptyList(),
    extraPolylines: List<MapPolyline> = emptyList(),
    showPhoneMarker: Boolean = true,
    caption: String? = null,
    cameraCenter: GeoPoint? = null,
    followPhone: Boolean? = null,
    cameraZoom: Float = DEFAULT_CAMERA_ZOOM,
    /** 地图按下/抬起回调：页面可据此在拖动地图时禁用外层垂直滚动 */
    onTouchActiveChange: ((Boolean) -> Unit)? = null,
) {
    val mapState by mapVm.uiState.collectAsStateWithLifecycle()
    val phoneFix by mapVm.phoneFix.collectAsStateWithLifecycle()
    val permission = rememberLocationPermissionState()

    LaunchedEffect(mapState.privacyAccepted, permission.granted) {
        if (mapState.privacyAccepted && permission.granted) {
            mapVm.startLocating()
        }
    }

    SectionCard(title = title, modifier = modifier) {
        if (!mapState.privacyAccepted) {
            Text(
                text = "地图仅用于显示位置，不用于安全判断。",
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
                            text = "地图可显示，但无法估算手机/锥桶位置。",
                            color = WarnOrange,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        PrimaryActionButton(text = "申请定位权限", onClick = permission::request)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // cameraCenter（起点锚点）优先；否则只有需要画手机星标时才把手机位置交给相机。
            val renderPhone = cameraCenter ?: if (showPhoneMarker) phoneFix?.point else null
            AMapCanvas(
                model = MapRenderModel(
                    phone = renderPhone,
                    followPhone = followPhone ?: mapState.followPhone,
                ),
                onMapReady = { },
                extraMarkers = extraMarkers,
                extraPolylines = extraPolylines,
                showPhoneMarker = showPhoneMarker,
                cameraZoom = cameraZoom,
                onTouchActiveChange = onTouchActiveChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height),
            )

            if (!caption.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
