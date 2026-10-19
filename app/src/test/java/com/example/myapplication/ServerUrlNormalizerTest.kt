package com.example.myapplication

import com.example.myapplication.core.util.ServerUrlResult
import com.example.myapplication.core.util.normalizeServerUrl
import com.example.myapplication.core.util.parseServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器地址规范化单测。
 *
 * 重点覆盖两类容易出错的输入：
 * 1. **IPv6 漏写方括号 + 带端口** —— 与端口的歧义只能靠"先切端口"救回
 * 2. **等价写法必须收敛成同一字符串** —— 否则用户只改个写法就被判成"换了服务端"而强制登出
 */
class ServerUrlNormalizerTest {

    private val ipv6 = "2408:8463:2a00:283b:a83c:bc29:1b44:a437"

    private fun ok(raw: String): String =
        when (val r = parseServerUrl(raw)) {
            is ServerUrlResult.Ok -> r.url
            is ServerUrlResult.Invalid -> throw AssertionError("期望成功，实际失败：${r.reason}（输入 $raw）")
        }

    private fun bad(raw: String): String =
        when (val r = parseServerUrl(raw)) {
            is ServerUrlResult.Ok -> throw AssertionError("期望失败，实际得到 ${r.url}（输入 $raw）")
            is ServerUrlResult.Invalid -> r.reason
        }

    // ── 常规形态 ──

    @Test
    fun `补 scheme、去尾斜杠、去空白`() {
        assertEquals("http://192.168.43.120:8080", ok("192.168.43.120:8080"))
        assertEquals("http://192.168.43.120:8080", ok("  http://192.168.43.120:8080/  "))
        assertEquals("http://192.168.43.120:8080", ok("HTTP://192.168.43.120:8080"))
        assertEquals("http://192.168.43.120:8080", ok("\t192.168.43.120:8080\n"))
    }

    @Test
    fun `默认端口归并 —— 同一地址的不同写法必须相等`() {
        assertEquals("http://1.2.3.4", ok("http://1.2.3.4:80"))
        assertEquals("http://1.2.3.4", ok("http://1.2.3.4:80/"))
        assertEquals("https://a.example.com", ok("https://a.example.com:443"))
        // 非默认端口保留
        assertEquals("http://1.2.3.4:8080", ok("http://1.2.3.4:8080"))
    }

    @Test
    fun `域名统一小写、保留路径`() {
        assertEquals("http://api.example.com:8080", ok("http://Api.Example.COM:8080"))
        assertEquals("http://a.example.com:8080/base", ok("http://a.example.com:8080/base/"))
    }

    // ── IPv6（本改造最典型的输入） ──

    @Test
    fun `IPv6 漏方括号 + 带端口 —— 先切端口救回`() {
        assertEquals("http://[$ipv6]:8080", ok("$ipv6:8080"))
        assertEquals("http://[$ipv6]:8080", ok("http://$ipv6:8080"))
    }

    @Test
    fun `IPv6 不带端口 —— 不臆造端口`() {
        assertEquals("http://[$ipv6]", ok(ipv6))
        assertEquals("http://[$ipv6]", ok("http://[$ipv6]"))
        assertEquals("http://[$ipv6]:8080", ok("http://[$ipv6]:8080"))
    }

    @Test
    fun `IPv6 压缩写法与内嵌 IPv4`() {
        assertEquals("http://[::1]", ok("::1"))
        assertEquals("http://[::1]:8080", ok("[::1]:8080"))
        // 内嵌 IPv4 的末段不能被当成端口（parsePort 拒绝含点的串）
        assertEquals("http://[::ffff:1.2.3.4]", ok("::ffff:1.2.3.4"))
        // 有意的取舍：端口优先，故无方括号的 fe80::1:8080 会被读成 [fe80::1]:8080
        assertEquals("http://[fe80::1]:8080", ok("fe80::1:8080"))
        // 想表达"这个 :8080 是地址末组"就必须写方括号
        assertEquals("http://[fe80::1:8080]", ok("[fe80::1:8080]"))
    }

    // ── 非法输入 ──

    @Test
    fun `非法输入必须带可读原因`() {
        assertTrue(bad("").contains("不能为空"))
        assertTrue(bad("   ").contains("不能为空"))
        assertTrue(bad("http://a:8080\nhttp://b:8080").contains("一个地址"))
        assertTrue(bad("ftp://1.2.3.4").contains("http"))
        assertTrue(bad("http://").contains("缺少"))
        assertTrue(bad("1.2.3.4:99999").contains("不合法"))
        assertTrue(bad("999.999.999.999").contains("不合法"))
        assertTrue(bad("http://a b").contains("一个地址")) // 空格切成了两段
        assertTrue(bad("http://1.2.3.4:8080?a=1").contains("?"))
        assertTrue(bad("http://user@1.2.3.4").contains("@"))
        assertTrue(bad("[]").contains("不合法"))
        assertTrue(bad("[::1").contains("不合法"))
        assertTrue(bad("http://1.2.3.4:").contains("不合法"))
        assertTrue(bad(":::").contains("不合法"))
    }

    // ── 便捷版 ──

    @Test
    fun `normalizeServerUrl 失败返回 null`() {
        assertNull(normalizeServerUrl("不是地址"))
        assertEquals("http://192.168.43.120:8080", normalizeServerUrl("192.168.43.120:8080"))
    }
}
