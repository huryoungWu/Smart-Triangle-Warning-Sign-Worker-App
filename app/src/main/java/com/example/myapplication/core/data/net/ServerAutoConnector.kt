package com.example.myapplication.core.data.net

/**
 * 启动期**静默选服**。
 *
 * 两个服务端按 [DEFAULT_CANDIDATES] 的顺序探测，**第一个
 * `GET /api/v1/app/version` 返回 2xx 的即选中**；全都不通返回 `null`
 * （调用方跳「设置」，让用户手动填地址）。
 *
 * 只判断“连不连得上”，不产生任何界面。
 */
class ServerAutoConnector(
    private val candidates: List<String> = DEFAULT_CANDIDATES,
    /** 该地址是否可用；单测注入假实现，避免真实网络。 */
    private val probe: suspend (String) -> ServerProbeResult = ServerProbe(
        connectTimeoutMs = PROBE_TIMEOUT_MS,
        readTimeoutMs = PROBE_TIMEOUT_MS,
    )::check,
) {

    /** 按优先级返回第一个可用地址；全部不可达返回 null */
    suspend fun select(): String? =
        candidates.firstOrNull { probe(it) is ServerProbeResult.Ok }

    companion object {
        /**
         * 选服顺序 = 优先级：先热点局域网（快），不通再走公网备用。
         *
         * **改服务器地址只改这里**；release 的明文 HTTP 白名单
         * （`res/xml/network_security_config.xml`）需同步加对应 IP，否则会被系统拦截。
         */
        val DEFAULT_CANDIDATES: List<String> = listOf(
            "http://192.168.220.39:8080",
            "http://47.108.26.28:8082",
        )

        /** 静默探测必须快：两个都不通时最多等 2 × 2s 就进设置页 */
        private const val PROBE_TIMEOUT_MS = 2_000
    }
}
