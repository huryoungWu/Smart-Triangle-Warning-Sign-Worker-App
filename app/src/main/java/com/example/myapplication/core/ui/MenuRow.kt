package com.example.myapplication.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.WarningRed

/**
 * 设置类页面的一行菜单：左侧标签 + 右侧 `›` 指示。
 *
 * **整行可点**（`clickable` 挂在 Row 上）—— 右侧箭头只是视觉指示，不单独承载点击。
 * 早先的实现把 onClick 挂在箭头 TextButton 上，导致点标签没反应、必须精准点到那个
 * 小箭头才进得去，看起来像坏了。
 */
@Composable
fun MenuRow(
    label: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) WarningRed else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = if (danger) WarningRed else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
