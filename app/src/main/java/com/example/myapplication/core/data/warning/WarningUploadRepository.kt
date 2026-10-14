package com.example.myapplication.core.data.warning

import com.example.myapplication.core.model.media.WarningPayload

/**
 * 预警信息上传接口 —— **上传给过往司机**。
 *
 * ⚠️ **接口已预留，暂不实现**（按需求：「预警信息的上传先保留接口先不做」）。
 * 后续接通预警发布平台（如路侧广播/诱导屏/高德交通事件）时实现 [HttpWarningUploadRepository] 即可。
 */
interface WarningUploadRepository {
    /**
     * 上传预警信息（快照 + 时间 + 事件类型 + 位置）给过往司机。
     * @return 成功返回预警单号
     */
    suspend fun uploadWarning(payload: WarningPayload): Result<String>
}

/** 占位实现：不做实际上传，返回成功便于流程演示 */
class StubWarningUploadRepository : WarningUploadRepository {
    override suspend fun uploadWarning(payload: WarningPayload): Result<String> {
        return Result.success("stub-warn-${payload.snapshotId.take(6)}")
    }
}

/** 真实实现（留口，当前不接） */
class HttpWarningUploadRepository(
    private val baseUrl: String = "https://api.example.com",
) : WarningUploadRepository {
    override suspend fun uploadWarning(payload: WarningPayload): Result<String> {
        // TODO: POST $baseUrl/api/v1/warnings
        return Result.failure(UnsupportedOperationException("预警上传尚未接入"))
    }
}
