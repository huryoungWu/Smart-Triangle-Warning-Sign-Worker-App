package com.example.myapplication.core.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.myapplication.ui.theme.WarningRed

/**
 * 二次确认弹窗：用于「恢复待命」「取消任务」等需要确认的操作。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    dismissText: String = "取消",
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (danger) {
                    ButtonDefaults.buttonColors(containerColor = WarningRed)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(confirmText) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}
