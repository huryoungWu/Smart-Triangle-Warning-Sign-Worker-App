package com.example.myapplication.core.data.device

import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.model.vehicle.TaskKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 真实设备通信（M01）：手机连接 TriangleCar 本地 Wi-Fi 后访问 192.168.4.1。
 *
 * 注意：Android 9+ 默认禁止明文 HTTP，必须配 `res/xml/network_security_config.xml`
 * 放行 192.168.4.1（见改造计划）。全部请求跑在 Dispatchers.IO。
 */
class HttpDeviceRepository(
    private val baseUrl: String = BASE_URL,
    private val connectTimeoutMs: Int = 1500,
    private val readTimeoutMs: Int = 2000,
) : DeviceRepository {

    override suspend fun fetchStatus(): Result<DeviceSnapshot> = withContext(Dispatchers.IO) {
        runCatching { DeviceJson.parseStatus(request("GET", "$baseUrl/status", body = null)) }
    }

    override suspend fun sendMove(dir: MoveDir): Result<CmdAck> = postCmd(
        JSONObject().put("type", "move").put("dir", dir.dirToken),
    )

    override suspend fun sendStop(): Result<CmdAck> = postCmd(JSONObject().put("type", "stop"))

    override suspend fun sendEstop(): Result<CmdAck> = postCmd(JSONObject().put("type", "estop"))

    override suspend fun sendResume(): Result<CmdAck> = postCmd(JSONObject().put("type", "resume"))

    override suspend fun startTask(kind: TaskKind, targetM: Double): Result<CmdAck> = postCmd(
        JSONObject().put("type", "task_start").put("kind", kind.token).put("targetM", targetM),
    )

    override suspend fun cancelTask(): Result<CmdAck> = postCmd(JSONObject().put("type", "task_cancel"))

    override suspend fun startRecover(): Result<CmdAck> = postCmd(JSONObject().put("type", "recover"))

    override suspend fun setLight(mode: LightMode): Result<CmdAck> =
        postJson("$baseUrl/light", JSONObject().put("mode", mode.token))

    override suspend fun resetOdo(): Result<CmdAck> = postCmd(JSONObject().put("type", "odo_reset"))

    private suspend fun postCmd(body: JSONObject): Result<CmdAck> = postJson("$baseUrl/cmd", body)

    private suspend fun postJson(path: String, body: JSONObject): Result<CmdAck> =
        withContext(Dispatchers.IO) {
            runCatching { DeviceJson.parseAck(request("POST", path, body.toString())) }
        }

    /** 单次 HTTP 请求；超时/IO 异常原样抛出交由上层判定失联或结果未知 */
    private fun request(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("设备返回 HTTP $code：$text")
            }
            return text
        } catch (e: SocketTimeoutException) {
            throw IOException("设备应答超时（${readTimeoutMs}ms）", e)
        } finally {
            conn.disconnect()
        }
    }

    private val MoveDir.dirToken: String
        get() = when (this) {
            MoveDir.FWD -> "fwd"
            MoveDir.BACK -> "back"
            MoveDir.LEFT -> "left"
            MoveDir.RIGHT -> "right"
        }

    private val TaskKind.token: String
        get() = when (this) {
            TaskKind.FIXED_DISTANCE -> "fixed_distance"
            TaskKind.ONE_TOUCH -> "one_touch"
            TaskKind.RECOVER -> "recover"
        }

    private val LightMode.token: String
        get() = when (this) {
            LightMode.OFF -> "off"
            LightMode.ON -> "on"
            LightMode.BLINK -> "blink"
        }

    companion object {
        const val BASE_URL = "http://192.168.4.1"
    }
}
