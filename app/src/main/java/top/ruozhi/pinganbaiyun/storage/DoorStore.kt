package top.ruozhi.pinganbaiyun.storage

import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.model.DoorSnapshot
import top.ruozhi.pinganbaiyun.model.LoadResult

interface DoorStore {
    fun load(): LoadResult
    fun save(snapshot: DoorSnapshot): Result<Unit>
    fun reset(): Result<Unit>
}

class DoorRepository(private val store: DoorStore) {
    @Volatile private var cached: LoadResult = store.load()

    @Synchronized fun load(): LoadResult = cached

    @Synchronized fun upsert(door: DoorConfig): Result<DoorSnapshot> = mutate { current ->
        val duplicate = current.doors.firstOrNull { it.mac == door.mac && it.id != door.id }
        require(duplicate == null) { "该 MAC 已保存为“${duplicate?.doorName}”" }
        val next = current.doors.filterNot { it.id == door.id } + door
        current.copy(doors = next)
    }

    @Synchronized fun delete(id: String): Result<DoorSnapshot> = mutate { current ->
        current.copy(
            doors = current.doors.filterNot { it.id == id },
            defaultId = current.defaultId.takeUnless { it == id },
        )
    }

    @Synchronized fun setDefault(id: String?): Result<DoorSnapshot> = mutate { current ->
        require(id == null || current.doors.any { it.id == id }) { "门禁不存在" }
        current.copy(defaultId = id)
    }

    @Synchronized fun reset(): Result<DoorSnapshot> = store.reset().map {
        DoorSnapshot().also { cached = LoadResult.Success(it) }
    }

    private fun mutate(block: (DoorSnapshot) -> DoorSnapshot): Result<DoorSnapshot> {
        val current = (cached as? LoadResult.Success)?.snapshot
            ?: return Result.failure(IllegalStateException("本地配置不可读取，请先安全重置"))
        return runCatching { block(current) }.fold(
            onSuccess = { next ->
                store.save(next).map {
                    cached = LoadResult.Success(next)
                    next
                }
            },
            onFailure = { Result.failure(it) },
        )
    }
}
