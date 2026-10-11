package com.example.myapplication.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.core.model.vehicle.CommandPhase
import com.example.myapplication.core.model.vehicle.ConnectionPhase
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MotionState
import com.example.myapplication.core.model.vehicle.VehicleMode
import com.example.myapplication.ui.theme.InfoBlue
import com.example.myapplication.ui.theme.InfoBlueBg
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.SafeGreen
import com.example.myapplication.ui.theme.SafeGreenBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

/** 状态标签基元 */
@Composable
fun StatusChip(
    text: String,
    fg: Color,
    bg: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 连接状态（M01）：失联标"已断开／状态未确认" */
@Composable
fun ConnectionChip(phase: ConnectionPhase, modifier: Modifier = Modifier) {
    val (fg, bg) = when (phase) {
        ConnectionPhase.CONNECTED -> SafeGreen to SafeGreenBg
        ConnectionPhase.CONNECTING -> InfoBlue to InfoBlueBg
        ConnectionPhase.LOST -> WarningRed to WarningRedBg
        ConnectionPhase.DISCONNECTED -> NeutralGrey to NeutralGreyBg
    }
    StatusChip(phase.label, fg, bg, modifier)
}

/** 车辆模式（M02）：待命/手动/自动/安全停止 */
@Composable
fun VehicleModeChip(mode: VehicleMode, modifier: Modifier = Modifier) {
    val (fg, bg) = when (mode) {
        VehicleMode.STANDBY -> SafeGreen to SafeGreenBg
        VehicleMode.MANUAL -> InfoBlue to InfoBlueBg
        VehicleMode.AUTO -> WarnOrange to WarnOrangeBg
        VehicleMode.SAFE_STOP -> WarningRed to WarningRedBg
    }
    StatusChip(mode.label, fg, bg, modifier)
}

/** 灯光模式（M07）：与运动状态分开显示 */
@Composable
fun LightModeChip(mode: LightMode, modifier: Modifier = Modifier) {
    val (fg, bg) = when (mode) {
        LightMode.OFF -> NeutralGrey to NeutralGreyBg
        LightMode.ON -> InfoBlue to InfoBlueBg
        LightMode.BLINK -> WarnOrange to WarnOrangeBg
    }
    StatusChip("灯：${mode.label}", fg, bg, modifier)
}

/** 运动状态（M02） */
@Composable
fun MotionChip(state: MotionState, modifier: Modifier = Modifier) {
    val (fg, bg) = when (state) {
        MotionState.IDLE -> NeutralGrey to NeutralGreyBg
        MotionState.MOVING -> InfoBlue to InfoBlueBg
        MotionState.FAULT -> WarningRed to WarningRedBg
    }
    StatusChip(state.label, fg, bg, modifier)
}

/** 命令状态分层（规格第 4 节） */
@Composable
fun CommandPhaseChip(phase: CommandPhase, modifier: Modifier = Modifier) {
    val (fg, bg) = when (phase) {
        CommandPhase.SENDING -> WarnOrange to WarnOrangeBg
        CommandPhase.ACCEPTED -> InfoBlue to InfoBlueBg
        CommandPhase.EXECUTING -> InfoBlue to InfoBlueBg
        CommandPhase.DEVICE_DONE -> SafeGreen to SafeGreenBg
        CommandPhase.VERIFIED -> SafeGreen to SafeGreenBg
        CommandPhase.FAILED -> WarningRed to WarningRedBg
        CommandPhase.UNKNOWN -> WarningRed to WarningRedBg
    }
    StatusChip(phase.label, fg, bg, modifier)
}

/** C 阶段置灰徽标 */
@Composable
fun PendingHardwareChip(modifier: Modifier = Modifier) {
    StatusChip("待硬件验收", NeutralGrey, NeutralGreyBg, modifier)
}
