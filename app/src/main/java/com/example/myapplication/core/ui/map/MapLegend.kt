package com.example.myapplication.core.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.ui.SafetyText

/**
 * 地图图例（数据真实性的 UI 落点）：
 * 三类位置必须区分标注，估算不得冒充地图定位（规格第 4 节）。
 */
@Composable
fun MapLegend(
    vehicleHasGps: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LegendRow(Color(0xFF1565C0), "作业人员（手机定位）", "手机 GPS 定位（GCJ-02，直接显示）")
        if (vehicleHasGps) {
            LegendRow(Color(0xFFE65100), "小车（GPS）", "车端 GPS 上报（WGS-84 转 GCJ-02 显示）")
        }
        LegendRow(Color(0xFF757575), "小车（行程估算）", "由车轮编码器估算行驶距离推算 · 非地图定位", dashed = true)
    }
}

@Composable
private fun LegendRow(color: Color, title: String, desc: String, dashed: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Canvas(Modifier.size(14.dp)) {
            drawCircle(color = color, radius = size.minDimension / 2)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 小车位置来源免责声明（常驻横幅） */
@Composable
fun VehiclePositionDisclaimer(vehicleHasGps: Boolean, stale: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (vehicleHasGps) {
            SafetyText("小车位置来自车端 GPS 上报并做坐标转换；定位精度取决于车端模块。")
        } else {
            SafetyText("小车位置为行程估算示意，非地图实时定位，误差可达数十米；请以现场实际位置为准。")
        }
        if (stale) {
            SafetyText("设备状态非实时（通信中断或数据过期）。")
        }
    }
}
