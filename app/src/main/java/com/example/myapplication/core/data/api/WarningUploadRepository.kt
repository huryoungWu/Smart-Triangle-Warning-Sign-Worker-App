package com.example.myapplication.core.data.api

import com.example.myapplication.core.model.media.WarningPayload

/**
 * 预警信息上传接口 —— **上传给过往司机**。
 *
 * 实现分布（本文件为纯接口，不含实现）：
 * - [com.example.myapplication.core.data.warning.StubWarningUploadRepository] 占位实现
 * - [com.example.myapplication.core.data.warning.HttpWarningUploadRepository] 服务端 `POST /api/v1/warnings`（留口，未接）
 * 接口已预留（按需求「预警信息的上传先保留接口先不做」），后续接通预警发布平台时实现即可。
 */
interface WarningUploadRepository {
    /**
     * 上传预警信息（快照 + 时间 + 事件类型 + 位置）给过往司机。
     * @return 成功返回预警单号
     */
    suspend fun uploadWarning(payload: WarningPayload): Result<String>
}
