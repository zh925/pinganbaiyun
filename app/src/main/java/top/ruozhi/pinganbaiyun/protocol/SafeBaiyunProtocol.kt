package top.ruozhi.pinganbaiyun.protocol

import android.annotation.SuppressLint
import top.ruozhi.pinganbaiyun.model.DoorValidation
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol baseline: dogproton/SafeBaiyun@c4ff50888ef9244870a30304b3a5e6d0d5a96eac
 * LockBiz.kt:12-63 and FDes.kt:15-29. This intentionally implements only the
 * confirmed 20-byte frame; unsupported seed sizes fail closed.
 */
object SafeBaiyunProtocol {
    const val SERVICE_UUID = "14839ac4-7d7e-415c-9a42-167340cf2339"

    @SuppressLint("GetInstance") // Exact legacy cipher required by the confirmed physical-device protocol.
    fun buildUnlockFrame(mac: String, key: String, seed: ByteArray): Result<ByteArray> = runCatching {
        val validated = DoorValidation.validate("protocol", mac, key).getOrThrow()
        require(seed.isNotEmpty() && seed.size <= 6) { "设备随机数长度不受支持" }
        val macBytes = validated.mac.split(':').map { it.toInt(16).toByte() }.toByteArray()
        val keyBytes = validated.key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val sum = (seed.asSequence() + keyBytes.asSequence()).sumOf { it.toInt() and 0xff }
        val plain = ByteArray(8)
        plain[0] = sum.toByte()
        plain[1] = (sum ushr 8).toByte()
        seed.copyInto(plain, destinationOffset = 2)

        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
        val encrypted = cipher.doFinal(plain)
        check(encrypted.size == 8) { "协议加密失败" }

        ByteArray(20).also { frame ->
            frame[0] = 0xA5.toByte()
            frame[1] = 0x14
            frame[2] = 0x05
            macBytes.copyInto(frame, destinationOffset = 3, startIndex = 2, endIndex = 6)
            frame[7] = 0x00
            frame[8] = 0x01
            frame[9] = 0x07
            encrypted.copyInto(frame, destinationOffset = 10)
            frame[19] = 0x5A
            val frameSum = frame.sumOf { it.toInt() and 0xff }
            frame[18] = frameSum.inv().toByte()
        }
    }
}
