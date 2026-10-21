package com.example.myapplication.core.data.device

import com.example.myapplication.core.data.api.CmdAck
import com.example.myapplication.core.data.api.DeviceConnectRequest
import com.example.myapplication.core.data.api.DeviceRepository
import com.example.myapplication.core.model.vehicle.DeviceSnapshot
import com.example.myapplication.core.model.vehicle.DeviceSummary
import com.example.myapplication.core.model.vehicle.LightMode
import com.example.myapplication.core.model.vehicle.MoveDir
import com.example.myapplication.core.model.vehicle.RouteSegment
import com.example.myapplication.core.model.vehicle.SegmentAction
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.model.vehicle.TaskMode
import com.example.myapplication.core.util.FALLBACK_SERVER_BASE_URL
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
 * - `GET  /api/v1/device/status?device_code=` —— 状态（复用 [DeviceJson] 解析：小写 token 与枚举名均兼容）
 * - `POST /api/v1/device/command`         —— 全部控制命令（type 见文档 6.2 全表）
 * - `GET  /api/v1/devices`                —— 设备列表（台账）
 *
 * 联调：`tools/mock-server/server.js`（零依赖 Node Mock，已内置小车状态机模拟）。
 */
class ServerDeviceRepository(
    /** 每次请求时解析（服务端地址可在「设置 → 服务器地址」运行时更改） */
    private val baseUrlProvider: () -> String = { FALLBACK_SERVER_BASE_URL },
    /** 登录 token 提供者（未登录返回 null）；由 ServiceLocator 注入 */
    private val tokenProvider: () -> String? = { null },
    private val connectTimeoutMs: Int = 1500,
    private val readTimeoutMs: Int = 2000,
) : DeviceRepository {

    /** 保持 `baseUrl` 这个名字：下面所有 `"$baseUrl/api/v1/..."` 插值点无需改动 */
    private val baseUrl: String get() = baseUrlProvider()

    /** 当前选中设备（连接页下拉后由 ViewModel 写入，用法同 [FakeDeviceRepository]） */
    @Volatile
    var selectedDeviceCode: String = DEFAULT_DEVICE_CODE

    /**
     * 服务端已切换：设备编号属于**旧**服务端，必须复位交由连接页重新选择。
     * 不复位的话，新服务端上可能没有这台设备（状态读取永远失败 → 卡在"未连接"），
     * 也可能恰好存在但**不属于当前账号** —— 而 `/device/status`、`/device/command`
     * 都只带 device_code，会向别人的设备下发命令。
     *
     * `phoneLat` / `phoneLng` **不复位**：那是手机自身位置（客户端事实），与服务端无关。
     */
    fun resetForNewServer() {
        selectedDeviceCode = DEFAULT_DEVICE_CODE
    }

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
            // 单设备连接页只列普通三角牌设备；锥桶走协同布设向导
            val arr = JSONObject(request("GET", "$baseUrl/api/v1/devices?device_type=triangle", null))
                .optJSONArray("devices") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    add(
                        DeviceSummary(
                            id = d.optString("device_code"),
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
     * 由连接页原样展示；成功后由 ViewModel 读取一次状态（之后仅在手动刷新时再读）。
     */
    override suspend fun connect(request: DeviceConnectRequest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("device_code", request.deviceCode)
                .put("binding_code", request.bindingCode)
                .put("device_type", request.deviceType)
            // 未登录（跳过登录）时显式上报 null（服务端不再补默认值；正式服务端由 Bearer 鉴权兜底）
            body.put("username", request.username?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            body.put("password", request.password?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            val device = JSONObject(request("POST", "$baseUrl/api/v1/devices/bind", body.toString()))
                .optJSONObject("device")
            // 以服务端返回的设备编号为准（编号可能有规范化），保证后续状态查询/命令对应同一台设备
            val boundId = device?.optString("device_code")?.takeIf { it.isNotBlank() }
            if (boundId != null) selectedDeviceCode = boundId
        }
    }

    /** 读取一次设备状态：连接成功后读取一次，之后仅在用户手动刷新时调用。 */
    override suspend fun fetchStatus(): Result<DeviceSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val anchor = if (phoneLat.isFinite() && phoneLng.isFinite()) {
                "&lat=$phoneLat&lng=$phoneLng" // 手机位置锚点（可选）：服务端把车端 GPS 锚定在附近
            } else {
                ""
            }
            DeviceJson.parseStatus(
                request("GET", "$baseUrl/api/v1/device/status?device_code=$selectedDeviceCode$anchor", null),
            )
        }
    }

    // ══════════════ 控制命令（统一走 POST /api/v1/device/command）══════════════

    override suspend fun sendMove(dir: MoveDir): Result<CmdAck> =
        postCommand(JSONObject().put("type", "move").put("dir", dir.dirToken))

    override suspend fun sendStop(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "stop"))

    /** 手动微调结束：不取消正在执行的任务。 */
    override suspend fun sendManualStop(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "manual_stop"))

    override suspend fun sendEstop(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "estop"))

    override suspend fun sendResume(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "resume"))

    /**
     * 单段快捷任务（形态 1）：所有字段显式上报。
     * `segments=[]` 表示单段快捷；`light`/`lane` 该路径无值，显式上报 null。
     */
    override suspend fun startTask(kind: TaskKind, targetM: Double): Result<CmdAck> =
        postCommand(
            JSONObject()
                .put("type", "task_start")
                .put("kind", kind.token)
                .put("targetM", targetM)
                .put("mode", TaskMode.AUTO.token)
                .put("light", JSONObject.NULL)
                .put("lane", JSONObject.NULL)
                .put("segments", JSONArray()),
        )

    override suspend fun cancelTask(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "task_cancel"))

    /** 用户现场确认任务完成：停止运动并把任务置为 done。 */
    override suspend fun completeTask(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "task_complete"))

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
        // 目标里程：所有前进段之和（与车端 targetM 口径一致）；不再依赖服务端计算
        val targetM = segments
            .filter { it.action == SegmentAction.FWD }
            .sumOf { it.value }
        val body = JSONObject()
            .put("type", "task_start")
            .put("kind", kind.token)
            .put("targetM", targetM)
            .put("mode", mode.token)
            .put("light", light.token)
            .put("segments", segArr)
        // 作业车道：始终显式上报；未填写用 null（服务端按未标注处理）
        body.put("lane", lane ?: JSONObject.NULL)
        return postCommand(body)
    }

    override suspend fun confirmSegment(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "segment_confirm"))

    override suspend fun setLight(mode: LightMode): Result<CmdAck> =
        postCommand(JSONObject().put("type", "light").put("mode", mode.token))

    override suspend fun resetOdo(): Result<CmdAck> =
        postCommand(JSONObject().put("type", "odo_reset"))

    /** 命令统一出口：补 device_code 与客户端时间戳，解析 {accepted, cmdId, reason} */
    private suspend fun postCommand(body: JSONObject): Result<CmdAck> = withContext(Dispatchers.IO) {
        runCatching {
            body.put("device_code", selectedDeviceCode).put("ts", System.currentTimeMillis())
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

    companion object {
        /** 内置演示设备（Mock 服务端默认账号 demo 名下）；换服务端后复位到它 */
        const val DEFAULT_DEVICE_CODE = "TC-001"
    }
}
