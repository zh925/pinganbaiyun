package com.pinganbaiyun.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四要素校验与 key 掩码测试（纯 JVM，不触及 org.json / Android SDK）。
 */
class DoorConfigValidationTest {

    private fun cfg(
        doorName: String = "大门",
        mac: String = "AA:BB:CC:DD:EE:FF",
        key: String = "133457799BBCDFF1",
        bt: String = "BYK0A0B0C0D",
    ) = DoorConfig(doorName = doorName, mac = mac, key = key, bluetoothName = bt)

    @Test
    fun validConfig_passes() {
        assertTrue(cfg().isValid)
    }

    @Test
    fun invalidMac_rejected() {
        assertFalse(cfg(mac = "AABBCCDDEEFF").isValid)
        assertFalse(cfg(mac = "AA:BB:CC:DD:EE").isValid)
    }

    @Test
    fun invalidKey_rejected() {
        assertFalse(cfg(key = "1234").isValid)              // 太短
        assertFalse(cfg(key = "133457799BBCDFF").isValid)   // 奇数长度
        assertFalse(cfg(key = "ZZZZ57799BBCDFF1").isValid)  // 非 hex
    }

    @Test
    fun invalidBluetoothName_rejected() {
        assertFalse(cfg(bt = "BYK01").isValid)              // 长度不足
        assertFalse(cfg(bt = "byk-0a0b0c0d").isValid)       // 含非法字符（规范化后长度不足）
    }

    @Test
    fun emptyDoorName_rejected() {
        assertFalse(cfg(doorName = "  ").isValid)
    }

    @Test
    fun maskedKey_masksMiddle() {
        // 16 位 key → 保留首尾各 2 位，中间 12 位掩码
        assertEquals("13" + "*".repeat(12) + "F1", cfg().maskedKey)
    }
}
