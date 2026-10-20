package com.example.myapplication.core.data.deployment

import com.example.myapplication.core.data.api.DeploymentRepository
import com.example.myapplication.core.model.deployment.DeploymentDeviceSummary
import com.example.myapplication.core.model.deployment.DeploymentDraft
import com.example.myapplication.core.model.deployment.DeploymentEvent
import com.example.myapplication.core.model.deployment.DeploymentRecordSummary
import com.example.myapplication.core.model.deployment.DeploymentRole
import com.example.myapplication.core.model.deployment.DeviceSet
import com.example.myapplication.core.model.deployment.SkipDisposition
import com.example.myapplication.core.util.FALLBACK_SERVER_BASE_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * 锥桶协同布设服务端仓库（手机 → 服务端，服务端中继设备）。
 *
 * 接口见 docs/锥桶协同布设功能需求规格说明书.md 第 9 章 / tools/mock-server/deployment.js。
 */
class ServerDeploymentRepository(
    private val baseUrlProvider: () -> String = { FALLBACK_SERVER_BASE_URL },
    private val tokenProvider: () -> String? = { null },
    private val connectTimeoutMs: Int = 1500,
    private val readTimeoutMs: Int = 3000,
) : DeploymentRepository {

    private val baseUrl: String get() = baseUrlProvider()

    override suspend fun listDevices(role: DeploymentRole?): Result<List<DeploymentDeviceSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val suffix = role?.let { "?role=${it.token}" } ?: ""
                DeploymentJson.parseDeviceList(
                    JSONObject(request("GET", "$baseUrl/api/v1/devices$suffix", null)),
                )
            }
        }

    override suspend fun getDeviceSet(masterCode: String): Result<DeviceSet> =
        withContext(Dispatchers.IO) {
            runCatching {
                DeploymentJson.parseDeviceSet(
                    JSONObject(
                        request(
                            "GET",
                            "$baseUrl/api/v1/device-sets?master_code=${enc(masterCode)}",
                            null,
                        ),
                    ),
                )
            }
        }

    override suspend fun bindDevice(deviceCode: String, bindingCode: String): Result<DeploymentDeviceSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("device_code", deviceCode)
                    .put("binding_code", bindingCode)
                    .put("device_type", "cone")
                val o = JSONObject(request("POST", "$baseUrl/api/v1/devices/bind", body.toString()))
                val d = o.optJSONObject("device") ?: JSONObject()
                DeploymentDeviceSummary(
                    deviceCode = d.optString("device_code", deviceCode),
                    name = d.optString("name", deviceCode),
                    role = DeploymentRole.fromToken(d.optString("role")),
                    setId = d.optString("setId").takeIf { it.isNotBlank() && it != "null" },
                    masterCode = d.optString("masterCode").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }

    override suspend fun unbindDevice(deviceCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                request("DELETE", "$baseUrl/api/v1/devices/${enc(deviceCode)}/bind", null)
                Unit
            }
        }

    override suspend fun activeDeployment(): Result<DeploymentEvent?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val o = JSONObject(
                    request("GET", "$baseUrl/api/v1/deployments?state=active&page=1&size=1", null),
                )
                val items = o.optJSONArray("items") ?: JSONArray()
                val first = items.optJSONObject(0) ?: return@runCatching null
                val id = first.optString("deploymentId", first.optString("deployment_id", ""))
                if (id.isBlank()) null else getDeploymentSync(id)
            }
        }

    override suspend fun listDeployments(state: String?, page: Int, size: Int): Result<List<DeploymentRecordSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = buildString {
                    append("?page=").append(page).append("&size=").append(size)
                    if (!state.isNullOrBlank()) append("&state=").append(enc(state))
                }
                DeploymentJson.parseRecordList(JSONObject(request("GET", "$baseUrl/api/v1/deployments$q", null)))
            }
        }

    override suspend fun createDeployment(draft: DeploymentDraft, clientRequestId: String): Result<DeploymentEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val L = draft.L ?: throw IOException("L 未填写")
                val d = draft.d ?: throw IOException("d 未填写")
                val slaves = JSONArray()
                draft.slaves.sortedBy { it.order }.forEach { s ->
                    slaves.put(JSONObject().put("deviceCode", s.device.deviceCode).put("order", s.order))
                }
                val body = JSONObject()
                    .put("clientRequestId", clientRequestId)
                    .put("name", draft.name.ifBlank { "锥桶协同布设" })
                    .put("lane", draft.lane ?: JSONObject.NULL)
                    .put("note", draft.note)
                    .put("masterCode", draft.master?.deviceCode ?: throw IOException("主桶未选择"))
                    .put("slaves", slaves)
                    .put("L", L)
                    .put("d", d)
                DeploymentJson.parseEvent(
                    JSONObject(request("POST", "$baseUrl/api/v1/deployments", body.toString())),
                )
            }
        }

    override suspend fun startDeployment(deploymentId: String, commandId: String): Result<DeploymentEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("commandId", commandId)
                    .put("confirmed", true)
                    .put(
                        "precheck",
                        JSONObject()
                            .put("startLineConfirmed", true)
                            .put("orderConfirmed", true)
                            .put("safetyConfirmed", true),
                    )
                request("POST", "$baseUrl/api/v1/deployments/${enc(deploymentId)}/start", body.toString())
                getDeploymentSync(deploymentId)
            }
        }

    override suspend fun getDeployment(deploymentId: String): Result<DeploymentEvent> =
        withContext(Dispatchers.IO) { runCatching { getDeploymentSync(deploymentId) } }

    private fun getDeploymentSync(deploymentId: String): DeploymentEvent =
        DeploymentJson.parseEvent(
            JSONObject(request("GET", "$baseUrl/api/v1/deployments/${enc(deploymentId)}", null)),
        )

    override suspend fun pause(deploymentId: String, reason: String): Result<Unit> =
        control(deploymentId, "pause") { it.put("reason", reason) }

    override suspend fun resume(deploymentId: String): Result<Unit> =
        control(deploymentId, "resume") { it.put("confirmed", true) }

    override suspend fun abort(deploymentId: String, reason: String): Result<Unit> =
        control(deploymentId, "abort") { it.put("reason", reason) }

    override suspend fun retry(
        deploymentId: String,
        type: String,
        deviceCode: String,
        originalCommandId: String?,
        reportId: String?,
    ): Result<Unit> = control(deploymentId, "retry") {
        it.put("requestId", UUID.randomUUID().toString())
            .put("type", type)
            .put("deviceCode", deviceCode)
        if (originalCommandId != null) it.put("originalCommandId", originalCommandId)
        if (reportId != null) it.put("reportId", reportId)
    }

    override suspend fun skip(
        deploymentId: String,
        deviceCode: String,
        reason: String,
        disposition: SkipDisposition,
        note: String,
    ): Result<Unit> = control(deploymentId, "skip") {
        it.put("skipRequestId", UUID.randomUUID().toString())
            .put("deviceCode", deviceCode)
            .put("reason", reason)
            .put("disposition", disposition.token)
            .put("note", note)
            .put(
                "confirmations",
                JSONObject()
                    .put("deviceStoppedOrRemoved", true)
                    .put("nextPathSafe", true)
                    .put("understandException", true),
            )
    }

    override suspend fun verify(deploymentId: String, deviceCode: String, pass: Boolean, note: String): Result<Unit> =
        control(deploymentId, "verify") {
            it.put("deviceCode", deviceCode)
                .put("result", if (pass) "pass" else "fail")
                .put("note", note)
        }

    override suspend fun complete(deploymentId: String): Result<Unit> =
        control(deploymentId, "complete") {}

    /** 控制动作统一出口：commandId 由 APP 生成，服务端幂等 */
    private suspend fun control(
        deploymentId: String,
        action: String,
        fill: (JSONObject) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("commandId", UUID.randomUUID().toString())
            fill(body)
            request("POST", "$baseUrl/api/v1/deployments/${enc(deploymentId)}/$action", body.toString())
            Unit
        }
    }

    private fun request(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
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
                throw IOException(serverErrorMessage(code, text))
            }
            return text
        } catch (e: SocketTimeoutException) {
            throw IOException("服务端应答超时（${readTimeoutMs}ms）", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun serverErrorMessage(code: Int, text: String): String =
        runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "服务端返回 HTTP $code：$text"

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
}
