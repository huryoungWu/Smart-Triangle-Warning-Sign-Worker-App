package com.example.myapplication.core.util

import java.net.URI

/**
 * 实时视频地址工具（纯 Kotlin，可 JVM 单测）。
 *
 * 逻辑接口不变：仍然使用 `serverBaseUrl` 里的 host/port + 原来的 request-target；
 * 只把 scheme 从 `http(s)://` 换成 `udp://`。UDP 端口与 HTTP 同号。
 */
data class UdpEndpoint(val host: String, val port: Int)

private val SCHEME_RE = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")

/**
 * 由服务端 HTTP 基地址 + 原 request-target 生成 UDP 视频地址。
 *
 * 例：
 * - `http://192.168.1.5:8080` + `/api/v1/device/video/stream?device_code=TC-001`
 *   → `udp://192.168.1.5:8080/api/v1/device/video/stream?device_code=TC-001`
 * - `https://example.com` + `/x` → `udp://example.com:443/x`
 * - `http://[2408::1]:8080` + `/x` → `udp://[2408::1]:8080/x`
 *
 * 默认端口显式写出：`udp://` 已经丢失了原 scheme，无法再区分 http(80)/https(443)。
 */
fun udpVideoUrl(serverBaseUrl: String, requestTarget: String): String {
    val base = serverBaseUrl.trim().trimEnd('/')
    val match = SCHEME_RE.find(base)
    val scheme = match?.groupValues?.get(1)?.lowercase() ?: "http"
    val rest = if (match != null) base.substring(match.value.length) else base

    val slash = rest.indexOf('/')
    val authority = if (slash >= 0) rest.substring(0, slash) else rest
    val basePath = if (slash >= 0) rest.substring(slash).trimEnd('/') else ""
    val parsed = splitAuthority(authority)
    val host = parsed?.first ?: authority
    val port = parsed?.second ?: when (scheme) {
        "https" -> 443
        else -> 80
    }
    val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    val target = if (requestTarget.startsWith("/")) requestTarget else "/$requestTarget"
    return "udp://$hostPart:$port$basePath$target"
}

/** 解析 UDP 地址为 host + port；host 已去掉 IPv6 的方括号。 */
fun parseUdpEndpoint(udpUrl: String): UdpEndpoint? {
    val uri = runCatching { URI(udpUrl) }.getOrNull() ?: return null
    if (!uri.scheme.equals("udp", ignoreCase = true)) return null
    val rawHost = uri.host ?: return null
    val host = rawHost.removeSurrounding("[", "]")
    if (host.isBlank()) return null
    val port = if (uri.port != -1) uri.port else 80
    if (port !in 1..65535) return null
    return UdpEndpoint(host, port)
}

/**
 * 拆 authority：[host]:[port] 或 [IPv6]:port 或裸 IPv6。
 * 返回的 host 保留 IPv6 方括号（便于拼回 URL），port 为 null 表示未显式指定。
 */
private fun splitAuthority(authority: String): Pair<String, Int?>? {
    if (authority.isEmpty()) return null
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        val host = authority.substring(0, close + 1)
        val rest = authority.substring(close + 1)
        return when {
            rest.isEmpty() -> host to null
            rest.startsWith(':') -> {
                val port = parsePort(rest.substring(1)) ?: return null
                host to port
            }
            else -> null
        }
    }
    val colonCount = authority.count { it == ':' }
    if (colonCount == 0) return authority to null
    if (colonCount == 1) {
        val i = authority.lastIndexOf(':')
        val host = authority.substring(0, i)
        val port = parsePort(authority.substring(i + 1)) ?: return null
        if (host.isEmpty()) return null
        return host to port
    }
    // 多个冒号且没有方括号：按裸 IPv6 处理，无端口
    return authority to null
}

private fun parsePort(raw: String): Int? {
    if (raw.isEmpty() || raw.length > 5 || !raw.all { it.isDigit() }) return null
    return raw.toIntOrNull()?.takeIf { it in 1..65535 }
}
