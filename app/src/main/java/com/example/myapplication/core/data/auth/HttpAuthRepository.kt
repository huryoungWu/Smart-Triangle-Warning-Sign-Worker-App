package com.example.myapplication.core.data.auth

import com.example.myapplication.BuildConfig
import com.example.myapplication.core.data.api.AuthRepository
import com.example.myapplication.core.data.api.AuthSession
import com.example.myapplication.core.data.api.AuthUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务端登录实现：`POST /api/v1/auth/login`（见 docs/后端接口设计文档.md 第 3 节）。
 *
 * 约定：
 * - token 保存在内存（应用重启需重新登录；持久化/自动刷新为后续增强）
 * - 登录失败会清空会话，避免带着旧身份继续
 * - 未登录（含「跳过登录」）时各 HTTP 仓库不带 Authorization —— 调试期服务端按默认用户
 *   `demo` 处理（Mock 宽松模式）；正式服务端会返回 401，届时需先登录
 */
class HttpAuthRepository(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 5000,
) : AuthRepository {

    @Volatile
    override var currentToken: String? = null
        private set

    @Volatile
    override var currentUser: AuthUser? = null
        private set

    override suspend fun login(username: String, password: String): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("username", username)
                    .put("password", password)
                    .put("client", "android")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                val text = request("POST", "$baseUrl/api/v1/auth/login", body.toString())
                val o = JSONObject(text)
                val u = o.optJSONObject("user")
                val session = AuthSession(
                    token = o.optString("token"),
                    refreshToken = o.optString("refreshToken", null),
                    user = AuthUser(
                        id = u?.optString("id").orEmpty(),
                        name = u?.optString("name").orEmpty(),
                        role = u?.optString("role", null),
                        phone = u?.optString("phone", null),
                    ),
                )
                currentToken = session.token
                currentUser = session.user
                session
            }.onFailure {
                currentToken = null
                currentUser = null
            }
        }

    override suspend fun logout() {
        val token = currentToken
        currentToken = null
        currentUser = null
        if (token == null) return
        withContext(Dispatchers.IO) {
            // 尽力通知服务端使 token 失效；失败无妨（本地会话已清）
            runCatching {
                val conn = URL("$baseUrl/api/v1/auth/logout").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

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
                // 服务端错误体 {"error":{"message":...}} 优先取可读文案（登录页直接展示）
                val msg = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IOException(msg?.takeIf { it.isNotBlank() } ?: "服务端返回 HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
