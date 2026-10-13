package com.example.myapplication.feature.risk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事故详情页：事故照片、事故视频、介绍。
 *
 * ⚠️ 照片/视频为**演示占位**（无真实事故数据源），接通真实数据源后替换为实际媒体。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccidentDetailScreen(
    accident: Accident,
    onBack: () -> Unit,
) {
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("事故详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题与标签
            Text(
                text = accident.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(accident.type.label, WarningRed, WarningRedBg)
                Spacer(Modifier.width(8.dp))
                StatusChip(
                    text = "距您 ${accident.distanceText}",
                    fg = if (accident.distanceM < 1000) WarnOrange else MaterialTheme.colorScheme.primary,
                    bg = if (accident.distanceM < 1000) WarnOrangeBg else MaterialTheme.colorScheme.primaryContainer,
                )
            }
            Text(
                text = "发生时间：${timeFormat.format(Date(accident.occurredAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── 事故照片 ──
            SectionCard(title = "事故照片") {
                if (accident.hasPhoto) {
                    MediaPlaceholder(
                        title = "事故现场照片",
                        subtitle = "演示占位 · 未接入真实事故数据源",
                        icon = Icons.Default.Warning,
                        height = 180.dp,
                    )
                } else {
                    Text("暂无现场照片", color = NeutralGrey, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── 事故视频 ──
            SectionCard(title = "事故视频") {
                if (accident.hasVideo) {
                    MediaPlaceholder(
                        title = "点击查看视频",
                        subtitle = "演示占位 · 未接入真实事故数据源",
                        icon = Icons.Default.PlayArrow,
                        height = 180.dp,
                    )
                } else {
                    Text("暂无现场视频", color = NeutralGrey, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── 介绍 ──
            SectionCard(title = "事故介绍") {
                Text(
                    text = accident.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // ── 位置 ──
            SectionCard(title = "事故位置") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = WarningRed)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = String.format("纬度 %.5f · 经度 %.5f", accident.lat, accident.lng),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
                SafetyText("坐标为高德地图坐标系（GCJ-02），可直接用于导航。")
            }

            Spacer(Modifier.height(8.dp))
            SafetyText("请谨慎驾驶，注意观察现场情况；以上信息仅供参考，以现场实际为准。")

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MediaPlaceholder(
    title: String,
    subtitle: String,
    icon: ImageVector,
    height: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF455A64), Color(0xFF263238)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
