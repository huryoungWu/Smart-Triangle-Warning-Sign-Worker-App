package com.example.myapplication.feature.map

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import com.example.myapplication.core.geo.MapRenderModel
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.map.AMapCanvas
import com.example.myapplication.core.ui.map.MapLegend
import com.example.myapplication.core.ui.permission.rememberLocationPermissionState
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg

/** 地图页：隐私/权限门 + 地图 + 图例。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    vm: VehicleViewModel,
    mapVm: MapViewModel,
    onBack: () -> Unit,
) {
    val mapState by mapVm.uiState.collectAsStateWithLifecycle()
    val vehicleState by vm.uiState.collectAsStateWithLifecycle()
    val permission = rememberLocationPermissionState()
    val renderModel = remember(mapState, mapVm.phoneFix.value) { mapVm.renderModel() }

    val lifecycleOwner = LocalLifecycleOwner.current
    // 页面可见期间 1Hz 读取状态：小车位置/轨迹实时更新；切后台或离开页面自动停止
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.acquireLiveStatus()
            try {
                awaitCancellation()
            } finally {
                vm.releaseLiveStatus()
            }
        }
    }

    // 车端快照随 VehicleViewModel 状态推入地图
    LaunchedEffect(vehicleState.snapshot) {
        vehicleState.snapshot?.let { mapVm.onVehicleSnapshot(it) }
    }

    // 隐私同意 + 定位权限都就绪后才启动定位（幂等，startLocating 内部防重）
    LaunchedEffect(mapState.privacyAccepted, permission.granted) {
        if (mapState.privacyAccepted && permission.granted) {
            mapVm.startLocating()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
        text = "地图位置",
        onBack = onBack,
        trailing = {
            IconButton(onClick = mapVm::toggleFollow) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = "跟随/回中",
                    tint = if (mapState.followPhone) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        )

        // 门 1：隐私同意（高德合规硬要求）
        if (!mapState.privacyAccepted) {
            SectionCard(title = "地图与定位服务说明") {
                Text(
                    text = "地图与定位由高德 SDK 提供，仅用于显示位置，不用于安全判断。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryActionButton(text = "同意并继续", onClick = mapVm::acceptPrivacy)
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton(text = "暂不使用", onClick = onBack)
            }
            return@Column
        }

        // 门 2：定位权限（未授权时地图仍出图，仅无定位点）
        if (!permission.granted) {
            Card(colors = CardDefaults.cardColors(containerColor = WarnOrangeBg)) {
                Column(Modifier.padding(12.dp)) {
                    Text("未授予定位权限", color = WarnOrange, fontWeight = FontWeight.Bold)
                    Text(
                        text = "地图可显示，但不会显示手机定位点。",
                        color = WarnOrange,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryActionButton(text = "申请定位权限", onClick = permission::request)
                    Spacer(Modifier.height(6.dp))
                    SecondaryActionButton(text = "去系统设置", onClick = permission::openAppSettings)
                }
            }
        } else if (permission.coarseOnly) {
            StatusChip("仅大致位置 · 精度受限", NeutralGrey, WarnOrangeBg)
        }

        // 门 3：地图
        AMapCanvas(
            model = renderModel,
            onMapReady = { /* 相机已由 renderMap 管理 */ },
            showPhoneMarker = true,   // 蓝星 = 我的实时位置 A
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
        )

        // 跟随开关 + 回中
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("跟随作业人员", modifier = Modifier.weight(1f))
            Switch(checked = mapState.followPhone, onCheckedChange = { mapVm.toggleFollow() })
        }

        if (permission.granted && renderModel.phone == null) {
            Card(colors = CardDefaults.cardColors(containerColor = WarnOrangeBg)) {
                Column(Modifier.padding(12.dp)) {
                    Text("正在获取定位…", color = WarnOrange, fontWeight = FontWeight.Bold)
                    Text(
                        text = "首次定位可能需几秒；请检查系统定位服务、权限和信号。",
                        color = WarnOrange,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        SecondaryActionButton(text = "回到我的位置", onClick = mapVm::toggleFollow)

        // 图例（数据真实性）
        SectionCard(title = "图例") {
            MapLegend(vehicleHasGps = renderModel.vehicleHasGps)
        }

        SafetyText("地图仅显示位置，不替代现场安全确认。")

        Spacer(Modifier.height(24.dp))
    }
}
