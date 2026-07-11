package com.pinganbaiyun.app.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 门锁 5 重识别的纯函数测试（对齐参考小程序 `pages/index/index.js` matchDevice）。
 * 配置：mac=81:D9:6C:FC:C8:EE，蓝牙名=BY96CFCC8EE。
 */
class DoorMatcherTest {

    private val mac = "81:D9:6C:FC:C8:EE"
    private val name = "BY96CFCC8EE"

    @Test
    fun normalize_and_reverse_mac() {
        assertEquals("81D96CFCC8EE", DoorMatcher.normalizeMacHex(mac))
        assertEquals("81D96CFCC8EE", DoorMatcher.normalizeMacHex("81d96cfcc8ee"))
        // 字节反序：81 D9 6C FC C8 EE → EE C8 FC 6C D9 81
        assertEquals("EEC8FC6CD981", DoorMatcher.reverseMacHex("81D96CFCC8EE"))
    }

    @Test
    fun asciiToHex_of_name() {
        // 'B'=0x42 'Y'=0x59 '9'=0x39 '6'=0x36 'C'=0x43 ...
        assertEquals("4259393643464343384545", DoorMatcher.asciiToHex(name))
    }

    @Test
    fun match_by_mac_exact() {
        val way = DoorMatcher.matchWay(
            deviceAddress = "81:d9:6c:FC:C8:EE",
            deviceName = "其它设备",
            advHex = "",
            targetMac = DoorMatcher.normalizeMacHex(mac),
            targetName = name,
        )
        assertEquals("MAC", way)
    }

    @Test
    fun match_by_name() {
        val way = DoorMatcher.matchWay(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "by96cfcc8ee", // 大小写无关
            advHex = "",
            targetMac = DoorMatcher.normalizeMacHex(mac),
            targetName = name,
        )
        assertEquals("蓝牙名", way)
    }

    @Test
    fun match_by_adv_contains_mac_forward() {
        // 广播厂商数据里正序含 MAC（如 ...81D96CFCC8EE...）
        val adv = "FF0181D96CFCC8EE77"
        val way = DoorMatcher.matchWay(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "",
            advHex = adv,
            targetMac = DoorMatcher.normalizeMacHex(mac),
            targetName = name,
        )
        assertEquals("广播含MAC", way)
    }

    @Test
    fun match_by_adv_contains_mac_reversed() {
        // 门锁固件常见：广播含反序 MAC（EEC8FC6CD981）
        val adv = "A1EEC8FC6CD98109"
        val way = DoorMatcher.matchWay(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "",
            advHex = adv,
            targetMac = DoorMatcher.normalizeMacHex(mac),
            targetName = name,
        )
        assertEquals("广播含反序MAC", way)
    }

    @Test
    fun match_by_adv_contains_name_ascii() {
        val adv = "02" + DoorMatcher.asciiToHex(name) + "99"
        val way = DoorMatcher.matchWay(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "",
            advHex = adv,
            targetMac = DoorMatcher.normalizeMacHex(mac),
            targetName = name,
        )
        assertEquals("广播含名", way)
    }

    @Test
    fun no_match_for_unrelated_device() {
        val way = DoorMatcher.matchWay(
            deviceAddress = "11:22:33:44:55:66",
            deviceName = "mobike",
            advHex = "00112233445566778899",
            targetMac = DoorMatcher.normalizeMacHex(mac),
            targetName = name,
        )
        assertNull(way)
    }
}
