package com.example.myapplication.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.vehicle.VehicleMode
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.WarningRed

/**
 * 常驻急停条（M08）：首页与各运动页底部常驻，拇指可达。
 *
 * **点击无二次确认** —— 安全指令不容迟滞（规格"停止优先"）；
 * 二次确认弹窗保留给"恢复至待命"。
 * 未收到设备确认前显示"停止未确认，需现场核查"，不得仅凭命令发出就显示安全停车。
 */
@Composable
fun EstopBar(
    locked: Boolean,
    stopUnconfirmed: Boolean,
    onEstop: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        if (stopUnconfirmed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarningRed.copy(alpha = 0.12f))
                    .padding(8.dp),
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = WarningRed, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "停止未确认，需现场核查",
                    color = WarningRed,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (locked) {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = WarningRed.copy(alpha = 0.35f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("急停已锁定 · 需现场核查后恢复", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onResume,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Text("恢复至待命", color = SafeGreen, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onEstop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WarningRed),
            ) {
                Icon(Icons.Default.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("紧急停止", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = "车身实体急停不由本按钮替代；停车不等于急停锁定。",
                color = NeutralGrey,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 是否处于急停锁定（供各页判断禁用矩阵） */
fun isEstopLocked(mode: VehicleMode?, locked: Boolean?): Boolean =
    locked == true || mode == VehicleMode.SAFE_STOP
