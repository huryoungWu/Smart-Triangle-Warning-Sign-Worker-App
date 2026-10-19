package com.example.myapplication.core.util

/**
 * 服务器地址规范化（纯 Kotlin、零 Android 依赖 → 可 JVM 单测）。
 *
 * 用户会在「设置 → 服务器地址」里手输或粘贴各种写法，本文件负责把它们收敛成
 * **同一种标准形式**：`scheme://host[:port][/path]`。
 *
 * 为什么要"标准形式"而不是原样收下：切换地址时会拿新旧字符串比相等，
 * 若 `http://1.2.3.4:8080/` 与 `1.2.3.4:8080` 不相等，用户只是改了个写法就会被
 * 强制登出（见 `ServiceLocator.applyServerBaseUrl`）。
 */
/**
 * 编译期默认值缺失或非法时的兜底地址：模拟器 / USB 真机 + `adb reverse tcp:8080 tcp:8080`。
 * 唯一来源，`SettingsRepository` 与各仓库的默认 provider 都引用它。
 */
const val FALLBACK_SERVER_BASE_URL = "http://127.0.0.1:8080"

sealed interface ServerUrlResult {
    /** 规范化成功：[url] 可直接用于拼接 `$url/api/v1/...` */
    data class Ok(val url: String) : ServerUrlResult

    /** 规范化失败：[reason] 是给用户看的中文原因，UI 直接展示 */
    data class Invalid(val reason: String) : ServerUrlResult
}

/** 便捷版：只要结果，不要原因（单测与内部调用用） */
fun normalizeServerUrl(raw: String): String? = (parseServerUrl(raw) as? ServerUrlResult.Ok)?.url

/**
 * 解析并规范化服务端地址。规则：
 *
 * | 输入 | 输出 |
 * |---|---|
 * | `192.168.43.120:8080` | `http://192.168.43.120:8080` |
 * | `http://1.2.3.4:8080/` | `http://1.2.3.4:8080` |
 * | `http://2408:8463:2a00:283b:a83c:bc29:1b44:a437:8080`（漏方括号） | `http://[2408:8463:2a00:283b:a83c:bc29:1b44:a437]:8080` |
 * | `[2408:...:a437]:8080` | `http://[2408:...:a437]:8080` |
 * | `http://host:80` | `http://host`（默认端口归并） |
 * | `999.999.999.999` / `ftp://1.2.3.4` / 多行 | 失败（带原因） |
 */
fun parseServerUrl(raw: String): ServerUrlResult {
    // ① 空白切分：多行/多段粘贴 → 明确报错，绝不"猜第一行"
    val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return ServerUrlResult.Invalid("地址不能为空")
    if (tokens.size > 1) return ServerUrlResult.Invalid("只能填一个地址（检测到多行/多段内容）")
    var s = tokens[0]

    // ② scheme：大小写不敏感；白名单只放 http/https；缺省补 http://
    val schemeMatch = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://").find(s)
    val scheme: String
    if (schemeMatch != null) {
        scheme = schemeMatch.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") {
            return ServerUrlResult.Invalid("只支持 http:// 或 https://（收到 $scheme://）")
        }
        s = s.substring(schemeMatch.value.length)
    } else {
        scheme = SCHEME_HTTP
    }

    // ③ query / fragment / userinfo 一律拒绝（在拆 authority 之前查，否则会被并进端口串）
    if (s.contains('?') || s.contains('#')) return ServerUrlResult.Invalid("地址不要带 ? 参数或 # 片段")
    if (s.contains('@')) return ServerUrlResult.Invalid("地址不要带 用户名@ 前缀")

    // ④ 拆 authority / path
    val slash = s.indexOf('/')
    val authority = if (slash >= 0) s.substring(0, slash) else s
    val path = if (slash >= 0) s.substring(slash) else ""
    if (authority.isEmpty()) return ServerUrlResult.Invalid("缺少服务器地址（主机名或 IP）")

    // ⑤ 端口与主机
    val hp = parseAuthority(authority)
        ?: return ServerUrlResult.Invalid("主机或端口不合法（IPv6 请写成 [地址]:端口）")

    // ⑥ 默认端口归并：让"同一地址的两种写法"字符串相等（相等性决定切地址时是否强制登出）
    val portPart = when {
        hp.port == null -> ""
        hp.port == DEFAULT_PORT[scheme] -> ""
        else -> ":${hp.port}"
    }
    return ServerUrlResult.Ok("$scheme://${hp.host}$portPart${path.trimEnd('/')}")
}

private const val SCHEME_HTTP = "http"
private val DEFAULT_PORT = mapOf(SCHEME_HTTP to 80, "https" to 443)

private data class HostPort(val host: String, val port: Int?)

/**
 * 冒号数分流 —— **IPv6 与端口的歧义只在这里裁决**：
 * - 已带 `[...]` → 方括号内必须是合法 IPv6，括号后只允许跟 `:port`
 * - 0 个冒号 → 纯 host（不可能有端口）
 * - 1 个冒号 → `host:port`（单个冒号不可能是 IPv6）
 * - ≥2 个冒号且无方括号 → 用户漏写方括号，见 [splitUnbracketedIpv6]
 */
private fun parseAuthority(authority: String): HostPort? {
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        val inner = authority.substring(1, close)
        if (!isIpv6Literal(inner)) return null
        val rest = authority.substring(close + 1)
        return when {
            rest.isEmpty() -> HostPort("[${inner.lowercase()}]", null)
            rest.startsWith(':') ->
                parsePort(rest.substring(1))?.let { HostPort("[${inner.lowercase()}]", it) }
            else -> null
        }
    }
    return when (authority.count { it == ':' }) {
        0 -> canonicalHost(authority)?.let { HostPort(it, null) }
        1 -> {
            val i = authority.lastIndexOf(':')
            val host = canonicalHost(authority.substring(0, i)) ?: return null
            val port = parsePort(authority.substring(i + 1)) ?: return null
            HostPort(host, port)
        }
        else -> splitUnbracketedIpv6(authority)
    }
}

/**
 * 漏写方括号的 IPv6（本项目最典型的输入：把 `[2408:...]:8080` 的方括号打漏了）。
 *
 * **先按 `ipv6:port` 解释，失败再按裸 IPv6 解释**：
 * - `2408:...:a437:8080` → 切掉末段后前 8 组合法 → 认定末段是端口 → `[2408:...:a437]:8080`
 * - `2408:...:a437`（8 组、无端口）→ 切掉末段后只剩 7 组（非法 IPv6）→ 回落整体 → `[2408:...:a437]`
 *
 * 端口优先的理由：本服务端恒带端口，而"8 组、恰好以数字组结尾、且该组属于地址本身"
 * 的写法极罕见 —— 歧义无法在算法层消除，方括号是唯一无歧义写法（输入框占位符已给示例）。
 */
private fun splitUnbracketedIpv6(authority: String): HostPort? {
    val i = authority.lastIndexOf(':')
    if (i > 0) {
        val head = authority.substring(0, i)
        val port = parsePort(authority.substring(i + 1))
        if (port != null && isIpv6Literal(head)) return HostPort("[${head.lowercase()}]", port)
    }
    return if (isIpv6Literal(authority)) HostPort("[${authority.lowercase()}]", null) else null
}

/** 端口：纯十进制、1..65535（拒绝空串、前导 `+`、超范围） */
private fun parsePort(s: String): Int? {
    if (s.isEmpty() || s.length > 5 || !s.all { it.isDigit() }) return null
    return s.toIntOrNull()?.takeIf { it in 1..65535 }
}

/** 主机规范化：IPv4 / IPv6 字面量 / 域名。域名与 IPv4 小写，IPv6 加方括号并小写 */
private fun canonicalHost(host: String): String? {
    if (host.isEmpty() || host.length > MAX_HOST_LEN) return null
    // 纯数字加点的串只可能是 IPv4：拦下 999.999.999.999 被当成域名收下
    if (host.all { it.isDigit() || it == '.' }) return host.takeIf { isIpv4Literal(it) }
    if (isIpv4Literal(host)) return host
    return host.takeIf { HOSTNAME_RE.matches(it) }?.lowercase()
}

private const val MAX_HOST_LEN = 253

private val HOSTNAME_RE = Regex(
    "^[A-Za-z0-9]([A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?" +
        "(\\.[A-Za-z0-9]([A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?)*$",
)

private fun isIpv4Literal(s: String): Boolean {
    val parts = s.split('.')
    if (parts.size != 4) return false
    return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
}

/**
 * 手写 IPv6 校验。**不用 `InetAddress.getByName`**：对含冒号的非法串它会回落 DNS 查询，
 * 单测要求离线、真机上也白等一次解析。支持 `::` 压缩与末组内嵌 IPv4。
 */
private fun isIpv6Literal(raw: String): Boolean {
    if (raw.isEmpty() || !raw.contains(':') || raw.contains('%')) return false
    if (Regex("::").findAll(raw).count() > 1) return false // "1::2::3"
    val compressed = raw.contains("::")
    val groups: List<String> = if (compressed) {
        val idx = raw.indexOf("::")
        val head = raw.substring(0, idx)
        val tail = raw.substring(idx + 2)
        val h = if (head.isEmpty()) emptyList() else head.split(':')
        val t = if (tail.isEmpty()) emptyList() else tail.split(':')
        h + t
    } else {
        raw.split(':')
    }
    if (groups.isEmpty()) return compressed // "::" 本身合法
    if (groups.any { it.isEmpty() }) return false // ":::"、"1::2::3"
    var count = 0
    groups.forEachIndexed { i, g ->
        if (g.contains('.')) {
            if (i != groups.lastIndex || !isIpv4Literal(g)) return false
            count += 2
        } else {
            if (g.length !in 1..4) return false
            if (!g.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return false
            count += 1
        }
    }
    return if (compressed) count <= 7 else count == 8
}
