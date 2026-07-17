package top.ruozhi.pinganbaiyun.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.model.DoorSnapshot
import top.ruozhi.pinganbaiyun.model.LoadResult
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedDoorStore(context: Context) : DoorStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): LoadResult {
        val encoded = prefs.getString(PAYLOAD, null) ?: return LoadResult.Success(DoorSnapshot())
        return try {
            val envelope = JSONObject(encoded)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(envelope.getString("iv"))))
            val json = JSONObject(String(cipher.doFinal(decode(envelope.getString("data"))), StandardCharsets.UTF_8))
            val array = json.getJSONArray("doors")
            val doors = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(DoorConfig(item.getString("id"), item.getString("doorName"), item.getString("mac"), item.getString("key")))
                }
            }
            val rawDefault = json.optString("defaultId").takeIf { it.isNotBlank() }
            val safeDefault = rawDefault?.takeIf { id -> doors.any { it.id == id } }
            val snapshot = DoorSnapshot(doors, safeDefault)
            if (safeDefault != rawDefault) save(snapshot)
            LoadResult.Success(snapshot)
        } catch (_: Exception) {
            LoadResult.Corrupt("本地加密配置无法读取；原数据尚未覆盖")
        }
    }

    override fun save(snapshot: DoorSnapshot): Result<Unit> = runCatching {
        val json = JSONObject().apply {
            put("version", 1)
            put("defaultId", snapshot.defaultId ?: JSONObject.NULL)
            put("doors", JSONArray().apply {
                snapshot.doors.forEach { door ->
                    put(JSONObject().apply {
                        put("id", door.id)
                        put("doorName", door.doorName)
                        put("mac", door.mac)
                        put("key", door.key)
                    })
                }
            })
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val envelope = JSONObject().apply {
            put("iv", encode(cipher.iv))
            put("data", encode(cipher.doFinal(json.toString().toByteArray(StandardCharsets.UTF_8))))
        }
        check(prefs.edit().putString(PAYLOAD, envelope.toString()).commit()) { "配置写入失败" }
    }

    override fun reset(): Result<Unit> = runCatching {
        check(prefs.edit().remove(PAYLOAD).commit()) { "配置重置失败" }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val PREFS = "encrypted_doors_v1"
        private const val PAYLOAD = "payload"
        private const val KEY_ALIAS = "pinganbaiyun-door-store-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
