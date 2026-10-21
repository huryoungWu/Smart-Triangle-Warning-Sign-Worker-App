package com.example.myapplication

import com.example.myapplication.core.video.FrameAssembler
import com.example.myapplication.core.video.FrameFragment
import com.example.myapplication.core.video.UdpVideoProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpVideoProtocolTest {

    private fun fragments(frame: ByteArray, sid: Long = 7, frameId: Long = 1): List<FrameFragment> {
        val count = (frame.size + UdpVideoProtocol.MAX_PAYLOAD - 1) / UdpVideoProtocol.MAX_PAYLOAD
        return (0 until count).map { i ->
            val start = i * UdpVideoProtocol.MAX_PAYLOAD
            val end = minOf(start + UdpVideoProtocol.MAX_PAYLOAD, frame.size)
            FrameFragment(
                sid = sid,
                frameId = frameId,
                frameLen = frame.size,
                fragIndex = i,
                fragCount = count,
                payload = frame.copyOfRange(start, end),
            )
        }
    }

    private fun sample(size: Int): ByteArray = ByteArray(size) { (it * 31 and 0xFF).toByte() }

    @Test
    fun `single fragment round trip`() {
        val frame = sample(100)
        val bytes = UdpVideoProtocol.encodeFragment(fragments(frame).single())
        val parsed = UdpVideoProtocol.parseFragment(bytes)
        assertNotNull(parsed)
        assertEquals(100, parsed!!.frameLen)
        assertEquals(1, parsed.fragCount)
        assertArrayEquals(frame, parsed.payload)
    }

    @Test
    fun `multi fragment out of order reassembles`() {
        val frame = sample(3000)
        val parts = fragments(frame)
        assertTrue(parts.size > 1)
        val assembler = FrameAssembler()
        var completed: ByteArray? = null
        for (part in listOf(parts[2], parts[0], parts[1])) {
            val parsed = UdpVideoProtocol.parseFragment(UdpVideoProtocol.encodeFragment(part))
            assertNotNull(parsed)
            completed = assembler.accept(parsed!!) ?: completed
        }
        assertArrayEquals(frame, completed!!)
    }

    @Test
    fun `duplicate fragment is ignored`() {
        val frame = sample(3000)
        val parts = fragments(frame)
        val assembler = FrameAssembler()
        assertNull(assembler.accept(parts[0]))
        assertNull(assembler.accept(parts[0]))
        assertNull(assembler.accept(parts[1]))
        assertArrayEquals(frame, assembler.accept(parts[2])!!)
    }

    @Test
    fun `missing fragment drops the frame`() {
        val frame = sample(3000)
        val parts = fragments(frame)
        val assembler = FrameAssembler()
        assertNull(assembler.accept(parts[0]))
        assertNull(assembler.accept(parts[1]))
        // 下一帧从 0 开始，证明缺片帧被丢弃
        val next = fragments(frame, frameId = 2)
        assertNull(assembler.accept(next[0]))
        assertNull(assembler.accept(next[1]))
        assertArrayEquals(frame, assembler.accept(next[2])!!)
    }

    @Test
    fun `new frame drops incomplete old frame`() {
        val frame = sample(3000)
        val old = fragments(frame, frameId = 10)
        val assembler = FrameAssembler()
        assertNull(assembler.accept(old[0]))
        val new = fragments(frame, frameId = 11)
        assertNull(assembler.accept(new[0]))
        assertNull(assembler.accept(new[1]))
        assertArrayEquals(frame, assembler.accept(new[2])!!)
        // 旧帧的迟到片被忽略
        assertNull(assembler.accept(old[1]))
    }

    @Test
    fun `late fragment after completed frame is ignored`() {
        val frame = sample(100)
        val assembler = FrameAssembler()
        assertNotNull(assembler.accept(fragments(frame, frameId = 5).single()))
        assertNull(assembler.accept(fragments(frame, frameId = 4).single()))
        assertNull(assembler.accept(fragments(frame, frameId = 5).single()))
    }

    @Test
    fun `frame id wraps around`() {
        val frame = sample(100)
        val assembler = FrameAssembler()
        assertNotNull(assembler.accept(fragments(frame, frameId = 0xFFFFFFFFL).single()))
        // 0xFFFFFFFF -> 0 应视为前进
        assertNotNull(assembler.accept(fragments(frame, frameId = 0L).single()))
    }

    @Test
    fun `invalid headers are rejected`() {
        val frame = sample(100)
        val valid = UdpVideoProtocol.encodeFragment(fragments(frame).single())

        fun mutated(index: Int, value: Int): ByteArray = valid.copyOf().also { it[index] = value.toByte() }

        assertNull(UdpVideoProtocol.parseFragment(mutated(0, 0)))
        assertNull(UdpVideoProtocol.parseFragment(mutated(4, 2)))
        assertNull(UdpVideoProtocol.parseFragment(mutated(5, 2)))
        assertNull(UdpVideoProtocol.parseFragment(valid, 10))
        // payloadLen 改成 0（低字节）
        assertNull(UdpVideoProtocol.parseFragment(mutated(23, 0)))
        // frameLen 改成 0（低字节）
        assertNull(UdpVideoProtocol.parseFragment(mutated(17, 0)))
        // frameLen 超过 256KiB
        val tooBig = valid.copyOf().also {
            it[14] = 0x00; it[15] = 0x04; it[16] = 0x00; it[17] = 0x01
        }
        assertNull(UdpVideoProtocol.parseFragment(tooBig))
        // fragCount=2（frameLen=100 只应 1 片）
        assertNull(UdpVideoProtocol.parseFragment(mutated(21, 2)))
    }

    @Test
    fun `frame id comparison handles wrap`() {
        assertTrue(UdpVideoProtocol.isNewerFrameId(1, 0))
        assertTrue(UdpVideoProtocol.isNewerFrameId(0, 0xFFFFFFFFL))
        assertTrue(!UdpVideoProtocol.isNewerFrameId(0xFFFFFFFFL, 0))
        assertTrue(!UdpVideoProtocol.isNewerFrameId(5, 5))
    }
}
