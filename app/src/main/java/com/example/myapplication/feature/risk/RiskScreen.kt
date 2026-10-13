package com.example.myapplication.feature.risk

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.geo.MapRenderModel
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.model.risk.RiskRadius
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.core.ui.map.AMapCanvas
import com.example.myapplication.core.ui.map.MapPin
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前方道路风险提示（导航栏新增页）。
 *
 * - 半径可自行设置（1~20 公里）
 * - 高德地图显示用户位置与周围风险点位
 * - 地图下方按**距离从近到远**列出事故，点击进入详情（照片/视频/介绍）
 */
@Composable
fun RiskScreen(
    mapVm: MapViewModel,
    onOpenDetail: (Accident) -> Unit,
) {
    val mapState by mapVm.uiState.collectAsStateWithLifecycle()
    val phoneFix by mapVm.phoneFix.collectAsStateWithLifecycle()

    var radiusKm by remember { mutableStateOf(RiskRadius.DEFAULT_KM) }
    var accidents by remember { mutableStateOf<List<Accident>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // 中心点：用户当前位置（GCJ-02）；无定位时用演示基准点
    val center = phoneFix?.point ?: GeoPoint(39.9928, 116.4004)

    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember(context) {
        com.example.myapplication.di.ServiceLocator.get(context).riskRepository
    }

    LaunchedEffect(radiusKm, center) {
        loading = true
        accidents = repo.getAccidents(center, radiusKm.toDouble())
            .sortedBy { it.distanceM }      // 从最近到最远
        loading = false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 半径设置 ──
        item {
            SectionCard(title = "搜索半径") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "周边 ${radiusKm} 公里",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${RiskRadius.MIN_KM} ~ ${RiskRadius.MAX_KM} 公里",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralGrey,
                    )
                }
                Slider(
                    value = radiusKm.toFloat(),
                    onValueChange = { radiusKm = it.toInt().coerceIn(RiskRadius.MIN_KM, RiskRadius.MAX_KM) },
                    valueRange = RiskRadius.MIN_KM.toFloat()..RiskRadius.MAX_KM.toFloat(),
                    steps = RiskRadius.MAX_KM - RiskRadius.MIN_KM - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "拖动可调整搜索范围，地图与列表会同步刷新",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
        }

        // ── 风险地图 ──
        item {
            SectionCard(title = "风险地图（周边 ${radiusKm} 公里）") {
                AMapCanvas(
                    model = MapRenderModel(
                        phone = phoneFix?.point,
                        followPhone = true,
                    ),
                    extraMarkers = accidents.map { a ->
                        MapPin(
                            id = a.id,
                            point = GeoPoint(a.lat, a.lng),
                            title = a.title,
                            snippet = "${a.type.label} · ${a.distanceText}",
                            colorArgb = android.graphics.Color.parseColor("#D32F2F"),
                        )
                    },
                    onMapReady = { },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                )
            }
        }

        // ── 事故列表（从近到远）──
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "事故列表（从近到远）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (loading) "加载中…" else "共 ${accidents.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralGrey,
                )
            }
        }

        if (accidents.isEmpty() && !loading) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "周边 ${radiusKm} 公里内暂无风险事件\n可调大搜索半径再试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeutralGrey,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(accidents, key = { it.id }) { accident ->
            AccidentCard(accident = accident, onClick = { onOpenDetail(accident) })
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AccidentCard(accident: Accident, onClick: () -> Unit) {
    val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = accident.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${accident.type.label} · ${timeFormat.format(Date(accident.occurredAt))}",
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
