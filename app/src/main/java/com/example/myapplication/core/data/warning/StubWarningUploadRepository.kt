package com.example.myapplication.core.data.warning

import com.example.myapplication.core.data.api.WarningUploadRepository
import com.example.myapplication.core.model.media.WarningPayload

/** 占位实现：不做实际上传，返回成功便于流程演示。 */
class StubWarningUploadRepository : WarningUploadRepository {
    override suspend fun uploadWarning(payload: WarningPayload): Result<String> {
        return Result.success("stub-warn-${payload.snapshotId.take(6)}")
    }
}
