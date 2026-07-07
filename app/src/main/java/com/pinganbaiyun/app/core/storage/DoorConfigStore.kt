package com.pinganbaiyun.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pinganbaiyun.app.data.model.DoorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 门禁配置本地持久化。四要素（含明文 key）落盘一律经 [EncryptedSharedPreferences]
 * （AES256-GCM，主密钥存于 Android Keystore）——满足 PRD「key 本地加密存储」。
 *
 * 存储结构：单个 JSON 字符串 `{ "version":1, "currentId":..., "items":[DoorConfig...] }`。
 * 对 UI 层暴露简单的 CRUD 接口；掩码/日志脱敏由 [DoorConfig.maskedKey] 保证。
 */
class DoorConfigStore private constructor(private val prefs: SharedPreferences) {

    fun list(): List<DoorConfig> = readState().items

    fun get(id: String): DoorConfig? = readState().items.firstOrNull { it.id == id }

    fun currentId(): String? = readState().currentId

    /** 新增或更新（按 id 匹配；无 id 则生成）。返回落库后的配置。 */
    fun upsert(config: DoorConfig): DoorConfig {
        val state = readState()
        val normalized = if (config.id.isBlank()) config.copy(id = generateId()) else config
        val items = state.items.toMutableList()
        val idx = items.indexOfFirst { it.id == normalized.id }
        if (idx >= 0) items[idx] = normalized else items.add(normalized)
        writeState(State(currentId = normalized.id, items = items))
        return normalized
    }

    fun delete(id: String) {
        val state = readState()
        val items = state.items.filterNot { it.id == id }
        if (items.size == state.items.size) return
        val currentId = if (state.currentId == id) items.firstOrNull()?.id else state.currentId
        writeState(State(currentId = currentId, items = items))
    }

    fun setCurrent(id: String) {
        val state = readState()
        if (state.items.none { it.id == id }) return
        writeState(state.copy(currentId = id))
    }

    // ---------- 内部 ----------

    private data class State(val currentId: String?, val items: List<DoorConfig>)

    private fun readState(): State {
        val raw = prefs.getString(KEY_STATE, null) ?: return State(null, emptyList())
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray(FIELD_ITEMS) ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    DoorConfig.normalized(
                        id = o.optString("id").ifBlank { generateId() },
                        doorName = o.optString(DoorConfig.KEY_DOOR_NAME),
                        mac = o.optString(DoorConfig.KEY_MAC),
                        key = o.optString(DoorConfig.KEY_KEY),
                        bluetoothName = o.optString(DoorConfig.KEY_BLUETOOTH_NAME),
                    )
                }.getOrNull()
            }
            val currentId = obj.optString(FIELD_CURRENT_ID).ifBlank { null }
                ?.takeIf { id -> items.any { it.id == id } } ?: items.firstOrNull()?.id
            State(currentId, items)
        }.getOrElse { State(null, emptyList()) }
    }

    private fun writeState(state: State) {
        val arr = JSONArray()
        state.items.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put(DoorConfig.KEY_DOOR_NAME, c.doorName)
                    .put(DoorConfig.KEY_MAC, c.mac)
                    .put(DoorConfig.KEY_KEY, c.key)
                    .put(DoorConfig.KEY_BLUETOOTH_NAME, c.bluetoothName),
            )
        }
        val obj = JSONObject()
            .put(FIELD_VERSION, 1)
            .put(FIELD_CURRENT_ID, state.currentId ?: JSONObject.NULL)
            .put(FIELD_ITEMS, arr)
        prefs.edit().putString(KEY_STATE, obj.toString()).apply()
    }

    private fun generateId(): String = "door_" + UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        private const val PREFS_NAME = "pinganbaiyun_secure_store"
        private const val KEY_STATE = "door_config_state"
        private const val FIELD_VERSION = "version"
        private const val FIELD_CURRENT_ID = "currentId"
        private const val FIELD_ITEMS = "items"

        @Volatile
        private var instance: DoorConfigStore? = null

        fun get(context: Context): DoorConfigStore =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): DoorConfigStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return DoorConfigStore(prefs)
        }
    }
}
