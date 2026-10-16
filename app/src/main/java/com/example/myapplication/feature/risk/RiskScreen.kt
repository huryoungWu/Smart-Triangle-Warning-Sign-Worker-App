package com.example.myapplication.feature.risk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.model.risk.RiskRadius
import com.example.myapplication.core.model.risk.RiskSort
import com.example.myapplication.core.model.risk.RiskTimeScope
import com.example.myapplication.core.model.risk.applyRiskView
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.RemoteImage
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.map.MapPanel
import com.example.myapplication.core.ui.map.MapPin
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg

/**
 * 前方道路风险提示（导航栏新增页）。
 *
 * 页面骨架与「作业记录 / 设置」一致：标题 [PageTitle] + 内容列 16dp 边距、12dp 间距。
 * - 搜索范围固定 2 公里（无设置项）
 * - 高德地图显示**我的位置（五角星）**与周围风险点位；风险页不显示小车位置免责横幅
 * - 列表**默认只筛「今天」**，可按「按距离近→远（默认）/ 按时间新→旧」排序，点击进入详情（照片/介绍）
 * - 列表条目**都带占用车道**（第 N 车道）
 */
@Composable
fun RiskScreen(
    vm: VehicleViewModel,
    mapVm: MapViewModel,
    onOpenDetail: (Accident) -> Unit,
) {
    val phoneFix by mapVm.phoneFix.collectAsStateWithLifecycle()

    val radiusKm = RiskRadius.DEFAULT_KM
    var accidents by remember { mutableStateOf<List<Accident>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // 列表口径：默认「今天」+ 按距离从近到远
    var timeScope by remember { mutableStateOf(RiskTimeScope.TODAY) }
    var sort by remember { mutableStateOf(RiskSort.DISTANCE) }

    // 中心点：用户当前位置（GCJ-02）；无定位时用演示基准点
    val center = phoneFix?.point ?: GeoPoint(39.9928, 116.4004)

    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember(context) {
        com.example.myapplication.di.ServiceLocator.get(context).riskRepository
    }

    LaunchedEffect(center) {
        loading = true
        accidents = repo.getAccidents(center, radiusKm.toDouble())
        loading = false
    }

    // 筛选 + 排序后的列表（地图点位与列表同一口径，切筛选时同步变化）
    val shown = remember(accidents, timeScope, sort) { accidents.applyRiskView(timeScope, sort) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 页面标题（全应用统一样式，与「作业记录 / 设置」一致）
        item { PageTitle("风险地图") }

        // ── 风险地图（中心点=用户坐标，与任务地图同源：MapPanel 接管定位/隐私门）──
        item {
            MapPanel(
                vm = vm,
                mapVm = mapVm,
                height = 260.dp,
                title = "风险点位",
                showLegend = false,
                showPhoneMarker = true,      // 五角星 = 我的位置
                showDisclaimer = false,      // 风险页不显示小车位置免责横幅（红色感叹号提示）
                cameraZoom = RISK_MAP_CAMERA_ZOOM,   // 视野覆盖 2 公里半径内的风险点位
                extraMarkers = shown.map { a ->
                    MapPin(
                        id = a.id,
                        point = GeoPoint(a.lat, a.lng),
                        title = a.title,
                        snippet = buildString {
                            append(a.type.label)
                            a.laneText?.let { append(" · $it") }   // 占用车道（第几车道）
                            append(" · ${a.distanceText}")
                        },
                        colorArgb = android.graphics.Color.parseColor("#D32F2F"),
                    )
                },
            )
        }

        // ── 事故列表（筛选 + 排序）──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "事故列表",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (loading) "加载中…" else "共 ${shown.size} 条 · ${sort.orderHint}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralGrey,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    RiskTimeScope.entries.forEach { scope ->
                        FilterChip(
                            selected = timeScope == scope,
                            onClick = { timeScope = scope },
                            label = { Text(scope.label) },
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    RiskSort.entries.forEach { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { sort = option },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        }

        if (shown.isEmpty() && !loading) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = when (timeScope) {
                            RiskTimeScope.TODAY -> "今天周边 ${radiusKm} 公里内暂无风险事件（可切「全部」查看更早的）"
                            RiskTimeScope.ALL -> "周边 ${radiusKm} 公里内暂无风险事件"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeutralGrey,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(shown, key = { it.id }) { accident ->
            AccidentCard(accident = accident, onClick = { onOpenDetail(accident) })
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 风险页相机缩放：15 ≈ 视野约 4 公里宽，刚好覆盖 2 公里搜索半径 */
private const val RISK_MAP_CAMERA_ZOOM = 15f

@Composable
private fun AccidentCard(accident: Accident, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            // 有照片就显示缩略图（作业人员上报的快照图片，便于区分各条事件）
            val photo = accident.photoUrls.firstOrNull()
            if (photo != null) {
                RemoteImage(
                    url = photo,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = accident.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = buildString {
                        append(accident.type.label)
                        // 占用车道（第几车道）
                        accident.laneText?.let { append(" · $it") }
                        append(" · ${TimeFormat.format("MM-dd HH:mm", accident.occurredAt)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(
                text = accident.distanceText,
                fg = if (accident.distanceM < 1000) WarnOrange else InfoBlue,
                bg = if (accident.distanceM < 1000) WarnOrangeBg else InfoBlueBg,
            )
        }
    }
}
