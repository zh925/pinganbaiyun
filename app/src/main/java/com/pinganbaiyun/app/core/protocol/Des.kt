package com.pinganbaiyun.app.core.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 单 DES（ECB / NoPadding，8 字节块）。
 *
 * 1:1 移植参考仓库 `utils/des.js`（基于 CryptoJS.DES）：
 * - 密钥：清洗为大写 hex 后**只取前 8 字节**（前 16 个 hex 字符），非 3DES。
 * - 数据块：必须恰好 8 字节（16 个 hex 字符）。
 *
 * CryptoJS.DES(ECB, NoPadding) 与标准 DES 一致，已用 FIPS 官方测试向量核对：
 *   key=133457799BBCDFF1, data=0123456789ABCDEF → 密文 85E813540F0AB405。
 * 因此本实现直接使用 JCE 的 "DES/ECB/NoPadding"，输出与小程序端逐字节一致。
 */
object Des {

    private val NON_HEX = Regex("[^0-9A-Fa-f]")

    private fun normalizeKeyHex(keyHex: String?): String {
        require(!keyHex.isNullOrEmpty()) { "缺少 DES 密钥" }
        val clean = NON_HEX.replace(keyHex, "").uppercase()
        require(clean.length >= 16) { "密钥长度不足 8 字节" }
        return clean.substring(0, 16)
    }

    private fun normalizeDataHex(dataHex: String?): String {
        require(!dataHex.isNullOrEmpty()) { "缺少 DES 数据块" }
        val clean = NON_HEX.replace(dataHex, "").uppercase()
        require(clean.length == 16) { "DES 数据块必须为 8 字节（16 个十六进制字符）" }
        return clean
    }

    private fun cipher(mode: Int, keyHex: String): Cipher {
        val keyBytes = HexUtils.hexToBytes(normalizeKeyHex(keyHex))
        val secret = SecretKeySpec(keyBytes, "DES")
        return Cipher.getInstance("DES/ECB/NoPadding").apply { init(mode, secret) }
    }

    /** 加密单块，返回大写十六进制密文。 */
    fun encryptBlockHex(keyHex: String, dataHex: String): String {
        val data = HexUtils.hexToBytes(normalizeDataHex(dataHex))
        return HexUtils.bytesToHex(cipher(Cipher.ENCRYPT_MODE, keyHex).doFinal(data))
    }

    /** 解密单块，返回大写十六进制明文。 */
    fun decryptBlockHex(keyHex: String, dataHex: String): String {
        val data = HexUtils.hexToBytes(normalizeDataHex(dataHex))
        return HexUtils.bytesToHex(cipher(Cipher.DECRYPT_MODE, keyHex).doFinal(data))
    }
}
