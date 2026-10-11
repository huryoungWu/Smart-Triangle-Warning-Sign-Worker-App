package com.example.myapplication.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.WarningRed

/** 红色高亮警示/法规约束文本（NFR-110） */
@Composable
fun SafetyText(
    text: String,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        if (showIcon) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = WarningRed,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = WarningRed,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}
