package top.ruozhi.pinganbaiyun.storage

import org.json.JSONArray
import org.json.JSONObject
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.model.DoorSnapshot
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Testable authenticated envelope used by the Android Keystore-backed store. */
class EncryptedSnapshotCodec(private val key: SecretKey) {
    data class Decoded(val snapshot: DoorSnapshot, val clearedDanglingDefault: Boolean)

    fun encode(snapshot: DoorSnapshot): String {
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
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return JSONObject().apply {
            put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            put("data", Base64.getEncoder().encodeToString(cipher.doFinal(json.toString().toByteArray(StandardCharsets.UTF_8))))
        }.toString()
    }

    fun decode(envelope: String): DoorSnapshot = decodeResult(envelope).snapshot

    fun decodeResult(envelope: String): Decoded {
        val encoded = JSONObject(envelope)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, Base64.getDecoder().decode(encoded.getString("iv"))),
        )
        val json = JSONObject(String(
            cipher.doFinal(Base64.getDecoder().decode(encoded.getString("data"))),
            StandardCharsets.UTF_8,
        ))
        require(json.getInt("version") == 1) { "Unsupported storage version" }
        val array = json.getJSONArray("doors")
        val doors = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(DoorConfig(item.getString("id"), item.getString("doorName"), item.getString("mac"), item.getString("key")))
            }
        }
        val requestedDefault = if (json.isNull("defaultId")) null else json.getString("defaultId")
        val safeDefault = requestedDefault?.takeIf { id -> doors.any { it.id == id } }
        return Decoded(DoorSnapshot(doors, safeDefault), requestedDefault != null && safeDefault == null)
    }

    companion object { private const val TRANSFORMATION = "AES/GCM/NoPadding" }
}

interface EncryptedPayload {
    fun read(): String?
    fun write(value: String): Boolean
    fun remove(): Boolean
}

/** Platform-independent store core; authentication failures never mutate the source payload. */
class SecureDoorStore(
    private val payload: EncryptedPayload,
    private val keyProvider: () -> SecretKey,
) : DoorStore {
    override fun load(): top.ruozhi.pinganbaiyun.model.LoadResult {
        val encoded = payload.read() ?: return top.ruozhi.pinganbaiyun.model.LoadResult.Success(DoorSnapshot())
        return try {
            val decoded = EncryptedSnapshotCodec(keyProvider()).decodeResult(encoded)
            if (decoded.clearedDanglingDefault && !payload.write(EncryptedSnapshotCodec(keyProvider()).encode(decoded.snapshot))) {
                throw IllegalStateException("Unable to persist sanitized default")
            }
            top.ruozhi.pinganbaiyun.model.LoadResult.Success(decoded.snapshot)
        } catch (_: Exception) {
            top.ruozhi.pinganbaiyun.model.LoadResult.Corrupt("本地加密配置无法读取；原数据尚未覆盖")
        }
    }

    override fun save(snapshot: DoorSnapshot): Result<Unit> = runCatching {
        check(payload.write(EncryptedSnapshotCodec(keyProvider()).encode(snapshot))) { "配置写入失败" }
    }

    override fun reset(): Result<Unit> = runCatching {
        check(payload.remove()) { "配置重置失败" }
    }
}
