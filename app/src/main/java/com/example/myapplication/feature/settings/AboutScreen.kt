package com.example.myapplication.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard

/** 关于我们（设置 → 关于我们） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageTitle("关于我们", onBack = onBack)

        Spacer(Modifier.height(16.dp))
        Text(
            "智能三角警示牌作业",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Android 客户端 V1.0",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        SectionCard(title = "应用简介") {
            Text(
                text = "三角警示牌小车现场控制应用。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "使用说明") {
            Text(
                text = "连接设备 → 选择作业 → 发布任务 → 核验记录",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "安全提示") {
            SafetyText("仅供有人看护的封闭场地使用；作业前须人工确认现场安全。")
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "© 2026 智能三角警示牌项目组",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }
}
