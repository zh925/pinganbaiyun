package com.pinganbaiyun.app.data.model

import com.pinganbaiyun.app.core.protocol.DoorProtocol
import org.json.JSONObject

/**
 * 门禁配置（四要素）——既是本地持久化单元，也是 NFC 卡承载的数据模型。本期一卡一门。
 *
 * 字段与校验取自参考仓库 `config.js` / `lockBiz.js`：
 *  - [doorName]      门/设备显示名，非空
 *  - [mac]           蓝牙 MAC，`^([0-9A-F]{2}:){5}[0-9A-F]{2}$`（大写）
 *  - [key]           开锁密钥（DES key, hex），`^[0-9A-F]+$`，长度 16–32 且偶数（大写）
 *  - [bluetoothName] 蓝牙广播名，用于解析 deviceId 头部，大写字母数字长度 ≥ 11
 */
data class DoorConfig(
    val id: String = "",
    val doorName: String = "",
    val mac: String = "",
    val key: String = "",
    val bluetoothName: String = "",
) {
    /** 展示/日志用的掩码 key（仅保留首尾各 2 位）。任何 UI/日志都应使用它而非明文。 */
    val maskedKey: String
        get() = when {
            key.length <= 4 -> "*".repeat(key.length)
            else -> key.take(2) + "*".repeat(key.length - 4) + key.takeLast(2)
        }

    /** 逐字段校验；返回失败原因列表，空列表表示全部通过。 */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (doorName.trim().isEmpty()) errors += "门名不能为空"
        if (!DoorProtocol.isValidMac(mac)) errors += "MAC 格式非法（应形如 AA:BB:CC:DD:EE:FF）"
        if (!DoorProtocol.isValidKey(key)) errors += "Key 非法（需 16–32 位偶数长度大写十六进制）"
        if (!isValidBluetoothName(bluetoothName)) errors += "蓝牙名非法（需大写字母数字且长度 ≥ 11）"
        return errors
    }

    val isValid: Boolean get() = validate().isEmpty()

    /** 序列化为 NDEF / 存储用的 JSON 字符串。 */
    fun toJson(): String = JSONObject().apply {
        put(KEY_DOOR_NAME, doorName)
        put(KEY_MAC, mac)
        put(KEY_KEY, key)
        put(KEY_BLUETOOTH_NAME, bluetoothName)
    }.toString()

    companion object {
        const val KEY_DOOR_NAME = "doorName"
        const val KEY_MAC = "mac"
        const val KEY_KEY = "key"
        const val KEY_BLUETOOTH_NAME = "bluetoothName"

        private val BT_NAME_REGEX = Regex("^[0-9A-Z]{11,}$")

        fun isValidBluetoothName(name: String): Boolean =
            BT_NAME_REGEX.matches(name.trim().uppercase())

        /** 规范化输入：MAC/key/蓝牙名统一大写，doorName 去空白。 */
        fun normalized(
            id: String = "",
            doorName: String,
            mac: String,
            key: String,
            bluetoothName: String,
        ): DoorConfig = DoorConfig(
            id = id,
            doorName = doorName.trim(),
            mac = mac.uppercase().trim(),
            key = DoorProtocol.sanitizeKey(key),
            bluetoothName = bluetoothName.uppercase().trim(),
        )

        /** 从 JSON 字符串解析（写卡回读、读卡入口共用）。 */
        fun fromJson(json: String, id: String = ""): DoorConfig {
            val obj = JSONObject(json)
            return normalized(
                id = id,
                doorName = obj.optString(KEY_DOOR_NAME),
                mac = obj.optString(KEY_MAC),
                key = obj.optString(KEY_KEY),
                bluetoothName = obj.optString(KEY_BLUETOOTH_NAME),
            )
        }
    }
}
