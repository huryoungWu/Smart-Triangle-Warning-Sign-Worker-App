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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.BuildConfig
import com.example.myapplication.core.geo.MapRenderModel
import com.example.myapplication.core.ui.LOCKED_FEATURE_NOTICE
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SecondaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.map.AMapCanvas
import com.example.myapplication.core.ui.map.MapLegend
import com.example.myapplication.core.ui.map.VehiclePositionDisclaimer
import com.example.myapplication.core.ui.permission.rememberLocationPermissionState
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/**
 * 地图页（C 阶段能力提前落地）：真实地图 + 手机定位 + 小车位置（GPS／行程估算）。
 *
 * 三层门：
 * ① 隐私同意（高德合规硬要求，未同意前不创建 MapView/定位 SDK）
 * ② 定位权限（无权限也出瓦片，仅不显示定位点；**车端点位不受影响**）
 * ③ 正常（地图 + 图例 + 免责横幅 + 跟随开关）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

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

        // 门 0：无 Key 友好提示（不黑屏不闪退）
        if (!BuildConfig.AMAP_KEY_CONFIGURED) {
            Card(colors = CardDefaults.cardColors(containerColor = WarningRedBg)) {
                Column(Modifier.padding(12.dp)) {
                    Text("地图 Key 未配置", color = WarningRed, fontWeight = FontWeight.Bold)
                    Text(
                        text = "请在 local.properties 中填写 AMAP_KEY（或 AMAP_KEY_DEBUG/AMAP_KEY_RELEASE），" +
                            "然后 Re-Sync。未配置 Key 时地图无法加载，但不影响 APP 其他功能。",
                        color = WarningRed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // 门 1：隐私同意（高德合规硬要求）
        if (!mapState.privacyAccepted) {
            SectionCard(title = "地图与定位服务说明") {
                Text(
                    text = "本页地图与定位由高德 SDK 提供。继续使用前请阅读并同意：\n\n" +
                        "• 定位信息仅用于在地图上显示作业人员位置\n" +
                        "• 不用于任何安全判断或道路风险评估\n" +
                        "• 地图底图由高德提供，需联网加载",
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
                        text = "地图可正常显示，但不会显示作业人员定位点。\n" +
                            "车端 GPS／行程估算点位不受影响。",
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
                        text = "首次定位可能需要几秒。若长时间无结果，请确认：\n" +
                            "• 手机系统「定位服务」已打开\n" +
                            "• APP 已授予定位权限（且非「仅大致位置」）\n" +
                            "• 室内 GPS 信号弱，可到窗边/室外\n" +
                            "• 诊断卡中「定位来源」应为「真实高德定位」",
                        color = WarnOrange,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        SecondaryActionButton(text = "回到我的位置（跟随关闭时）", onClick = mapVm::toggleFollow)

        // 图例（数据真实性）
        SectionCard(title = "图例") {
            MapLegend(vehicleHasGps = renderModel.vehicleHasGps)
        }

        // 免责横幅（规格第 4 节·数据真实性）
        VehiclePositionDisclaimer(
            vehicleHasGps = renderModel.vehicleHasGps,
            stale = vehicleState.isStale(),
        )

        SafetyText("地图仅用于显示位置，不具备道路安全判断、障碍或来车检测能力。")
        SafetyText("小车位置不等于道路风险评估；布置前须人工现场确认。")

        // 诊断卡：地图不显示时按此逐项排查
        SectionCard(title = "诊断信息（地图异常时查看）") {
            val injected = com.example.myapplication.core.ui.map.MapSdkPrivacy.injectedApiKey
            DiagRow(
                "Key 配置 / setApiKey",
                when {
                    injected != null -> "已注入 ${injected.take(6)}…"
                    BuildConfig.AMAP_KEY_CONFIGURED -> "配置了但未注入"
                    else -> "未配置"
                },
            )
            DiagRow("隐私合规（地图 SDK）", if (mapState.privacyAccepted) "已同意" else "未同意")
            DiagRow("定位权限", if (permission.granted) "已授权" else "未授权")
            DiagRow("定位来源", if (com.example.myapplication.di.ServiceLocator.get(
                    androidx.compose.ui.platform.LocalContext.current,
                ).locationSource == com.example.myapplication.di.LocationSource.AMAP
            ) "真实高德定位" else "模拟轨迹（演示）")
            DiagRow("小车位置来源", if (renderModel.vehicleHasGps) "车端 GPS" else "行程估算（无车端 GPS）")
            Spacer(Modifier.height(6.dp))
            SafetyText("若地图仍空白：请核对高德控制台 Key 绑定的包名与 SHA1 是否与本机一致。")
        }

        Spacer(Modifier.height(24.dp))
    }
}
