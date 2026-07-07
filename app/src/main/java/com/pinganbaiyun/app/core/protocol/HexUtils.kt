package com.pinganbaiyun.app.core.protocol

/**
 * 十六进制与字节数组互转工具。
 *
 * 移植自参考仓库 BaiyunKeys-preview 的 `utils/lockBiz.js`(bytesToHex/hexToBytes)
 * 与 `utils/bleProtocol.js`(normalizeHex)，行为保持逐字节一致。
 */
object HexUtils {

    private val NON_HEX = Regex("[^0-9A-Fa-f]")

    /** 清洗为大写十六进制串；奇数长度左侧补 0（对应 JS normalizeHex）。 */
    fun normalizeHex(hex: String?): String {
        if (hex.isNullOrEmpty()) return ""
        val clean = NON_HEX.replace(hex, "").uppercase()
        return if (clean.length % 2 == 0) clean else "0$clean"
    }

    /** 十六进制串 → 字节数组。要求偶数长度。 */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "十六进制字符串长度必须为偶数" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    /** 字节数组 → 大写十六进制串（每字节两位）。 */
    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789ABCDEF"[v ushr 4])
            sb.append("0123456789ABCDEF"[v and 0x0f])
        }
        return sb.toString()
    }

    /** IntArray（每元素代表一个字节，取低 8 位）→ 大写十六进制串。 */
    fun intsToHex(bytes: IntArray): String {
        val ba = ByteArray(bytes.size) { (bytes[it] and 0xff).toByte() }
        return bytesToHex(ba)
    }
}
