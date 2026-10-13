package com.example.myapplication.core.model.vehicle

/** 车辆模式（M02）：以设备返回值为准 */
enum class VehicleMode(val label: String) {
    STANDBY("待命"),
    MANUAL("手动"),
    AUTO("自动"),
    SAFE_STOP("安全停止"),
}

/** 运动状态（M02） */
enum class MotionState(val label: String) {
    IDLE("静止"),
    MOVING("行驶中"),
    FAULT("故障"),
}

/** 灯光模式（M07）：三者互斥；灯光与运动分离 */
enum class LightMode(val label: String) {
    OFF("关闭"),
    ON("常亮"),
    BLINK("双闪"),
}

/** 连接阶段（M01）：仅收到有效状态才显示"已连接" */
enum class ConnectionPhase(val label: String) {
    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    LOST("已断开／状态未确认"),
}

/** 命令状态分层（规格第 4 节）：已发出 → 设备已接收 → 正在执行 → 设备报告完成 → 人工现场核验 */
enum class CommandPhase(val label: String) {
    SENDING("已发出"),
    ACCEPTED("设备已接收"),
    EXECUTING("正在执行"),
    DEVICE_DONE("设备报告已完成"),
    VERIFIED("人工已核验"),
    FAILED("设备报告失败"),
    UNKNOWN("结果未知"),
    ;

    /** 终态：可再次发起新任务（否则布置完就卡死） */
    val isTerminal: Boolean
        get() = this == DEVICE_DONE || this == VERIFIED || this == FAILED || this == UNKNOWN
}

/** 停止来源（M08）：区分人工停止/失联保护/编码器异常/左右偏差/超时 */
enum class StopSource(val label: String) {
    NONE("无"),
    MANUAL("人工停止"),
    ESTOP("急停锁定"),
    LINK_LOSS("失联保护"),
    ENCODER("编码器异常"),
    DEVIATION("左右偏差"),
    TIMEOUT("超时"),
}

/** 手动遥控方向（M04） */
enum class MoveDir(val label: String) {
    FWD("前进"),
    BACK("后退"),
    LEFT("左转"),
    RIGHT("右转"),
}

/** 定距布置距离范围（米）：允许 10-100 自由输入 */
object DistanceRange {
    const val MIN_M = 10
    const val MAX_M = 100
    const val DEFAULT_M = 30
}

enum class TaskKind(val label: String, val token: String) {
    FIXED_DISTANCE("定距布置", "fixed_distance"),
    MANUAL("手动遥控", "manual"),
    ONE_TOUCH("一键布置", "one_touch"),
    RECOVER("自动回收", "recover"),
}

/** 任务来源（M06）：APP 启动或车身实体按钮启动 */
enum class TaskSource(val label: String) {
    APP("手机 APP"),
    BUTTON("车身按钮"),
}

enum class TaskRunState(val label: String) {
    IDLE("无任务"),
    RUNNING("运行中"),
    DONE("设备报告已完成"),
    FAILED("失败"),
    CANCELED("已取消"),
}

enum class CommandKind(val label: String) {
    MOVE("方向指令"),
    STOP("普通停车"),
    ESTOP("紧急停止"),
    RESUME("恢复待命"),
    TASK_START("启动任务"),
    TASK_CANCEL("取消任务"),
    LIGHT("灯光设置"),
    ODO_RESET("计数清零"),
    SEGMENT_CONFIRM("确认执行本段"),
}

/** 一键布置阶段（M06）：阶段状态由设备回报，不以 APP 计时为准 */
enum class OneTouchStage(val label: String, val step: Int) {
    LIGHTING("灯光双闪", 1),
    COUNTDOWN("提示倒计时", 2),
    MOVING("低速直行", 3),
    PARKED("停车续闪", 4),
    WAITING_VERIFY("待人工核验", 5),
}
