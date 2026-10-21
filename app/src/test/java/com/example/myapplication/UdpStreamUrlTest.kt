package com.example.myapplication

import com.example.myapplication.core.util.UdpEndpoint
import com.example.myapplication.core.util.parseUdpEndpoint
import com.example.myapplication.core.util.udpVideoUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UdpStreamUrlTest {

    private val target = "/api/v1/device/video/stream?device_code=TC-001"

    @Test
    fun `http ipv4 with port`() {
        assertEquals(
            "udp://192.168.1.5:8080$target",
            udpVideoUrl("http://192.168.1.5:8080", target),
        )
    }

    @Test
    fun `http without port defaults to 80`() {
        assertEquals("udp://example.com:80/x", udpVideoUrl("http://example.com", "/x"))
    }

    @Test
    fun `https without port defaults to 443`() {
        assertEquals("udp://example.com:443/x", udpVideoUrl("https://example.com/", "/x"))
    }

    @Test
    fun `ipv6 keeps brackets`() {
        assertEquals(
            "udp://[2408:8463::1]:8080/x",
            udpVideoUrl("http://[2408:8463::1]:8080", "/x"),
        )
    }

    @Test
    fun `bare ipv6 without port gets brackets and default port`() {
        assertEquals(
            "udp://[2408:8463::1]:80/x",
            udpVideoUrl("http://2408:8463::1", "/x"),
        )
    }

    @Test
    fun `path prefix is preserved`() {
        assertEquals(
            "udp://host:8080/prefix/api/v1/x",
            udpVideoUrl("http://host:8080/prefix", "/api/v1/x"),
        )
    }

    @Test
    fun `target without leading slash`() {
        assertEquals("udp://host:8080/api/x", udpVideoUrl("http://host:8080", "api/x"))
    }

    @Test
    fun `parse endpoint ipv4 and ipv6`() {
        assertEquals(UdpEndpoint("127.0.0.1", 8080), parseUdpEndpoint("udp://127.0.0.1:8080/x"))
        assertEquals(UdpEndpoint("2408:8463::1", 8080), parseUdpEndpoint("udp://[2408:8463::1]:8080/x"))
        assertEquals(UdpEndpoint("example.com", 80), parseUdpEndpoint("udp://example.com/x"))
    }

    @Test
    fun `parse invalid endpoints`() {
        assertNull(parseUdpEndpoint("http://127.0.0.1:8080/x"))
        assertNull(parseUdpEndpoint("udp://"))
        assertNull(parseUdpEndpoint("not a url"))
    }

    @Test
    fun `round trip through parse`() {
        val url = udpVideoUrl("http://[::1]:8080", target)
        assertEquals(UdpEndpoint("::1", 8080), parseUdpEndpoint(url))
    }
}
