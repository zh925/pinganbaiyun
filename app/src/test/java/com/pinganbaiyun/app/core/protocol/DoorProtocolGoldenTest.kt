package com.pinganbaiyun.app.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.util.Calendar
import java.util.GregorianCalendar
import org.junit.Test

/**
 * 协议引擎黄金用例——所有期望值均由参考仓库 BaiyunKeys-preview 的原始
 * `utils/des.js` / `utils/bleProtocol.js` / `utils/lockBiz.js` 用 Node 直接运行产出，
 * 保证安卓端与小程序端**逐字节一致**（对应验收标准 AC-12）。
 *
 * 固定输入：
 *   key  = 133457799BBCDFF1 （FIPS DES 官方测试密钥）
 *   seed = 11 22 33 44
 *   mac  = AA:BB:CC:DD:EE:FF
 *   btName = BYK0A0B0C0D
 */
class DoorProtocolGoldenTest {

    private val key16 = "133457799BBCDFF1"
    private val key32 = "133457799BBCDFF10011223344556677"
    private val seed = byteArrayOf(0x11, 0x22, 0x33, 0x44)

    @Test
    fun des_matchesFipsVector() {
        // FIPS PUB 81 官方测试向量，也与 CryptoJS.DES(ECB,NoPadding) 输出一致
        assertEquals("85E813540F0AB405", Des.encryptBlockHex(key16, "0123456789ABCDEF"))
        assertEquals("0123456789ABCDEF", Des.decryptBlockHex(key16, "85E813540F0AB405"))
    }

    @Test
    fun des_usesFirst8KeyBytes_notTripleDes() {
        // 32-hex key 只取前 16 hex，结果应与 16-hex key 相同
        assertEquals(
            Des.encryptBlockHex(key16, "0123456789ABCDEF"),
            Des.encryptBlockHex(key32, "0123456789ABCDEF"),
        )
    }

    @Test
    fun extractDeviceIdParts_golden() {
        assertArrayEquals(intArrayOf(10, 11, 12, 13), DoorProtocol.extractDeviceIdParts("BYK0A0B0C0D"))
    }

    @Test
    fun deriveHeaderFromMac_golden() {
        assertArrayEquals(intArrayOf(0xCC, 0xDD, 0xEE, 0xFF), DoorProtocol.deriveHeaderFromMac("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun buildHandshakeCommandWithHeader_golden() {
        val header = intArrayOf(0xCC, 0xDD, 0xEE, 0xFF)
        val frame = DoorProtocol.buildHandshakeCommandWithHeader(seed, header, key16)
        assertEquals("A51405CCDDEEFF000107642915C375062121275A", HexUtils.bytesToHex(frame))
    }

    @Test
    fun encryptUnlockCommand_golden() {
        val frame = DoorProtocol.encryptUnlockCommand(seed, "AA:BB:CC:DD:EE:FF", key16)
        assertEquals("A51405CCDDEEFF000107642915C375062121275A", HexUtils.bytesToHex(frame))
    }

    @Test
    fun decodeOpenResult_success00() {
        val r = DoorProtocol.decodeOpenResult("A5000000000000000000BCAE6A89DAEAEBC9005A", key16)
        assertEquals("00", r.code)
        assertEquals("开门成功", r.message)
    }

    @Test
    fun decodeOpenResult_alreadyOpen02() {
        val r = DoorProtocol.decodeOpenResult("A5000000000000000000750BA0181E592CE5005A", key16)
        assertEquals("02", r.code)
        assertEquals("门已打开", r.message)
    }

    @Test
    fun decodeOpenResult_failure() {
        val r = DoorProtocol.decodeOpenResult("A500000000000000000045D3D110F728C39E005A", key16)
        assertEquals("09", r.code)
        assertEquals("开门失败，密码无效", r.message)
    }

    @Test
    fun generateTimeHex_golden() {
        // 2026-07-07 13:24:35，周二
        val cal: Calendar = GregorianCalendar(2026, Calendar.JULY, 7, 13, 24, 35)
        assertEquals("26070713243502", DoorProtocol.generateTimeHex(cal))
    }

    @Test
    fun buildTimeSyncCommand_golden() {
        val timeBytes = HexUtils.hexToBytes("26070713243502")
        val frame = DoorProtocol.buildTimeSyncCommand(timeBytes, "BYK0A0B0C0D", key16)
        assertEquals("A516010A0B0C0D0000D026060504A97F58D11B8CBE5A", HexUtils.bytesToHex(frame))
    }
}
