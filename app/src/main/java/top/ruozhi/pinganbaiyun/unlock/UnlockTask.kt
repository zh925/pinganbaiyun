package top.ruozhi.pinganbaiyun.unlock

import top.ruozhi.pinganbaiyun.model.DoorConfig

enum class UnlockOrigin { MANUAL, COLD_START, RETRY }

enum class UnlockStage(val label: String, val terminal: Boolean = false) {
    PREPARING("准备连接"),
    CONNECTING("连接设备"),
    DISCOVERING_SERVICES("发现服务"),
    PREPARING_CHANNEL("准备通信通道"),
    READING_SEED("读取设备随机数"),
    SENDING_COMMAND("发送开门指令"),
    CONFIRMING_WRITE("确认指令写入"),
    SENT("开门指令已发送，请确认门禁状态", true),
    FAILED("开门失败", true),
    CANCELLED("已取消", true),
    TIMED_OUT("操作超时", true),
}

data class UnlockTask(
    val taskId: String,
    val door: DoorConfig,
    val origin: UnlockOrigin,
    val stage: UnlockStage,
    val detail: String = stage.label,
)

interface UnlockTransport {
    interface Listener {
        fun onStage(stage: UnlockStage)
        fun onSent()
        fun onFailure(message: String)
    }

    fun start(door: DoorConfig, listener: Listener)
    fun cancel()
}

fun interface CancelHandle { fun cancel() }

fun interface TaskScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): CancelHandle
}
