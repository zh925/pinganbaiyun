package top.ruozhi.pinganbaiyun.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.model.DoorSnapshot
import top.ruozhi.pinganbaiyun.model.LoadResult
import javax.crypto.spec.SecretKeySpec

class EncryptedSnapshotCodecTest {
    @Test fun `round trips encrypted snapshot without plaintext secret in envelope`() {
        val codec = EncryptedSnapshotCodec(key(1))
        val snapshot = DoorSnapshot(listOf(door), door.id)
        val envelope = codec.encode(snapshot)
        assertEquals(snapshot, codec.decode(envelope))
        assertEquals(false, envelope.contains(door.key))
    }

    @Test fun `wrong keystore key and damaged ciphertext fail without replacement snapshot`() {
        val memory = MemoryPayload(EncryptedSnapshotCodec(key(1)).encode(DoorSnapshot(listOf(door), door.id)))
        val original = memory.value
        assertTrue(SecureDoorStore(memory) { key(2) }.load() is LoadResult.Corrupt)
        assertEquals(original, memory.value)

        memory.value = original?.dropLast(5)
        val damaged = memory.value
        assertTrue(SecureDoorStore(memory) { key(1) }.load() is LoadResult.Corrupt)
        assertEquals(damaged, memory.value)
    }

    @Test fun `dangling default reference is cleared without selecting first door`() {
        val codec = EncryptedSnapshotCodec(key(1))
        val memory = MemoryPayload(codec.encode(DoorSnapshot(listOf(door), "missing")))
        val loaded = SecureDoorStore(memory) { key(1) }.load() as LoadResult.Success
        val decoded = loaded.snapshot
        assertNull(decoded.defaultId)
        assertEquals(listOf(door), decoded.doors)
        assertNull(codec.decode(memory.value!!).defaultId)
    }

    private fun key(value: Byte) = SecretKeySpec(ByteArray(16) { value }, "AES")

    private class MemoryPayload(var value: String?) : EncryptedPayload {
        override fun read() = value
        override fun write(value: String): Boolean { this.value = value; return true }
        override fun remove(): Boolean { value = null; return true }
    }

    companion object {
        private val door = DoorConfig("door", "测试门", "AA:BB:CC:DD:EE:01", "0123456789ABCDEF")
    }
}
