package com.pinganbaiyun.app.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FrameAssembler` 帧组包纯函数测试，锁定 `A5 <len> … <cs> 5A`（**len = 总字节数，含 A5 头与 5A 尾**）
 * 的组包语义。帧内容（payload/cs）仅占位，本测试只关心按 len 边界切分与跨分包续接。
 */
class FrameAssemblerTest {

    /** 构造 len 字节的帧 hex：A5 + len + (len-4) 字节 payload + cs + 5A。 */
    private fun frame(len: Int, payloadByte: String = "11"): String {
        val lenHex = len.toString(16).uppercase().padStart(2, '0')
        val payload = payloadByte.repeat((len - 4).coerceAtLeast(0))
        return FrameAssembler.HEAD_A5 + lenHex + payload + "CC5A"
    }

    @Test
    fun emptyBuffer_yieldsNothing() {
        val r = FrameAssembler.extract("")
        assertTrue(r.frames.isEmpty())
        assertEquals("", r.remaining)
    }

    @Test
    fun singleCompleteFrame() {
        val f = frame(8)
        val r = FrameAssembler.extract(f)
        assertEquals(listOf(f), r.frames)
        assertEquals("", r.remaining)
    }

    @Test
    fun leadingGarbage_skippedToHead() {
        // 帧前的非 A5 噪声应被跳到首个 A5
        val f = frame(8)
        val r = FrameAssembler.extract("FFEE99" + f)
        assertEquals(listOf(f), r.frames)
        assertEquals("", r.remaining)
    }

    @Test
    fun invalidLen_tooSmall_dropsHead() {
        // len=3（< 4）不可信：丢弃该 A5，继续找下一个合法帧
        val valid = frame(8)
        val r = FrameAssembler.extract("A503FF" + valid)
        assertEquals(listOf(valid), r.frames)
        assertEquals("", r.remaining)
    }

    @Test
    fun incompleteFrame_keptAsRemaining() {
        // 缓冲不足以组完整帧（差尾部）：不提取，留作剩余等下一包
        val partial = frame(8).substring(0, 10) // 仅 10 hex，需 16 才完整
        val r = FrameAssembler.extract(partial)
        assertTrue(r.frames.isEmpty())
        assertEquals(partial, r.remaining)
    }

    /**
     * 核心：长回执跨 MTU 分包、组包还原。默认 MTU 下一个长帧被拆成两个 notify 分包，
     * 第一包到达时帧不完整（[extract] 返回 0 帧、把半帧留在 remaining），
     * 第二包续接后才组出完整帧——模拟 `BleUnlockManager` 的累积缓冲续接行为。
     */
    @Test
    fun longFrameAcrossMtuFragments_reassembled() {
        val full = frame(20) // 40 hex，超过单包常被拆分
        val firstFrag = full.substring(0, 20)
        val secondFrag = full.substring(20, 40)

        // 第一包：不完整，留作剩余
        var buffer = firstFrag
        val r1 = FrameAssembler.extract(buffer)
        assertTrue("首包不应产出帧", r1.frames.isEmpty())
        assertEquals(firstFrag, r1.remaining)

        // 第二包续接：组出完整帧
        buffer = r1.remaining + secondFrag
        val r2 = FrameAssembler.extract(buffer)
        assertEquals(listOf(full), r2.frames)
        assertEquals("", r2.remaining)
    }

    @Test
    fun twoConsecutiveFrames() {
        val a = frame(8)
        val b = frame(10)
        val r = FrameAssembler.extract(a + b)
        assertEquals(listOf(a, b), r.frames)
        assertEquals("", r.remaining)
    }

    @Test
    fun framesSeparatedByGarbage() {
        // 两帧之间夹噪声：第一帧提取后，中间噪声在找第二帧 A5 时被跳过
        val a = frame(8)
        val b = frame(10)
        val r = FrameAssembler.extract(a + "9988" + b)
        assertEquals(listOf(a, b), r.frames)
        assertEquals("", r.remaining)
    }
}
