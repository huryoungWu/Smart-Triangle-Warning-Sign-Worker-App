package com.example.myapplication.core.data.upload

import com.example.myapplication.core.data.api.TaskUploadRepository
import com.example.myapplication.core.data.api.UploadResult
import com.example.myapplication.core.model.vehicle.WorkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真实服务器实现：`POST /api/v1/tasks`（见 docs/后端接口设计文档.md 第 8 节）。
 * 载荷契约见 [TaskUploadJson]；建议携带 `Idempotency-Key: <record.id>` 支持安全重试。
 */
class HttpTaskUploadRepository(
    private val baseUrl: String,
    /** 登录 token 提供者（未登录返回 null）；由 ServiceLocator 注入 */
    private val tokenProvider: () -> String? = { null },
) : TaskUploadRepository {

    override suspend fun upload(record: WorkRecord): Result<UploadResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL("$baseUrl/api/v1/tasks").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Idempotency-Key", record.id)
                    tokenProvider()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                    conn.outputStream.use { it.write(TaskUploadJson.toJson(record).toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw IOException("服务器返回 HTTP $code：$text")
                    val o = JSONObject(text)
                    UploadResult(
                        serverId = o.optString("id", "srv-${record.id.take(8)}"),
                        uploadedAt = o.optLong("receivedAt", System.currentTimeMillis()),
                    )
                } finally {
                    conn.disconnect()
                }
            }
        }
}
