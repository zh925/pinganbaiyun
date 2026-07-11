package com.pinganbaiyun.app.core.ble

import com.pinganbaiyun.app.core.protocol.DoorProtocol
import com.pinganbaiyun.app.core.protocol.HexUtils
import com.pinganbaiyun.app.core.protocol.OpenResult

/**
 * 门锁 notify 回执分类——纯函数，便于单测。移植自 `pages/index/index.js` 的 processBleNotification：
 *  - header = hex[0:2]，command = hex[4:6]，type = hex[18:20]，tail = 末 2 位。
 *  - command `04` 且总长 > 24 hex → 握手成功（门锁开始执行开门）；否则握手失败，返回码取 hex[14:18] 两字节互换。
 *  - type `87` 且 tail `5A` → 开门最终回执，交 [DoorProtocol.decodeOpenResult] 解析。
 */
object NotificationParser {

    sealed class Notification {
        data class HandshakeSuccess(val rawHex: String) : Notification()
        data class HandshakeFailed(val codeWord: String) : Notification()
        data class OpenResultReceived(val result: OpenResult) : Notification()
        /** 其它片段（通信密钥/时间同步/黑名单等），主流程可忽略或另行处理。 */
        data class Fragment(val type: String, val rawHex: String) : Notification()
        data object Ignored : Notification()
    }

    fun parse(rawHex: String, productKey: String): Notification {
        val hex = HexUtils.normalizeHex(rawHex)
        if (hex.length < 2) return Notification.Ignored
        val header = hex.substring(0, 2).uppercase()
        if (header != "A5") return Notification.Ignored
        val command = if (hex.length >= 6) hex.substring(4, 6).uppercase() else ""
        val tail = hex.substring(hex.length - 2).uppercase()
        val type = if (hex.length >= 20) hex.substring(18, 20).uppercase() else ""

        if (command == "04") {
            return if (hex.length > 24) {
                Notification.HandshakeSuccess(hex)
            } else {
                // 握手失败：状态字对齐参考主路径（index.js processBleNotification）——
                // statusField = hex[14:18]，再两字节高低互换得 codeWord。
                // 如 statusField="0B00" → codeWord="000B"（密钥/SN 不匹配等诊断码）。
                val codeWord = if (hex.length >= 18) {
                    val statusField = hex.substring(14, 18)
                    "${statusField.substring(2, 4)}${statusField.substring(0, 2)}"
                } else ""
                Notification.HandshakeFailed(codeWord.uppercase())
            }
        }
        if (type == "87" && tail == "5A") {
            return Notification.OpenResultReceived(DoorProtocol.decodeOpenResult(hex, productKey))
        }
        if (type in setOf("89", "86", "82")) {
            return Notification.Fragment(type, hex)
        }
        return Notification.Ignored
    }
}
