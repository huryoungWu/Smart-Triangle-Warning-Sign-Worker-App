package com.example.myapplication.core.video

/**
 * UDP 实时视频协议 v1（纯 Kotlin，无 Android 依赖 → 可 JVM 单测）。
 *
 * 与 `tools/mock-server/udp-protocol.js` 保持同一字段定义：
 *
 * ```
 * 24 字节头（大端）+ payload，单 datagram ≤ 1200 字节
 *  0  4  magic "SPV1"
 *  4  1  version = 1
 *  5  1  type = 1 (FRAME)
 *  6  4  sid          u32
 *  10 4  frameId      u32（每 deviceStream 递增，模 2^32）
 *  14 4  frameLen     u32（整帧字节数）
 *  18 2  fragIndex    u16
 *  20 2  fragCount    u16
 *  22 2  payloadLen   u16
 *  24 N  payload
 * ```
 *
 * 控制报文（sub/ack/err/hb/unsub）是 JSON，放在 [UdpVideoClient] 里用 org.json 处理；
 * 本文件只负责二进制分片与重组（Android 单测里的 org.json 是 stub，不能放进来）。
 */
data class FrameFragment(
    /** u32，用 Long 保存 0..0xFFFFFFFF，避免 Int 符号问题 */
    val sid: Long,
    /** u32，用 Long 保存 0..0xFFFFFFFF */
    val frameId: Long,
    val frameLen: Int,
    val fragIndex: Int,
    val fragCount: Int,
    val payload: ByteArray,
)

object UdpVideoProtocol {
    private val MAGIC = byteArrayOf(
        'S'.code.toByte(),
        'P'.code.toByte(),
        'V'.code.toByte(),
        '1'.code.toByte(),
    )

    const val VERSION = 1
    const val TYPE_FRAME = 1
    const val HEADER_SIZE = 24
    const val MAX_PAYLOAD = 1176
    const val MAX_FRAME_BYTES = 256 * 1024
    const val MAX_FRAGS = (MAX_FRAME_BYTES + MAX_PAYLOAD - 1) / MAX_PAYLOAD // 223

    /** 解析一个数据 datagram；非法返回 null。调用方传 packet.length，避免复用缓冲区尾部的脏数据。 */
    fun parseFragment(buffer: ByteArray, length: Int = buffer.size): FrameFragment? {
        if (length < HEADER_SIZE || length > buffer.size) return null
        if (!MAGIC.contentEquals(buffer.copyOfRange(0, 4))) return null
        if (u8(buffer, 4) != VERSION || u8(buffer, 5) != TYPE_FRAME) return null

        val sid = u32(buffer, 6)
        val frameId = u32(buffer, 10)
        val frameLenLong = u32(buffer, 14)
        val fragIndex = u16(buffer, 18)
        val fragCount = u16(buffer, 20)
        val payloadLen = u16(buffer, 22)

        if (frameLenLong < 1 || frameLenLong > MAX_FRAME_BYTES.toLong()) return null
        if (fragCount !in 1..MAX_FRAGS) return null
        val frameLen = frameLenLong.toInt()
        val expectedFrags = (frameLen + MAX_PAYLOAD - 1) / MAX_PAYLOAD
        if (fragCount != expectedFrags) return null
        if (fragIndex >= fragCount) return null
        val expectedPayload = minOf(MAX_PAYLOAD, frameLen - fragIndex * MAX_PAYLOAD)
        if (payloadLen != expectedPayload) return null
        if (length != HEADER_SIZE + payloadLen) return null

        return FrameFragment(
            sid = sid,
            frameId = frameId,
            frameLen = frameLen,
            fragIndex = fragIndex,
            fragCount = fragCount,
            payload = buffer.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen),
        )
    }

    /** 编码一个分片（单测/模拟服务端用；正式服务端在 JS 侧）。 */
    fun encodeFragment(fragment: FrameFragment): ByteArray {
        require(fragment.payload.size in 1..MAX_PAYLOAD) { "payload 长度非法" }
        require(fragment.frameLen in 1..MAX_FRAME_BYTES) { "frameLen 非法" }
        require(fragment.fragCount in 1..MAX_FRAGS) { "fragCount 非法" }
        require(fragment.fragIndex in 0 until fragment.fragCount) { "fragIndex 非法" }
        val expected = minOf(
            MAX_PAYLOAD,
            fragment.frameLen - fragment.fragIndex * MAX_PAYLOAD,
        )
        require(fragment.payload.size == expected) { "payload 长度与 frameLen/fragIndex 不匹配" }

        val out = ByteArray(HEADER_SIZE + fragment.payload.size)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = TYPE_FRAME.toByte()
        putU32(out, 6, fragment.sid)
        putU32(out, 10, fragment.frameId)
        putU32(out, 14, fragment.frameLen.toLong())
        putU16(out, 18, fragment.fragIndex)
        putU16(out, 20, fragment.fragCount)
        putU16(out, 22, fragment.payload.size)
        fragment.payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** 模 2^32 判断 a 是否比 b 新（差值 < 2^31 视为前进）。 */
    fun isNewerFrameId(a: Long, b: Long): Boolean {
        val diff = (a - b) and 0xFFFFFFFFL
        return diff != 0L && diff < 0x80000000L
    }

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    private fun u16(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)

    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or
            (u8(b, o + 1).toLong() shl 16) or
            (u8(b, o + 2).toLong() shl 8) or
            u8(b, o + 3).toLong()

    private fun putU16(b: ByteArray, o: Int, value: Int) {
        b[o] = ((value ushr 8) and 0xFF).toByte()
        b[o + 1] = (value and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, value: Long) {
        b[o] = ((value ushr 24) and 0xFF).toByte()
        b[o + 1] = ((value ushr 16) and 0xFF).toByte()
        b[o + 2] = ((value ushr 8) and 0xFF).toByte()
        b[o + 3] = (value and 0xFF).toByte()
    }
}

/**
 * 单帧重组器：同一时刻只保留 1 个未完成帧。
 * - 新 frameId 丢弃旧帧；乱序/重复片忽略；
 * - 完整时返回整帧，否则 null；
 * - 旧 sid/frameId 的迟到分片直接丢弃。
 */
class FrameAssembler {
    private var sid: Long = -1
    private var frameId: Long = -1
    private var frameLen: Int = 0
    private var fragCount: Int = 0
    private var buffer: ByteArray? = null
    private var received: BooleanArray? = null
    private var got: Int = 0
    private var lastSid: Long = -1
    private var lastFrameId: Long = -1

    fun reset() {
        sid = -1
        frameId = -1
        frameLen = 0
        fragCount = 0
        buffer = null
        received = null
        got = 0
        lastSid = -1
        lastFrameId = -1
    }

    fun accept(fragment: FrameFragment): ByteArray? {
        val sameFrame = buffer != null && fragment.sid == sid && fragment.frameId == frameId
        if (!sameFrame) {
            // 当前未完成帧的旧分片
            if (buffer != null && fragment.sid == sid &&
                !UdpVideoProtocol.isNewerFrameId(fragment.frameId, frameId)
            ) {
                return null
            }
            // 已完成帧的迟到分片
            if (lastSid == fragment.sid && lastFrameId >= 0 &&
                !UdpVideoProtocol.isNewerFrameId(fragment.frameId, lastFrameId)
            ) {
                return null
            }
            sid = fragment.sid
            frameId = fragment.frameId
            frameLen = fragment.frameLen
            fragCount = fragment.fragCount
            buffer = ByteArray(frameLen)
            received = BooleanArray(fragCount)
            got = 0
        }

        val data = buffer ?: return null
        val seen = received ?: return null
        if (fragment.fragIndex !in 0 until fragCount) return null
        val offset = fragment.fragIndex * UdpVideoProtocol.MAX_PAYLOAD
        if (offset + fragment.payload.size > frameLen) return null
        if (seen[fragment.fragIndex]) return null
        fragment.payload.copyInto(data, offset)
        seen[fragment.fragIndex] = true
        got += 1
        if (got != fragCount) return null

        lastSid = sid
        lastFrameId = frameId
        buffer = null
        received = null
        return data
    }
}
