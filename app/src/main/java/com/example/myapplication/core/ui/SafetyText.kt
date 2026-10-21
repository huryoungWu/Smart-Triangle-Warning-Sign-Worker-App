package com.example.myapplication.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.myapplication.ui.theme.WarningRed

/** 红色高亮警示/法规约束文本（NFR-110） */
@Composable
fun SafetyText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = WarningRed,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
    )
}
