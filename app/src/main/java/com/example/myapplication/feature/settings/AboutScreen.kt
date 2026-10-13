package com.example.myapplication.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.ui.SafetyText
import com.example.myapplication.core.ui.SectionCard

/** 关于我们（设置 → 关于我们） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于我们") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                    text = "面向现场作业人员的三角警示牌小车控制应用。" +
                        "支持设备连接、任务发布（手动遥控／定距布置）、路线分段执行、" +
                        "进度监控、人工核验与作业记录本地存档。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "使用说明") {
                Text(
                    text = "1. 登录后连接设备\n" +
                        "2. 选择作业类型并填写路线\n" +
                        "3. 发布任务后自动执行\n" +
                        "4. 完成后核验并上传记录",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "安全提示") {
                SafetyText("仅供有人看护的室内或封闭场地使用，不具备自动识别道路安全位置、障碍或来车的能力。")
                Spacer(Modifier.height(6.dp))
                SafetyText("作业前须人工现场确认环境安全。")
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
}
