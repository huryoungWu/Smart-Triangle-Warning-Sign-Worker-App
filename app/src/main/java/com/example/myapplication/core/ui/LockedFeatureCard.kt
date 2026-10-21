package com.example.myapplication.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * C 阶段功能置灰卡（用户拍板：A+B+C UI 全做，C 置灰禁用）。
 * 统一标注"待硬件验收后开放"，点击仅提示，不执行任何动作。
 */
@Composable
fun LockedFeatureCard(
    title: String,
    reason: String = "待硬件验收后开放",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().alpha(0.55f)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                PendingHardwareChip()
            }
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Button(
                onClick = onClick,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("暂不可用 · 点击查看说明")
            }
        }
    }
}

/** 置灰说明弹窗文案 */
const val LOCKED_FEATURE_NOTICE = "该能力需完成硬件/定位/通信验收后开放。"
