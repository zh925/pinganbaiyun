package top.ruozhi.pinganbaiyun.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeBaiyunProtocolTest {
    @Test fun `golden vector matches pinned SafeBaiyun baseline`() {
        // SafeBaiyun@c4ff508 LockBiz.kt:12-63, FDes.kt:15-29.
        val actual = SafeBaiyunProtocol.buildUnlockFrame(
            "AA:BB:CC:DD:EE:FF",
            "0123456789ABCDEF",
            hex("010203040506"),
        ).getOrThrow()
        assertArrayEquals(hex("A51405CCDDEEFF0001071C313CC4BB2EA59FCF5A"), actual)
    }

    @Test fun `invalid seed never produces partial frame`() {
        assertTrue(SafeBaiyunProtocol.buildUnlockFrame("AA:BB:CC:DD:EE:FF", "0123456789ABCDEF", byteArrayOf()).isFailure)
        assertTrue(SafeBaiyunProtocol.buildUnlockFrame("AA:BB:CC:DD:EE:FF", "0123456789ABCDEF", ByteArray(7)).isFailure)
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
