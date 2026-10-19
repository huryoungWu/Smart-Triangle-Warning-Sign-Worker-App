package com.example.myapplication.core.data.warning

import com.example.myapplication.core.data.api.WarningUploadRepository
import com.example.myapplication.core.model.media.WarningPayload
import com.example.myapplication.core.util.FALLBACK_SERVER_BASE_URL

/**
 * 真实实现（留口，暂未接入）：`POST /api/v1/warnings`（见 docs/后端接口设计文档.md 第 10 节）。
 * Mock 服务端（tools/mock-server）已实现该接口，接通 APP 调用时启用即可。
 */
class HttpWarningUploadRepository(
    /** 每次请求时解析（服务端地址可在「设置 → 服务器地址」运行时更改） */
    private val baseUrlProvider: () -> String = { FALLBACK_SERVER_BASE_URL },
) : WarningUploadRepository {

    /** 保持 `baseUrl` 这个名字：下面 `"$baseUrl/api/v1/..."` 插值点无需改动 */
    private val baseUrl: String get() = baseUrlProvider()

    override suspend fun uploadWarning(payload: WarningPayload): Result<String> {
        // TODO: POST $baseUrl/api/v1/warnings { snapshotId, capturedAt, eventLabel, title, description, lat, lng }
        return Result.failure(UnsupportedOperationException("预警上传尚未接入"))
    }
}
