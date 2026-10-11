package com.example.myapplication.core.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.example.myapplication.core.model.AppDialog
import com.example.myapplication.ui.theme.WarningRed

/** 全局一次性弹窗统一出口（FR-103/108/114、NFR-109），全应用禁止页面私自拼 AlertDialog */
@Composable
fun AppDialogRenderer(
    dialog: AppDialog,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is AppDialog.Alert -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(dialog.title, fontWeight = FontWeight.Bold) },
            text = { Text(dialog.message) },
            confirmButton = {
                Button(onClick = onDismiss) { Text("我知道了") }
            },
        )

        is AppDialog.Warn -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(dialog.title, color = WarningRed, fontWeight = FontWeight.Bold) },
            text = { Text(dialog.message) },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = WarningRed),
                ) { Text("我知道了") }
            },
        )

        is AppDialog.Reject -> ReasonDialog(
            title = dialog.title,
            reasons = dialog.reasons,
            advice = dialog.advice,
            onConfirm = onDismiss,
        )
    }
}
