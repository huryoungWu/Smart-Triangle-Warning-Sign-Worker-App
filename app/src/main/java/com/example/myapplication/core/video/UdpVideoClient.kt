package com.example.myapplication.core.video

import com.example.myapplication.core.util.UdpEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.UUID

/** 视频流错误分类：服务端拒绝 / 网络（可重连）/ 权限。 */
sealed interface UdpVideoError {
    data class Server(val status: Int, val code: String, val message: String) : UdpVideoError
    data class Network(val cause: Throwable) : UdpVideoError
    data class Permission(val cause: Throwable) : UdpVideoError
}

private class PermanentStreamError(val error: UdpVideoError) : Exception()
private class RetryStreamError(message: String) : Exception(message)

/**
 * UDP 实时视频客户端。
 *
 * 行为：
 * - 订阅 `requestTarget`（与 HTTP 请求行完全一致，如 `/api/v1/device/video/stream?device_code=TC-001`）；
 * - 收到 ack 前丢弃所有二进制帧；sub 每 1s 重发（服务端按 cid 幂等）；
 * - ack 后每 2s 心跳；3s 没有完整帧则关闭 socket、1s 后重连（换新 cid）；
 * - 离开页面（协程取消）时尽力发 unsub 并关闭 socket；
 * - 不做 Bitmap 解码，由 UI 层在 IO 线程解码。
 *
 * 必须在 Dispatchers.IO 调用 [run]。
 */
class UdpVideoClient(
    private val endpoint: UdpEndpoint,
    private val requestTarget: String,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (UdpVideoError) -> Unit,
) {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            try {
                streamOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PermanentStreamError) {
                onError(e.error)
                return
            } catch (e: SecurityException) {
                onError(UdpVideoError.Permission(e))
                return
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                onError(UdpVideoError.Network(e))
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun streamOnce() {
        val cid = UUID.randomUUID().toString().replace("-", "").take(16)
        val (socket, serverAddress) = openSocket()
        val assembler = FrameAssembler()
        var sid: Long? = null
        var acked = false
        val startedAt = nowMs()
        var lastSubAt = startedAt
        var lastHbAt = 0L
        var lastFrameAt = 0L
        val buffer = ByteArray(MAX_DATAGRAM)

        try {
            send(socket, serverAddress, subPacket(cid))
            while (currentCoroutineContext().isActive) {
                val now = nowMs()

                if (!acked && now - lastSubAt >= SUB_RETRY_MS) {
                    send(socket, serverAddress, subPacket(cid))
                    lastSubAt = now
                }
                if (acked && now - lastHbAt >= HEARTBEAT_MS) {
                    sendHeartbeat(socket, serverAddress, sid!!)
                    lastHbAt = now
                }
                if (acked && now - lastFrameAt > FRAME_WATCHDOG_MS) {
                    throw RetryStreamError("3s 未收到完整帧")
                }
                if (!acked && now - startedAt > ACK_TIMEOUT_MS) {
                    // 订阅没有 ack：外层重连（新 cid / 新本地端口）
                    throw RetryStreamError("3s 未收到 ack")
                }

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }

                val len = packet.length
                if (len <= 0) continue
                if (packet.data[0] == '{'.code.toByte()) {
                    val obj = runCatching {
                        JSONObject(String(packet.data, 0, len, Charsets.UTF_8))
                    }.getOrNull() ?: continue
                    when (obj.optString("t")) {
                        "ack" -> {
                            val ackSid = obj.optLong("sid", -1L)
                            if (ackSid in 0..0xFFFFFFFFL) {
                                sid = ackSid
                                acked = true
                                assembler.reset()
                                lastFrameAt = nowMs()
                                lastHbAt = nowMs()
                            }
                        }

                        "err" -> {
                            val status = obj.optInt("status", 0)
                            val error = obj.optJSONObject("error")
                            val code = error?.optString("code").orEmpty().ifBlank { "error" }
                            val message = error?.optString("message").orEmpty()
                                .ifBlank { "视频流被服务端拒绝（$status）" }
                            if (status == 400 || status == 401 || status == 403 || status == 404) {
                                throw PermanentStreamError(UdpVideoError.Server(status, code, message))
                            }
                            throw RetryStreamError("服务端错误 $status $code")
                        }
                    }
                } else {
                    if (!acked) continue
                    val currentSid = sid ?: continue
                    val fragment = UdpVideoProtocol.parseFragment(packet.data, len) ?: continue
                    if (fragment.sid != currentSid) continue
                    val frame = assembler.accept(fragment) ?: continue
                    lastFrameAt = nowMs()
                    onFrame(frame)
                }
            }
        } finally {
            if (acked && sid != null) {
                runCatching { send(socket, serverAddress, unsubPacket(sid!!)) }
            }
            runCatching { socket.close() }
        }
    }

    private fun openSocket(): Pair<DatagramSocket, InetAddress> {
        val address = InetAddress.getByName(endpoint.host)
        val socket = DatagramSocket(null as SocketAddress?)
        try {
            socket.receiveBufferSize = RECEIVE_BUFFER_BYTES
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            // 按服务端地址族绑定：部分 Android 版本的默认构造只绑 IPv4，无法向 IPv6 发送
            val wildcard = if (address is Inet6Address) {
                InetAddress.getByName("::")
            } else {
                InetAddress.getByName("0.0.0.0")
            }
            socket.bind(InetSocketAddress(wildcard, 0))
            socket.connect(InetSocketAddress(address, endpoint.port))
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
        return socket to address
    }

    private fun subPacket(cid: String): ByteArray = JSONObject().apply {
        put("v", 1)
        put("t", "sub")
        put("cid", cid)
        put("target", requestTarget)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun unsubPacket(sid: Long): ByteArray = JSONObject().apply {
        put("v", 1)
        put("t", "unsub")
        put("sid", sid)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun sendHeartbeat(socket: DatagramSocket, address: InetAddress, sid: Long) {
        val bytes = JSONObject().apply {
            put("v", 1)
            put("t", "hb")
            put("sid", sid)
        }.toString().toByteArray(Charsets.UTF_8)
        send(socket, address, bytes)
    }

    private fun send(socket: DatagramSocket, address: InetAddress, bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, address, endpoint.port))
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val MAX_DATAGRAM = 65535
        const val RECEIVE_BUFFER_BYTES = 128 * 1024
        const val RECEIVE_TIMEOUT_MS = 500
        const val SUB_RETRY_MS = 1000L
        const val ACK_TIMEOUT_MS = 3000L
        const val HEARTBEAT_MS = 2000L
        const val FRAME_WATCHDOG_MS = 3000L
        const val RECONNECT_DELAY_MS = 1000L
    }
}
