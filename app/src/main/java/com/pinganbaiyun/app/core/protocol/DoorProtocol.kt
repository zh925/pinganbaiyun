package com.pinganbaiyun.app.core.protocol

import java.util.Calendar

/**
 * 门禁蓝牙协议引擎——纯函数集合，无 Android/BLE 依赖，可直接单元测试。
 *
 * 1:1 移植自参考仓库 BaiyunKeys-preview：
 *  - `utils/bleProtocol.js`：帧构造、设备标识解析、时间戳、回执解析。
 *  - `utils/lockBiz.js`：MAC/Key 校验、开锁指令 `encryptUnlockCommand`。
 *
 * 帧格式：`A5 <len> … <checksum> 5A`，`checksum = (~(sum & 0xff)) & 0xff`（补码，置倒数第二字节）。
 * 所有加解密走 [Des]（单 DES / ECB / NoPadding，密钥取前 8 字节）。
 *
 * 已用参考仓库 (key, seed) 造黄金用例，安卓端与小程序端逐字节一致（见 DoorProtocolGoldenTest）。
 */
object DoorProtocol {

    // ---------- 基础校验（对应 lockBiz.js）----------

    private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val KEY_REGEX = Regex("^[0-9A-F]+$")

    fun sanitizeKey(value: String): String =
        value.uppercase().replace(Regex("[^0-9A-F]"), "")

    fun isValidKey(value: String): Boolean {
        val s = sanitizeKey(value)
        return s.length in 16..32 && s.length % 2 == 0 && KEY_REGEX.matches(s)
    }

    fun sanitizeMacInput(value: String): String =
        value.uppercase().replace(Regex("[^0-9A-F:]"), "")

    fun isValidMac(value: String): Boolean =
        MAC_REGEX.matches(value.trim().uppercase())

    /** MAC → 6 字节。 */
    fun macToBytes(mac: String): IntArray {
        require(isValidMac(mac)) { "MAC 格式错误" }
        return mac.split(":").map { it.toInt(16) }.toIntArray()
    }

    // ---------- 内部字节运算 ----------

    /** 补码校验字节：`(~(sum & 0xff)) & 0xff`。 */
    private fun complementByte(sum: Int): Int = (sum.inv()) and 0xff

    private fun sumBytes(bytes: IntArray): Int = bytes.sum()

    private fun xorBytes(bytes: IntArray): Int {
        if (bytes.isEmpty()) return 0
        var acc = bytes[0]
        for (i in 1 until bytes.size) acc = acc xor bytes[i]
        return acc and 0xff
    }

    private fun toIntArray(bytes: ByteArray): IntArray =
        IntArray(bytes.size) { bytes[it].toInt() and 0xff }

    private fun finalizeFrame(frame: IntArray): ByteArray {
        val checksum = sumBytes(frame)
        frame[frame.size - 2] = complementByte(checksum)
        return ByteArray(frame.size) { (frame[it] and 0xff).toByte() }
    }

    // ---------- 设备标识（对应 bleProtocol.js）----------

    /** 从蓝牙广播名第 3~10 位解析出 4 字节设备标识（大写字母数字，长度 ≥ 11）。 */
    fun extractDeviceIdParts(name: String?): IntArray {
        val clean = (name ?: "").uppercase().replace(Regex("[^0-9A-Z]"), "")
        require(clean.length >= 11) { "蓝牙名称格式不合法，无法解析设备标识" }
        return intArrayOf(
            clean.substring(3, 5).toInt(16),
            clean.substring(5, 7).toInt(16),
            clean.substring(7, 9).toInt(16),
            clean.substring(9, 11).toInt(16),
        )
    }

    /** 取 MAC 第 3~6 字节（下标 2..5）作为指令帧头部（对应 index.js deriveHeaderFromMac）。 */
    fun deriveHeaderFromMac(mac: String): IntArray {
        val segments = mac.uppercase().split(":").filter { it.isNotEmpty() }
        if (segments.size == 6) {
            return segments.drop(2).map { it.toInt(16) }.toIntArray()
        }
        val compact = mac.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        if (compact.length == 12) {
            val bytes = IntArray(6) { compact.substring(it * 2, it * 2 + 2).toInt(16) }
            return bytes.copyOfRange(2, 6)
        }
        throw IllegalArgumentException("MAC 地址格式不合法，无法生成握手标识")
    }

    // ---------- 指令构造 ----------

    /**
     * 握手指令（帧头 `A5 14 05 …`）。
     *
     * @param random 门锁下发的随机数种子（≥ 4 字节，取前 4 字节入帧；求和用全部字节）。
     * @param header 4 字节帧头（一般由 [deriveHeaderFromMac] 得到）。
     * @param productKey 设备 key（hex）。
     */
    fun buildHandshakeCommandWithHeader(random: ByteArray, header: IntArray, productKey: String): ByteArray {
        require(header.size == 4) { "握手指令头部需提供 4 个字节" }
        var sum = 0
        for (b in random) sum += b.toInt() and 0xff
        val keyBytes = HexUtils.hexToBytes(HexUtils.normalizeHex(productKey))
        for (b in keyBytes) sum += b.toInt() and 0xff
        val low = sum and 0xff
        val high = (sum shr 8) and 0xff
        val temp = intArrayOf(
            low, high,
            random[0].toInt() and 0xff, random[1].toInt() and 0xff,
            random[2].toInt() and 0xff, random[3].toInt() and 0xff,
            0, 0,
        )
        val enc = HexUtils.hexToBytes(Des.encryptBlockHex(productKey, HexUtils.intsToHex(temp)))
        val frame = intArrayOf(
            0xa5, 0x14, 0x05,
            header[0], header[1], header[2], header[3],
            0x00, 0x01, 0x07,
            enc[0].toInt() and 0xff, enc[1].toInt() and 0xff, enc[2].toInt() and 0xff, enc[3].toInt() and 0xff,
            enc[4].toInt() and 0xff, enc[5].toInt() and 0xff, enc[6].toInt() and 0xff, enc[7].toInt() and 0xff,
            0x00, 0x5a,
        )
        return finalizeFrame(frame)
    }

    /**
     * 开锁指令（`lockBiz.js encryptUnlockCommand`）。
     *
     * @param seed 随机数种子（本期实测 ≤ 6 字节，与 2 字节校验和拼成 8 字节单块加密）。
     */
    fun encryptUnlockCommand(seed: ByteArray, mac: String, key: String): ByteArray {
        val macBytes = macToBytes(mac)
        val sanitizedKey = sanitizeKey(key)
        val keyBytes = HexUtils.hexToBytes(sanitizedKey)
        var sum = 0
        for (b in seed) sum += b.toInt() and 0xff
        for (b in keyBytes) sum += b.toInt() and 0xff
        val sumBytes = intArrayOf(sum and 0xff, (sum shr 8) and 0xff)
        val paddedLength = (Math.ceil((sumBytes.size + seed.size) / 8.0) * 8).toInt()
        val padded = IntArray(paddedLength)
        padded[0] = sumBytes[0]
        padded[1] = sumBytes[1]
        for (i in seed.indices) padded[2 + i] = seed[i].toInt() and 0xff
        val enc = HexUtils.hexToBytes(Des.encryptBlockHex(sanitizedKey, HexUtils.intsToHex(padded)))
        val header = macBytes.copyOfRange(2, 6)
        val totalLength = enc.size + 12
        val frame = IntArray(totalLength)
        frame[0] = 0xa5
        frame[1] = totalLength and 0xff
        frame[2] = 0x05
        for (i in 0 until 4) frame[3 + i] = header[i]
        frame[7] = 0x00
        frame[8] = 0x01
        frame[9] = 0x07
        for (i in enc.indices) frame[10 + i] = enc[i].toInt() and 0xff
        frame[totalLength - 2] = 0x00
        frame[totalLength - 1] = 0x5a
        return finalizeFrame(frame)
    }

    /**
     * 时间同步指令（`A5 16 01 …`）。timeBytes 一般为 [generateTimeHex] 转出的 7 字节。
     */
    fun buildTimeSyncCommand(timeBytes: ByteArray, derivedName: String, sessionKey: String): ByteArray {
        val payload = IntArray(timeBytes.size + 1)
        for (i in timeBytes.indices) payload[i] = timeBytes[i].toInt() and 0xff
        payload[timeBytes.size] = 0
        val ids = extractDeviceIdParts(derivedName)
        val enc = HexUtils.hexToBytes(Des.encryptBlockHex(sessionKey, HexUtils.intsToHex(payload)))
        val bodyHex = HexUtils.intsToHex(ids) + "0000" + HexUtils.bytesToHex(timeBytes)
        val body = toIntArray(HexUtils.hexToBytes(bodyHex))
        val sumBody = sumBytes(body) and 0xff
        val xorBody = xorBytes(body)
        val frame = intArrayOf(
            0xa5, 0x16, 0x01,
            ids[0], ids[1], ids[2], ids[3],
            0x00, 0x00, sumBody, xorBody, 0x06,
            enc[0].toInt() and 0xff, enc[1].toInt() and 0xff, enc[2].toInt() and 0xff, enc[3].toInt() and 0xff,
            enc[4].toInt() and 0xff, enc[5].toInt() and 0xff, enc[6].toInt() and 0xff, enc[7].toInt() and 0xff,
            0x00, 0x5a,
        )
        return finalizeFrame(frame)
    }

    /**
     * 通信密钥协商指令（`A5 16 01 …`）。
     *
     * 说明：参考仓库该函数的 pivot 仅 6 字节（`utils/bleProtocol.js:119`），
     * 交给单块 DES 会因长度不足 16 hex 抛错；P0 开门主流程（握手 → 开锁）并不调用它，
     * 通信密钥由设备下发后经 [decryptCommKey] 还原。此处逐行忠实移植，供后续对照真机时使用。
     */
    fun buildCommKeyCommand(random: ByteArray, derivedName: String, productKey: String): ByteArray {
        val ids = extractDeviceIdParts(derivedName)
        val pivot = intArrayOf(
            random[0].toInt() and 0xff, random[1].toInt() and 0xff,
            random[2].toInt() and 0xff, random[3].toInt() and 0xff, 0, 0,
        )
        val enc = HexUtils.hexToBytes(Des.encryptBlockHex(productKey, HexUtils.intsToHex(pivot)))
        val bodyHex = HexUtils.intsToHex(ids) + "0000" + HexUtils.bytesToHex(random)
        val body = toIntArray(HexUtils.hexToBytes(bodyHex))
        val sumBody = sumBytes(body) and 0xff
        val xorBody = xorBytes(body)
        val frame = intArrayOf(
            0xa5, 0x16, 0x01,
            ids[0], ids[1], ids[2], ids[3],
            0x00, 0x00, sumBody, xorBody, 0x09,
            enc[0].toInt() and 0xff, enc[1].toInt() and 0xff, enc[2].toInt() and 0xff, enc[3].toInt() and 0xff,
            enc[4].toInt() and 0xff, enc[5].toInt() and 0xff, enc[6].toInt() and 0xff, enc[7].toInt() and 0xff,
            0x00, 0x5a,
        )
        return finalizeFrame(frame)
    }

    // ---------- 时间戳 ----------

    /**
     * 生成门锁时间戳 hex：`年(hex) 月 日 时 分 秒 周`。
     *
     * 注意：仅**年**按 (十位<<4 | 个位) 编成一个字节的 hex；月/日/时/分/秒为两位**十进制**
     * 零填充直接拼接（与 `bleProtocol.js generateTimeHex` 一致）；周为 00(日)~06(六)。
     */
    fun generateTimeHex(calendar: Calendar = Calendar.getInstance()): String {
        val year = calendar.get(Calendar.YEAR) - 2000
        val yearHigh = year / 10
        val yearLow = year % 10
        val yearHex = ((yearHigh shl 4) or yearLow).toString(16).padStart(2, '0')
        val month = (calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val day = calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val hour = calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val minute = calendar.get(Calendar.MINUTE).toString().padStart(2, '0')
        val second = calendar.get(Calendar.SECOND).toString().padStart(2, '0')
        // Calendar.DAY_OF_WEEK: 1=周日 .. 7=周六 → 映射 00..06
        val weekday = (calendar.get(Calendar.DAY_OF_WEEK) - 1).toString().padStart(2, '0')
        return "$yearHex$month$day$hour$minute$second$weekday".uppercase()
    }

    // ---------- 回执 / 解密 ----------

    /**
     * 从设备下发帧还原通信密钥。忠实移植 `bleProtocol.js decryptCommKey`。
     * 单块 DES 解密得 16 hex（< 32）→ 返回 ""；真机通信密钥流程需结合完整时序另行核对。
     */
    fun decryptCommKey(bodyHex: String, productKey: String): String {
        val decrypted = Des.decryptBlockHex(productKey, bodyHex)
        if (decrypted.length < 32) return ""
        return decrypted.substring(16, 32).uppercase()
    }

    /** 用会话密钥解密单块，返回明文 hex。 */
    fun decryptWithSessionKey(bodyHex: String, sessionKey: String): String =
        Des.decryptBlockHex(sessionKey, bodyHex)

    /**
     * 解析开门回执帧。取 `substr(20,16)` 为密文单块，DES 解密后状态字节 = 明文 `substr(4,2)`。
     * `00`=开门成功、`02`=门已打开、其它=失败（密码无效）。
     */
    fun decodeOpenResult(frameHex: String, productKey: String): OpenResult {
        val clean = HexUtils.normalizeHex(frameHex)
        if (clean.length < 36) return OpenResult(OpenResult.CODE_INVALID, "数据长度不足，无法解析回执")
        val msgBodyHex = clean.substring(20, 36)
        val decrypted = Des.decryptBlockHex(productKey, msgBodyHex)
        if (decrypted.length != 16) return OpenResult(OpenResult.CODE_INVALID, "回执解密失败")
        return when (val status = decrypted.substring(4, 6)) {
            OpenResult.CODE_SUCCESS -> OpenResult(status, "开门成功")
            OpenResult.CODE_ALREADY_OPEN -> OpenResult(status, "门已打开")
            else -> OpenResult(status, "开门失败，密码无效")
        }
    }
}
