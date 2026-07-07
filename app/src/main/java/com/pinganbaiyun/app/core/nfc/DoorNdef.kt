package com.pinganbaiyun.app.core.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.pinganbaiyun.app.data.model.DoorConfig
import java.nio.charset.StandardCharsets

/**
 * 门禁四要素 ↔ NDEF Message 编解码。
 *
 * 载荷格式：单条 [DoorConfig] 序列化为 JSON，以自定义 MIME
 * `application/vnd.pinganbaiyun.door` 的 MIME NDEF Record 承载（本期一卡一门）。
 * 该 MIME 同时用于 AndroidManifest 的 `NDEF_DISCOVERED` intent-filter，实现冷启动精确唤起。
 */
object DoorNdef {

    const val MIME_TYPE = "application/vnd.pinganbaiyun.door"

    /** 组装写卡用 NDEF Message。 */
    fun encode(config: DoorConfig): NdefMessage {
        val record = NdefRecord.createMime(
            MIME_TYPE,
            config.toJson().toByteArray(StandardCharsets.UTF_8),
        )
        return NdefMessage(arrayOf(record))
    }

    /** 序列化后的字节长度，用于写卡前的卡容量检测。 */
    fun encodedByteLength(config: DoorConfig): Int = encode(config).toByteArray().size

    /**
     * 从 NDEF Message 解析四要素；未命中本 APP MIME 或 JSON 非法时返回 null。
     */
    fun decode(message: NdefMessage?): DoorConfig? {
        val records = message?.records ?: return null
        for (record in records) {
            if (!isDoorRecord(record)) continue
            val json = String(record.payload, StandardCharsets.UTF_8)
            return runCatching { DoorConfig.fromJson(json) }.getOrNull()
        }
        return null
    }

    private fun isDoorRecord(record: NdefRecord): Boolean {
        if (record.tnf != NdefRecord.TNF_MIME_MEDIA) return false
        val type = String(record.type, StandardCharsets.US_ASCII)
        return type.equals(MIME_TYPE, ignoreCase = true)
    }
}
