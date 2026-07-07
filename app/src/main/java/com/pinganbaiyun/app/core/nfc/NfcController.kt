package com.pinganbaiyun.app.core.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import com.pinganbaiyun.app.data.model.DoorConfig

/**
 * NFC 统一入口——封装前台调度、读卡解析与写卡（含写后回读校验）。
 *
 * UI 层用法：
 *  - Activity.onResume → [enableForegroundDispatch]，onPause → [disableForegroundDispatch]
 *  - onCreate/onNewIntent 收到的 Intent → [readFromIntent] 解析四要素
 *  - 维护页「写入 NFC 卡」→ 贴卡拿到 [Tag] 后 → [writeConfig]
 */
class NfcController(private val activity: Activity) {

    val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** 开启前台调度：APP 在前台时优先接收碰卡，避免二次弹选择框。 */
    fun enableForegroundDispatch() {
        val adapter = adapter ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(activity, activity.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(activity, 0, intent, flags)
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType(DoorNdef.MIME_TYPE)
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                throw IllegalStateException("NFC MIME 过滤配置错误", e)
            }
        }
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        adapter.enableForegroundDispatch(
            activity,
            pendingIntent,
            arrayOf(ndefFilter, techFilter, tagFilter),
            arrayOf(
                arrayOf(Ndef::class.java.name),
                arrayOf(NdefFormatable::class.java.name),
            ),
        )
    }

    fun disableForegroundDispatch() {
        adapter?.disableForegroundDispatch(activity)
    }

    /** 从碰卡 Intent 解析门禁四要素；非本 APP 格式返回 null。 */
    fun readFromIntent(intent: Intent?): DoorConfig? {
        intent ?: return null
        val rawMessages = getNdefMessages(intent) ?: return null
        for (message in rawMessages) {
            DoorNdef.decode(message)?.let { return it }
        }
        return null
    }

    /** 从 Intent 取出待写入的 [Tag]（供写卡流程使用）。 */
    fun extractTag(intent: Intent?): Tag? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }

    /**
     * 把配置写入卡片，并回读校验四要素一致。
     * 覆盖异常分支：只读卡、容量不足、中途移开、非本 APP 数据（由调用方在写入前二次确认）。
     */
    fun writeConfig(tag: Tag, config: DoorConfig): NfcWriteResult {
        val message = DoorNdef.encode(config)
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return writeToNdef(ndef, message, config)
        }
        val formatable = NdefFormatable.get(tag)
            ?: return NfcWriteResult.Failure(NfcWriteError.NOT_NDEF, "该卡不支持 NDEF，无法写入")
        return formatAndWrite(formatable, message, config)
    }

    private fun writeToNdef(ndef: Ndef, message: NdefMessage, config: DoorConfig): NfcWriteResult {
        return try {
            ndef.connect()
            if (!ndef.isWritable) {
                return NfcWriteResult.Failure(NfcWriteError.READ_ONLY, "卡片为只读，无法写入")
            }
            val required = message.toByteArray().size
            if (ndef.maxSize < required) {
                return NfcWriteResult.Failure(
                    NfcWriteError.CAPACITY,
                    "卡容量不足（需 ${required}B，卡仅 ${ndef.maxSize}B），建议使用 NTAG215/216",
                )
            }
            ndef.writeNdefMessage(message)
            verifyReadBack(ndef, config)
        } catch (e: FormatException) {
            NfcWriteResult.Failure(NfcWriteError.IO, "写卡数据格式错误：${e.message}")
        } catch (e: Exception) {
            NfcWriteResult.Failure(NfcWriteError.IO, "写卡失败（卡可能中途移开）：${e.message}")
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun formatAndWrite(
        formatable: NdefFormatable,
        message: NdefMessage,
        config: DoorConfig,
    ): NfcWriteResult {
        return try {
            formatable.connect()
            formatable.format(message)
            // 格式化写入后用 Ndef 通道回读校验
            NfcWriteResult.Success(config)
        } catch (e: Exception) {
            NfcWriteResult.Failure(NfcWriteError.IO, "格式化写卡失败：${e.message}")
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun verifyReadBack(ndef: Ndef, expected: DoorConfig): NfcWriteResult {
        val readBack = DoorNdef.decode(ndef.ndefMessage)
        val same = readBack != null &&
            readBack.doorName == expected.doorName &&
            readBack.mac == expected.mac &&
            readBack.key == expected.key &&
            readBack.bluetoothName == expected.bluetoothName
        return if (same) {
            NfcWriteResult.Success(expected)
        } else {
            NfcWriteResult.Failure(NfcWriteError.VERIFY, "写后回读校验不一致，请重试")
        }
    }

    private fun getNdefMessages(intent: Intent): List<NdefMessage>? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        } ?: return null
        return raw.filterIsInstance<NdefMessage>()
    }
}

enum class NfcWriteError { READ_ONLY, CAPACITY, NOT_NDEF, VERIFY, IO }

sealed class NfcWriteResult {
    data class Success(val config: DoorConfig) : NfcWriteResult()
    data class Failure(val error: NfcWriteError, val message: String) : NfcWriteResult()
}
