package com.example.myapplication.core.data.device

import com.example.myapplication.core.data.api.CmdAck
import com.example.myapplication.core.data.api.DeviceConnectRequest
import com.example.myapplication.core.data.api.DeviceRepository
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.DeviceSummary
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 服务端通信（《三层通信架构方案》：手机 → 服务端 → 小车）。
 *
 * 与 [HttpDeviceRepository]（手机直连小车，旧方案）**接口形状完全一致**，
 * ViewModel / 界面不感知差异；切换见 [com.example.myapplication.di.ServiceLocator.TRANSPORT]。
 *
 * 协议见 `docs/后端接口设计文档.md`：
 * - `GET  /api/v1/device/status?deviceId=` —— 状态（复用 [DeviceJson] 解析：小写 token 与枚举名均兼容）
 * - `POST /api/v1/device/command`         —— 全部控制命令（type 见文档 6.2 全表）
 * - `GET  /api/v1/devices`                —— 设备列表（台账）
 *
 * 联调：`tools/mock-server/server.js`（零依赖 Node Mock，已内置小车状态机模拟）。
 */
class ServerDeviceRepository(
    private val baseUrl: String,
    /** 登录 token 提供者（未登录返回 null）；由 ServiceLocator 注入 */
    private val tokenProvider: () -> String? = { null },
    private val connectTimeoutMs: Int = 1500,
    private val readTimeoutMs: Int = 2000,
) : DeviceRepository {

    /** 当前选中设备（连接页下拉后由 ViewModel 写入，用法同 [FakeDeviceRepository]） */
    @Volatile
    var selectedDeviceId: String = "TC-001"

    /**
     * 手机当前位置（GCJ-02，由 MapViewModel 实时写入，用法同 Fake 的 gpsBaseGcj02）。
     * 随状态查询上报（`&lat=&lng=`）：服务端据此把"车端 GPS"锚定在作业人员附近（联调演示约定），
     * 没有手机定位时不上报，服务端回落到其默认基准点。
     */
    @Volatile
    var phoneLat: Double = Double.NaN

    @Volatile
    var phoneLng: Double = Double.NaN

    override suspend fun listDevices(): List<DeviceSummary> = withContext(Dispatchers.IO) {
        // 服务端不可达时按空列表降级（连接页显示「暂无可用设备」，连接操作会被禁用）
        runCatching {
            val arr = JSONObject(request("GET", "$baseUrl/api/v1/devices", null))
                .optJSONArray("devices") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    add(
                        DeviceSummary(
                            id = d.optString("deviceId"),
                            name = d.optString("name"),
                            started = d.optBoolean("started", false),
                            available = d.optBoolean("available", true),
                            // null = 无来源（界面显示「未知」）
                            batteryPercent = if (d.isNull("batteryPercent")) -1 else d.optInt("batteryPercent", -1),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    /**
     * 连接设备（连接即绑定）：`POST /api/v1/devices/bind`，5 个字段
     * `username` / `password` / `device_code` / `binding_code` / `device_type`（见 docs 第 4.2 节）。
     *
     * 失败时抛出**服务端可读原因**（如「绑定码不能为空」「该设备已被其他账号绑定」），
     * 由连接页原样展示；成功后才由 ViewModel 启动 1Hz 状态轮询。
     */
    override suspend fun connect(request: DeviceConnectRequest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("device_code", request.deviceCode)
                .put("binding_code", request.bindingCode)
                .put("device_type", request.deviceType)
            // 未登录（跳过登录）时省略账号密码：Mock 按默认账号处理，正式服务端由 Bearer 鉴权兜底
            request.username?.takeIf { it.isNotBlank() }?.let { body.put("username", it) }
            request.password?.takeIf { it.isNotBlank() }?.let { body.put("password", it) }
            val device = JSONObject(request("POST", "$baseUrl/api/v1/devices/bind", body.toString()))
                .optJSONObject("device")
            // 以服务端返回的设备编号为准（编号可能有规范化），保证后续状态查询/命令对应同一台设备
            val boundId = device?.optString("deviceId")?.takeIf { it.isNotBlank() }
            if (boundId != null) selectedDeviceId = boundId
        }
    }

    override suspend fun fetchStatus(): Result<DeviceSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val anchor = if (phoneLat.isFinite() && phoneLng.isFinite()) {
                "&lat=$phoneLat&lng=$phoneLng" // 手机位置锚点（可选）：服务端把车端 GPS 锚定在附近
            } else {
                ""
            }
            DeviceJson.parseStatus(
                request("GET", "$baseUrl/api/v1/device/status?deviceId=$selectedDeviceId$anchor", null),
            )
        }
    }

    // ══════════════ 控制命令（统一走 POST /api/v1/device/command）══════════════

    override suspend fun sendMove(dir: MoveDir): Result<CmdAck> =
        postCommand(JSONObject().put("type", "move").put("dir", dir.dirToken))

    override suspend fun sendStop(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "stop"))

    override suspend fun sendEstop(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "estop"))

    override suspend fun sendResume(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "resume"))

    override suspend fun startTask(kind: TaskKind, targetM: Double): Result<CmdAck> =
        postCommand(
            JSONObject().put("type", "task_start").put("kind", kind.token).put("targetM", targetM),
        )

    override suspend fun cancelTask(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "task_cancel"))

    override suspend fun startRecover(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "recover"))

    override suspend fun startRoute(
        kind: TaskKind,
        mode: TaskMode,
        segments: List<RouteSegment>,
        light: LightMode,
        lane: Int?,
    ): Result<CmdAck> {
        val segArr = JSONArray()
        segments.forEach { seg ->
            segArr.put(JSONObject().put("action", seg.action.token).put("value", seg.value))
        }
        val body = JSONObject()
            .put("type", "task_start")
            .put("kind", kind.token)
            .put("mode", mode.token)
            .put("light", light.token)
            .put("segments", segArr)
        // 作业车道（第几车道）：未填写则整字段省略（服务端按未标注处理）
        lane?.let { body.put("lane", it) }
        return postCommand(body)
    }

    override suspend fun confirmSegment(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "segment_confirm"))

    override suspend fun setLight(mode: LightMode): Result<CmdAck> =
        postCommand(JSONObject().put("type", "light").put("mode", mode.token))

    override suspend fun resetOdo(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "odo_reset"))

    /** 命令统一出口：补 deviceId 与客户端时间戳，解析 {accepted, cmdId, reason} */
    private suspend fun postCommand(body: JSONObject): Result<CmdAck> = withContext(Dispatchers.IO) {
        runCatching {
            body.put("deviceId", selectedDeviceId).put("ts", System.currentTimeMillis())
            DeviceJson.parseAck(request("POST", "$baseUrl/api/v1/device/command", body.toString()))
        }
    }

    // ══════════════ HTTP 通路（超时/IO 异常原样抛出，交由上层判定失联或结果未知）══════════════

    private fun request(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            // 已登录时携带 Authorization（设备/命令按登录用户）；未登录（含跳过登录）不带 —— Mock 按默认用户处理
            tokenProvider()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                // 服务端错误体 {"error":{"message":...}} 优先取可读文案（连接失败/命令拒绝均原样展示给用户）
                throw IOException(serverErrorMessage(code, text))
            }
            return text
        } catch (e: SocketTimeoutException) {
            throw IOException("服务端应答超时（${readTimeoutMs}ms）", e)
        } finally {
            conn.disconnect()
        }
    }

    /** 错误体可读文案（无统一信封时回落到原始报文） */
    private fun serverErrorMessage(code: Int, text: String): String =
        runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "服务端返回 HTTP $code：$text"

    private val MoveDir.dirToken: String
        get() = when (this) {
            MoveDir.FWD -> "fwd"
            MoveDir.BACK -> "back"
            MoveDir.LEFT -> "left"
            MoveDir.RIGHT -> "right"
        }

    private val LightMode.token: String
        get() = when (this) {
            LightMode.OFF -> "off"
            LightMode.ON -> "on"
            LightMode.BLINK -> "blink"
        }
}
