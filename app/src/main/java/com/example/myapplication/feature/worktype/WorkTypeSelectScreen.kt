package com.example.myapplication.feature.worktype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.ui.PageTitle
import com.example.myapplication.core.ui.PrimaryActionButton
import com.example.myapplication.core.ui.SectionCard
import com.example.myapplication.core.ui.StatusChip
import com.example.myapplication.navigation.WorkType
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg

/**
 * 登录后的作业类型选择页：先确定本次做三角牌单设备作业，还是锥桶协同布设。
 *
 * 这里不读取任何设备连接状态 —— 两类作业各自进入自己的设备连接页。
 */
@Composable
fun WorkTypeSelectScreen(
    current: WorkType?,
    onSelect: (WorkType) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            text = "选择作业类型",
            subtitle = "先确定本次作业，再进入对应的设备连接与任务发布",
        )

        SectionCard(title = "三脚架布设") {
            Text(
                "单台三角警示牌设备",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "连接 1 台三角牌设备后，发布定距路线任务并在设备上自动执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current == WorkType.TRIANGLE) {
                Spacer(Modifier.height(6.dp))
                StatusChip("当前类型", InfoBlue, InfoBlueBg)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "设备连接 → 三角牌作业首页 → 任务发布 → 任务执行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryActionButton(
                text = if (current == WorkType.TRIANGLE) "进入三脚架作业" else "选择三脚架布设",
                onClick = { onSelect(WorkType.TRIANGLE) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = "锥桶协同布设") {
            Text(
                "1 主桶 + 1~5 从桶",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "先绑定/选择锥桶设备，再发布协同布设任务；主桶与从桶按自身行程逐级启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current == WorkType.CONE) {
                Spacer(Modifier.height(6.dp))
                StatusChip("当前类型", InfoBlue, InfoBlueBg)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "锥桶设备连接 → 锥桶作业首页 → 布设任务发布 → 布设执行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryActionButton(
                text = if (current == WorkType.CONE) "进入锥桶作业" else "选择锥桶协同布设",
                onClick = { onSelect(WorkType.CONE) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            "两类作业的设备连接与任务发布相互独立。切换作业类型不会取消正在执行的三角牌任务或锥桶布设事件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
    }
}
