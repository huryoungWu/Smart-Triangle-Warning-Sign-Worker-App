package com.example.myapplication

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.core.util.UdpEndpoint
import com.example.myapplication.core.video.FrameFragment
import com.example.myapplication.core.video.UdpVideoClient
import com.example.myapplication.core.video.UdpVideoError
import com.example.myapplication.core.video.UdpVideoProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 在真机/模拟器上验证 UdpVideoClient 的 socket/JSON/重组链路：
 * 本进程起一个 loopback UDP 假服务端，收到 sub 后回 ack + 3 个分片，
 * 客户端应重组出完整帧。
 *
 * 运行：./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class UdpVideoClientInstrumentedTest {

    @Test
    fun receivesAndReassemblesFrame() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val frame = ByteArray(3000) { (it * 7).toByte() }
        val fragCount = (frame.size + UdpVideoProtocol.MAX_PAYLOAD - 1) / UdpVideoProtocol.MAX_PAYLOAD
        val fragments = (0 until fragCount).map { i ->
            val start = i * UdpVideoProtocol.MAX_PAYLOAD
            val end = minOf(start + UdpVideoProtocol.MAX_PAYLOAD, frame.size)
            FrameFragment(
                sid = 42,
                frameId = 1,
                frameLen = frame.size,
                fragIndex = i,
                fragCount = fragCount,
                payload = frame.copyOfRange(start, end),
            )
        }

        val serverJob = launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(4096)
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                val sub = JSONObject(String(request.data, 0, request.length, Charsets.UTF_8))
                val ack = JSONObject()
                    .put("v", 1)
                    .put("t", "ack")
                    .put("cid", sub.optString("cid"))
                    .put("sid", 42L)
                    .put("status", 200)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                server.send(DatagramPacket(ack, ack.size, request.socketAddress))
                for (fragment in fragments) {
                    val bytes = UdpVideoProtocol.encodeFragment(fragment)
                    server.send(DatagramPacket(bytes, bytes.size, request.socketAddress))
                }
                // 保持 socket 打开，避免客户端在收到帧前拿到 ICMP PortUnreachable
                delay(2000)
            } finally {
                server.close()
            }
        }

        val received = CompletableDeferred<ByteArray>()
        val errors = mutableListOf<UdpVideoError>()
        val client = UdpVideoClient(
            endpoint = UdpEndpoint("127.0.0.1", port),
            requestTarget = "/api/v1/device/video/stream?device_code=TC-001",
            onFrame = { received.complete(it) },
            onError = { synchronized(errors) { errors.add(it) } },
        )
        val clientJob = launch(Dispatchers.IO) { client.run() }

        val actual = withTimeout(8000) { received.await() }
        assertArrayEquals(frame, actual)

        clientJob.cancelAndJoin()
        serverJob.cancelAndJoin()
        assertTrue("unexpected errors: $errors", errors.isEmpty())
    }
}
