package com.example.myapplication.core.model.media

import java.util.UUID

/**
 * 任务过程快照（小车运行中抓拍）。
 *
 * 快照用于**预警信息**：把图片连同时间、事件类型等信息一起传给过往司机。
 * ⚠️ 当前无真实摄像头，图片为占位；接通车载摄像头后在 [imageUri] 存实际图片地址。
 */
data class TaskSnapshot(
    val id: String = UUID.randomUUID().toString().take(8),
    /** 抓拍时间 */
    val capturedAt: Long,
    /** 所属任务名 */
    val taskName: String,
    /** 作业车道（第几车道）；随所属任务带入，用于风险事件标注 */
    val lane: Int? = null,
    /** 事件类型：任务执行中 / 设备已到位 / 异常急停 …… */
    val eventLabel: String,
    /** 图片（当前为占位；接摄像头后为实际图片路径/URI） */
    val imageUri: String? = null,
    val note: String = "",
)

/**
 * 预警信息（发给过往司机）。
 * 上传接口先保留，暂不实现。
 */
data class WarningPayload(
    val snapshotId: String,
    val capturedAt: Long,
    val eventLabel: String,
    val title: String,
    val description: String,
    /** 作业车道（第几车道）；随快照/任务带入 */
    val lane: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)
