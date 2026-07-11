package com.pinganbaiyun.app.core.ble

import com.pinganbaiyun.app.core.protocol.HexUtils

/**
 * 门锁识别的**纯函数**部分（对齐参考小程序 `pages/index/index.js` 的 matchDevice + 工具函数）。
 * 不依赖 Android，便于单测；广播原始数据的拼装（需 [android.bluetooth.le.ScanRecord]）仍由
 * `BleUnlockManager.buildAdvHex` 完成，再把拼好的 hex 交给本对象的 [matchWay] 判定。
 *
 * 5 重匹配：① 广播 MAC 精确；② 蓝牙名相同；③ 广播含配置 MAC（正序）；
 * ④ 广播含配置 MAC（反序）；⑤ 广播含配置蓝牙名 ASCII。命中任一即判为门锁。
 */
object DoorMatcher {

    /** 规范化 MAC 为大写无分隔 hex（对应 `normalizeMacForCompare`）。 */
    fun normalizeMacHex(mac: String): String =
        mac.uppercase().replace(Regex("[^0-9A-F]"), "")

    /** MAC 按字节反序（对应 `reverseMacHex`）：`81D9…C8EE` → `EEC8…D981`。 */
    fun reverseMacHex(mac: String): String {
        if (mac.length < 2) return ""
        val sb = StringBuilder()
        var i = mac.length
        while (i > 0) {
            val start = (i - 2).coerceAtLeast(0)
            sb.append(mac.substring(start, i))
            i -= 2
        }
        return sb.toString()
    }

    /** 文本 ASCII → 大写 hex（对应 `asciiToHex`）。 */
    fun asciiToHex(text: String): String =
        HexUtils.bytesToHex(text.toByteArray(Charsets.UTF_8))

    /**
     * 判定是否门锁；返回命中方式（MAC / 蓝牙名 / 广播含MAC / 广播含反序MAC / 广播含名），未命中返回 null。
     *
     * @param deviceAddress 广播设备地址（原始，带 `:`）
     * @param deviceName 广播设备名（原始大小写）
     * @param advHex 拼好的可检索广播 hex（含原始包/厂商/服务数据/UUID/名 ASCII）
     * @param targetMac 规范化后的配置 MAC（无分隔大写）
     * @param targetName 配置蓝牙名（大写）
     */
    fun matchWay(
        deviceAddress: String,
        deviceName: String,
        advHex: String,
        targetMac: String,
        targetName: String,
    ): String? {
        val reversedMac = reverseMacHex(targetMac)
        val targetNameUpper = targetName.uppercase()
        val targetNameHex = if (targetNameUpper.isNotEmpty()) asciiToHex(targetNameUpper) else ""
        // ① 广播 MAC 精确
        if (targetMac.isNotEmpty() && normalizeMacHex(deviceAddress) == targetMac) return "MAC"
        // ② 蓝牙名相同
        if (targetNameUpper.isNotEmpty() && deviceName.uppercase() == targetNameUpper) return "蓝牙名"
        // ③④ 广播含配置 MAC（正序 / 反序）
        if (targetMac.isNotEmpty() && advHex.contains(targetMac)) return "广播含MAC"
        if (reversedMac.isNotEmpty() && advHex.contains(reversedMac)) return "广播含反序MAC"
        // ⑤ 广播含配置蓝牙名 ASCII
        if (targetNameHex.isNotEmpty() && advHex.contains(targetNameHex)) return "广播含名"
        return null
    }
}
