package com.example.myapplication.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.WarningRed

/**
 * 失败/拒绝原因弹窗（NFR-109）：必须展示具体可读原因与处置建议，禁止仅展示"操作失败"。
 */
@Composable
fun ReasonDialog(
    title: String,
    reasons: List<String>,
    advice: String = "",
    confirmText: String = "我知道了",
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onConfirm,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = WarningRed,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(title, color = WarningRed, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                reasons.forEach { reason ->
                    Text(
                        text = "• $reason",
                        color = WarningRed,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (advice.isNotBlank()) {
                    Text(
                        text = advice,
                        color = NeutralGrey,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = WarningRed),
            ) { Text(confirmText) }
        },
    )
}
