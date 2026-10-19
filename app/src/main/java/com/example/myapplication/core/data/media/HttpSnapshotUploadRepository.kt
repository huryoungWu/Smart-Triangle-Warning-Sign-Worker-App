package com.example.myapplication.core.data.media

import com.example.myapplication.core.data.api.SnapshotUploadRepository
import com.example.myapplication.core.model.media.TaskSnapshot
import com.example.myapplication.core.util.FALLBACK_SERVER_BASE_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 快照上传实现：`POST /api/v1/snapshots`（multipart/form-data，见 docs/后端接口设计文档.md 第 9 节）。
 *
 * 约定（当前阶段）：
 * - **快照图片由服务端从视频流"截帧"**（统一约定）；APP 仅上报元数据（事件/时间/任务/设备）
 * - 上传成功后服务端将其登记为风险事件（风险页 `source=worker`）
 * - 接真机摄像头后（或改为 APP 侧截帧时）：在本方法 multipart 中补 `image` 文件部分即可，链路不变
 */
class HttpSnapshotUploadRepository(
    /** 每次请求时解析（服务端地址可在「设置 → 服务器地址」运行时更改） */
    private val baseUrlProvider: () -> String = { FALLBACK_SERVER_BASE_URL },
    private val tokenProvider: () -> String? = { null },
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 5000,
) : SnapshotUploadRepository {

    /** 保持 `baseUrl` 这个名字：下面 `"$baseUrl/api/v1/..."` 插值点无需改动 */
    private val baseUrl: String get() = baseUrlProvider()


    override suspend fun upload(snapshot: TaskSnapshot, deviceId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val boundary = "----ZT-${UUID.randomUUID().toString().take(12)}"
                val out = ByteArrayOutputStream()
                fun writeText(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

                listOfNotNull(
                    "id" to snapshot.id,
                    "deviceId" to deviceId,
                    "taskName" to snapshot.taskName,
                    // 作业车道（第几车道）：随任务带入，服务端登记到风险事件；未填写则不上报
                    snapshot.lane?.let { "lane" to it.toString() },
                    "eventLabel" to snapshot.eventLabel,
                    "capturedAt" to snapshot.capturedAt.toString(),
                    "note" to snapshot.note,
                ).forEach { (name, value) ->
                    writeText("--$boundary\r\n")
                    writeText("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    writeText(value)
                    writeText("\r\n")
                }
                // 预留：接真机/改为 APP 侧截帧时，在此追加 image 文件部分（当前由服务端从视频流截帧）
                writeText("--$boundary--\r\n")
                val body = out.toByteArray()

                val conn = URL("$baseUrl/api/v1/snapshots").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    tokenProvider()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                    conn.outputStream.use { it.write(body) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw IOException("服务端返回 HTTP $code：$text")
                    JSONObject(text).optString("id")
                } finally {
                    conn.disconnect()
                }
            }
        }
}
