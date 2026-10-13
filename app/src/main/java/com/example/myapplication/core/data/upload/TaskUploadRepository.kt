package com.example.myapplication.core.data.upload

import com.example.myapplication.core.model.vehicle.WorkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 上传结果（服务器返回的单号与时间） */
data class UploadResult(val serverId: String, val uploadedAt: Long)

/**
 * 任务上传接口（第 ⑤ 步闭环）。
 *
 * **服务器尚未接入**：默认走 [StubTaskUploadRepository]（模拟应答，可注入失败供演示）。
 * 真实服务器就绪后切 [HttpTaskUploadRepository] 即可，UI 不变。
 *
 * ⚠️ 数据真实性：占位模式下界面**不得宣称「已上传平台」**，只说「已模拟上传」。
 */
interface TaskUploadRepository {
    suspend fun upload(record: WorkRecord): Result<UploadResult>
}

/** 占位实现（默认启用）：无服务器，模拟应答 */
class StubTaskUploadRepository : TaskUploadRepository {

    /** 演示用：置 true 则下一次上传返回失败 */
    var failNext = false

    override suspend fun upload(record: WorkRecord): Result<UploadResult> {
        delay(300)
        if (failNext) {
            failNext = false
            return Result.failure(IOException("演示占位：模拟上传失败"))
        }
        return Result.success(
            UploadResult("stub-${record.id.take(8)}", System.currentTimeMillis()),
        )
    }
}

/**
 * 真实服务器实现（留口，当前不接）。
 * 接口契约见 [TaskUploadJson]。
 */
class HttpTaskUploadRepository(
    private val baseUrl: String = "https://api.example.com",
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

/**
 * 上传载荷契约（给未来服务器照做）。
 *
 * ```
 * POST /api/v1/tasks
 * → 201 { "id": "srv-xxx", "receivedAt": 1710000000000 }
 * ```
 */
object TaskUploadJson {

    fun toJson(record: WorkRecord): String {
        val segArr = JSONArray()
        record.route.forEach { seg ->
            segArr.put(
                JSONObject()
                    .put("action", seg.action.token)
                    .put("value", seg.value),
            )
        }
        return JSONObject()
            .put("id", record.id)
            .put("name", record.name)
            .put("kind", record.kind.token)
            .put("mode", record.mode?.token)
            .put("startedAt", record.startedAt)
            .put("finishedAt", record.finishedAt)
            .put("segments", segArr)
            .put("targetM", record.targetM)
            .put("traveledM", record.traveledM)
            .put("deviceResult", record.deviceResult)
            .put("deviceResultNote", record.deviceResultNote)
            .put("verifyResult", record.verifyResult)
            .put("verifyNote", record.verifyNote)
            .put("abnormalNote", record.abnormalNote)
            .toString()
    }
}
