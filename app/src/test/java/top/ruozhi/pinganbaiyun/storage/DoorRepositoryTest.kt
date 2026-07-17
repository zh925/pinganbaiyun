package top.ruozhi.pinganbaiyun.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.model.DoorSnapshot
import top.ruozhi.pinganbaiyun.model.LoadResult

class DoorRepositoryTest {
    @Test fun `default remains stable across edit and clears atomically on delete`() {
        val store = MemoryStore()
        val repository = DoorRepository(store)
        val first = door("a", "AA:BB:CC:DD:EE:01")
        repository.upsert(first).getOrThrow()
        repository.setDefault(first.id).getOrThrow()
        repository.upsert(first.copy(doorName = "新名称")).getOrThrow()
        assertEquals("a", snapshot(repository).defaultId)
        assertEquals("新名称", snapshot(repository).defaultDoor?.doorName)

        repository.delete("a").getOrThrow()
        assertTrue(snapshot(repository).doors.isEmpty())
        assertNull(snapshot(repository).defaultId)
        assertNull(store.snapshot.defaultId)
    }

    @Test fun `normalized duplicate mac is rejected`() {
        val repository = DoorRepository(MemoryStore())
        repository.upsert(door("a", "AA:BB:CC:DD:EE:01")).getOrThrow()
        assertTrue(repository.upsert(door("b", "AA:BB:CC:DD:EE:01")).isFailure)
    }

    private fun snapshot(repo: DoorRepository) = (repo.load() as LoadResult.Success).snapshot
    private fun door(id: String, mac: String) = DoorConfig(id, "门-$id", mac, "0123456789ABCDEF")

    private class MemoryStore : DoorStore {
        var snapshot = DoorSnapshot()
        override fun load() = LoadResult.Success(snapshot)
        override fun save(snapshot: DoorSnapshot) = Result.success(Unit).also { this.snapshot = snapshot }
        override fun reset() = Result.success(Unit).also { snapshot = DoorSnapshot() }
    }
}
