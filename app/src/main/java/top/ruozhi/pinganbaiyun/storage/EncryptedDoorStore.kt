package top.ruozhi.pinganbaiyun.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import top.ruozhi.pinganbaiyun.model.DoorSnapshot
import top.ruozhi.pinganbaiyun.model.LoadResult
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class EncryptedDoorStore(context: Context) : DoorStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val delegate = SecureDoorStore(
        payload = object : EncryptedPayload {
            override fun read(): String? = prefs.getString(PAYLOAD, null)
            override fun write(value: String): Boolean = prefs.edit().putString(PAYLOAD, value).commit()
            override fun remove(): Boolean = prefs.edit().remove(PAYLOAD).commit()
        },
        keyProvider = ::key,
    )

    override fun load(): LoadResult = delegate.load()
    override fun save(snapshot: DoorSnapshot): Result<Unit> = delegate.save(snapshot)
    override fun reset(): Result<Unit> = delegate.reset()

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

    companion object {
        private const val PREFS = "encrypted_doors_v1"
        private const val PAYLOAD = "payload"
        private const val KEY_ALIAS = "pinganbaiyun-door-store-v1"
    }
}
