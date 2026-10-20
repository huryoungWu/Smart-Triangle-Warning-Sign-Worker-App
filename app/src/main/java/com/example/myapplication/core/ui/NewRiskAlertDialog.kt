package com.example.myapplication.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.util.TimeFormat
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarningRed

/**
 * 「前方新风险」弹窗。
 *
 * 只由 `AppNavHost` 全局渲染一次（与安全弹窗 `AppDialogRenderer` 互斥），
 * 页面不得私自调用，遵守工程既有的"全局弹窗统一出口"约定。
 */
@Composable
fun NewRiskAlertDialog(
    accident: Accident,
    extraCount: Int,
    onOpenDetail: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,   // 返回键/点遮罩同样算已读
        title = {
            Text("前方新风险", color = WarningRed, fontWeight = FontWeight.Bold)
        },
        text = {
            Row(verticalAlignment = Alignment.Top) {
                val photo = accident.photoUrls.firstOrNull()
                if (photo != null) {
                    RemoteImage(
                        url = photo,
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = accident.title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(accident.type.label)
                            accident.laneText?.let { append(" · $it") }
                            append(" · ${accident.distanceText}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = TimeFormat.mmddHHmm(accident.occurredAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralGrey,
                    )
                    Text(
                        text = accident.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (extraCount > 0) {
                        Text(
                            text = "另有 $extraCount 条新风险",
                            color = WarningRed,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenDetail,
                colors = ButtonDefaults.buttonColors(containerColor = WarningRed),
            ) { Text("查看详情") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = NeutralGrey),
            ) { Text("知道了") }
        },
    )
}
