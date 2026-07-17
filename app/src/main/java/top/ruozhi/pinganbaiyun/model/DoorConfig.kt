package top.ruozhi.pinganbaiyun.model

data class DoorConfig(
    val id: String,
    val doorName: String,
    val mac: String,
    val key: String,
)

data class DoorSnapshot(
    val doors: List<DoorConfig> = emptyList(),
    val defaultId: String? = null,
) {
    val defaultDoor: DoorConfig? get() = doors.firstOrNull { it.id == defaultId }
}

sealed interface LoadResult {
    data class Success(val snapshot: DoorSnapshot) : LoadResult
    data class Corrupt(val reason: String) : LoadResult
}
