package top.ruozhi.pinganbaiyun.unlock

import top.ruozhi.pinganbaiyun.model.DoorConfig

enum class UnlockPrecondition { READY, PERMISSION_REQUIRED, BLUETOOTH_REQUIRED, UNSUPPORTED }

sealed interface UnlockFlowAction {
    data object NoAction : UnlockFlowAction
    data object NeedPermission : UnlockFlowAction
    data object NeedBluetooth : UnlockFlowAction
    data object Unsupported : UnlockFlowAction
    data class Started(val task: UnlockTask) : UnlockFlowAction
    data class Rejected(val message: String) : UnlockFlowAction
    data object MissingDoor : UnlockFlowAction
}

/** Process-scoped preflight state. Activity recreation never creates a second cold-start request. */
class UnlockFlowController(
    private val start: (DoorConfig, UnlockOrigin) -> Result<UnlockTask>,
    private val latestDoor: (String) -> DoorConfig?,
) {
    data class Pending(val door: DoorConfig, val origin: UnlockOrigin)

    private var coldStartConsumed = false
    var pending: Pending? = null
        private set

    @Synchronized fun coldStart(
        defaultDoor: DoorConfig?,
        launcherNewActivity: Boolean,
        precondition: UnlockPrecondition,
    ): UnlockFlowAction {
        if (!launcherNewActivity || coldStartConsumed) return UnlockFlowAction.NoAction
        coldStartConsumed = true
        return defaultDoor?.let { request(it, UnlockOrigin.COLD_START, precondition) } ?: UnlockFlowAction.NoAction
    }

    @Synchronized fun request(
        door: DoorConfig,
        origin: UnlockOrigin,
        precondition: UnlockPrecondition,
    ): UnlockFlowAction {
        pending?.let { return UnlockFlowAction.Rejected("${it.door.doorName}正在进行开门操作") }
        pending = Pending(door.copy(), origin)
        return continuePending(precondition)
    }

    @Synchronized fun resume(precondition: UnlockPrecondition): UnlockFlowAction =
        if (pending == null) UnlockFlowAction.NoAction else continuePending(precondition)

    @Synchronized fun retry(doorId: String, precondition: UnlockPrecondition): UnlockFlowAction {
        val latest = latestDoor(doorId) ?: return UnlockFlowAction.MissingDoor
        return request(latest, UnlockOrigin.RETRY, precondition)
    }

    @Synchronized fun cancelPending() { pending = null }

    private fun continuePending(precondition: UnlockPrecondition): UnlockFlowAction = when (precondition) {
        UnlockPrecondition.PERMISSION_REQUIRED -> UnlockFlowAction.NeedPermission
        UnlockPrecondition.BLUETOOTH_REQUIRED -> UnlockFlowAction.NeedBluetooth
        UnlockPrecondition.UNSUPPORTED -> {
            pending = null
            UnlockFlowAction.Unsupported
        }
        UnlockPrecondition.READY -> {
            val request = pending ?: return UnlockFlowAction.NoAction
            pending = null
            start(request.door, request.origin).fold(
                onSuccess = UnlockFlowAction::Started,
                onFailure = { UnlockFlowAction.Rejected(it.message ?: "无法开始开门") },
            )
        }
    }
}
