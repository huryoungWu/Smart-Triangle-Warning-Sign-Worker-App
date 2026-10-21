package com.example.myapplication.core.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** 「测试连接」结果 */
sealed interface ServerProbeResult {
    /** 服务端在，且确实是本项目的 API（[version] 为服务端上报的版本，可能为空） */
    data class Ok(val version: String?) : ServerProbeResult

    /** 有 HTTP 响应但不是 2xx：**网络是通的**，只是接口不对 / 需要鉴权 —— 措辞上要和"不可达"区分开 */
    data class HttpError(val code: Int) : ServerProbeResult

    /** 连不上：网络不可达 / 端口没监听 / 被系统明文策略拦截 */
    data class Unreachable(val reason: String) : ServerProbeResult
}

/**
 * 服务器连通性探测（设置 → 服务器地址 的「测试连接」）。
 *
 * **不读当前配置**：接收候选 URL 参数 —— 用户点「测试连接」时地址还没保存，
 * 探的必须是输入框里的值。
 *
 * 选 `GET /api/v1/app/version` 而不是另两个候选：
 * - `GET /`：只是 Mock 的人工自检页，不在接口契约里；真服务端/nginx 会返回 404 或 HTML → 误报失败
 * - `GET /api/v1/devices`：需要鉴权语义，未登录时正式服务端回 401 → 明明通了却报失败
 * - `/app/version`：契约内、无需鉴权、载荷几十字节，还能顺手显示服务端版本
 */
class ServerProbe(
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 3000,
) {

    suspend fun check(baseUrl: String): ServerProbeResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$baseUrl/api/v1/app/version").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code !in 200..299) return@withContext ServerProbeResult.HttpError(code)
                val text = conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                ServerProbeResult.Ok(
                    runCatching { JSONObject(text).optString("latestVersion").takeIf { it.isNotBlank() } }
                        .getOrNull(),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrElse { describe(it) }
    }

    private fun describe(e: Throwable): ServerProbeResult = when (e) {
        is SocketTimeoutException -> ServerProbeResult.Unreachable("连接超时（$connectTimeoutMs ms）")

        is UnknownHostException -> ServerProbeResult.Unreachable("主机名解析失败：${e.message.orEmpty()}")

        // Android 17（API 37）本地网络权限：未授权时局域网访问被系统拦截
        is SecurityException ->
            ServerProbeResult.Unreachable("缺少本地网络权限（Android 17+），请允许后重试")

        // Android 9+ 的明文策略：network_security_config 不放行时，请求在 HttpURLConnection
        // 层就被拒（抛 java.net.UnknownServiceException，是 IOException 的子类），不是服务端的问题。
        // 这是本功能最容易踩的坑，必须给出可读提示。
        is java.io.IOException -> {
            val msg = e.message.orEmpty()
            when {
                msg.contains("CLEARTEXT", ignoreCase = true) ->
                    ServerProbeResult.Unreachable("明文 HTTP 被系统安全策略拦截（debug 包已全面放行，见 network_security_config）")

                msg.contains("EPERM", ignoreCase = true) ||
                    msg.contains("Operation not permitted", ignoreCase = true) ->
                    ServerProbeResult.Unreachable("缺少本地网络权限（Android 17+），请允许后重试")

                else -> ServerProbeResult.Unreachable(msg.ifBlank { e.javaClass.simpleName })
            }
        }

        else -> ServerProbeResult.Unreachable(e.message ?: e.javaClass.simpleName)
    }
}
