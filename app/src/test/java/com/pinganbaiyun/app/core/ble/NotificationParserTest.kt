package com.pinganbaiyun.app.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * notify 回执路由分类测试（不依赖真机）。OpenResult 的具体解码在
 * DoorProtocolGoldenTest.decodeOpenResult_* 中以黄金用例覆盖。
 */
class NotificationParserTest {

    private val key = "133457799BBCDFF1"

    @Test
    fun handshakeSuccess_whenCmd04AndLongFrame() {
        // command(substr4,2)=04，总长 > 24 hex → 握手成功
        val hex = "A51404CCDDEEFF0001076429000000000000005A"
        val n = NotificationParser.parse(hex, key)
        assertTrue(n is NotificationParser.Notification.HandshakeSuccess)
    }

    @Test
    fun handshakeFailed_whenCmd04AndShortFrame() {
        // command=04，短帧（≤24 hex）→ 握手失败；返回码对齐参考主路径：
        // statusField = hex[14:18] 后两字节高低互换。此处 statusField="0B00" → codeWord="000B"
        // （与从帧尾取值的旧实现 0x5000 区分，校验偏移与互换方向均正确）
        val hex = "A50B04CCDDEEFF0B00505A"
        val n = NotificationParser.parse(hex, key)
        assertTrue(n is NotificationParser.Notification.HandshakeFailed)
        assertEquals("000B", (n as NotificationParser.Notification.HandshakeFailed).codeWord)
    }

    @Test
    fun ignored_whenNotA5Header() {
        assertEquals(
            NotificationParser.Notification.Ignored,
            NotificationParser.parse("FF0102030405", key),
        )
    }
}
