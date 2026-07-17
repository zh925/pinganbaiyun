package top.ruozhi.pinganbaiyun.ui

import top.ruozhi.pinganbaiyun.unlock.UnlockStage
import top.ruozhi.pinganbaiyun.unlock.UnlockTask

enum class UiScene {
    LOADING,
    DOOR_LIST,
    EMPTY,
    PROGRESS,
    SENT,
    DEVICE_UNREACHABLE,
    TIMED_OUT,
    PROTOCOL_FAILED,
    CANCELLED,
    PERMISSION_REQUIRED,
    BLUETOOTH_DISABLED,
    DATA_CORRUPT,
}

object UiStateMapper {
    fun sceneFor(task: UnlockTask?): UiScene = when (task?.stage) {
        null -> UiScene.DOOR_LIST
        UnlockStage.SENT -> UiScene.SENT
        UnlockStage.TIMED_OUT -> UiScene.TIMED_OUT
        UnlockStage.CANCELLED -> UiScene.CANCELLED
        UnlockStage.FAILED -> failureScene(task.detail)
        else -> UiScene.PROGRESS
    }

    fun progressStep(stage: UnlockStage): Int = when (stage) {
        UnlockStage.PREPARING,
        UnlockStage.CONNECTING,
        UnlockStage.DISCOVERING_SERVICES,
        UnlockStage.PREPARING_CHANNEL -> 0
        UnlockStage.READING_SEED -> 1
        UnlockStage.SENDING_COMMAND -> 2
        UnlockStage.CONFIRMING_WRITE -> 3
        else -> 3
    }

    fun failureScene(detail: String): UiScene {
        val protocolMarkers = listOf("协议", "服务", "特征", "描述符", "通道", "随机数", "seed", "帧", "写入")
        return if (protocolMarkers.any { detail.contains(it, ignoreCase = true) }) {
            UiScene.PROTOCOL_FAILED
        } else {
            UiScene.DEVICE_UNREACHABLE
        }
    }
}
