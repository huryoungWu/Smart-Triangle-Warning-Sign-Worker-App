package com.example.myapplication.core.model

/** 一次性全局告警/提示弹窗（FR-103/108/114、NFR-109） */
sealed interface AppDialog {
    val title: String

    /** 普通提示（确认型） */
    data class Alert(override val title: String, val message: String) : AppDialog

    /** 红色警示（硬件告警、急停等） */
    data class Warn(override val title: String, val message: String) : AppDialog

    /** 命令被拒/校验失败，展示具体原因 + 处置建议（规格：失败必须给出可读原因） */
    data class Reject(
        override val title: String,
        val reasons: List<String>,
        val advice: String,
    ) : AppDialog
}
