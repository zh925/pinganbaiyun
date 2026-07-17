package top.ruozhi.pinganbaiyun.unlock

import top.ruozhi.pinganbaiyun.model.DoorConfig
import java.util.UUID

class UnlockCoordinator(
    private val transportFactory: () -> UnlockTransport,
    private val scheduler: TaskScheduler,
    private val stageTimeoutMillis: Long = 15_000,
) {
    private val observers = linkedSetOf<(UnlockTask?) -> Unit>()
    private var transport: UnlockTransport? = null
    private var timeout: CancelHandle? = null
    @Volatile var task: UnlockTask? = null
        private set

    @Synchronized fun observe(observer: (UnlockTask?) -> Unit): CancelHandle {
        observers += observer
        observer(task)
        return CancelHandle { synchronized(this) { observers -= observer } }
    }

    @Synchronized fun start(door: DoorConfig, origin: UnlockOrigin): Result<UnlockTask> {
        val active = task?.takeUnless { it.stage.terminal }
        if (active != null) return Result.failure(IllegalStateException("${active.door.doorName}正在进行开门操作"))
        val created = UnlockTask(UUID.randomUUID().toString(), door.copy(), origin, UnlockStage.PREPARING)
        task = created
        notifyObservers()
        val currentTransport = transportFactory()
        transport = currentTransport
        armTimeout(created.taskId)
        currentTransport.start(created.door, object : UnlockTransport.Listener {
            override fun onStage(stage: UnlockStage) = transition(created.taskId, stage, stage.label)
            override fun onSent() = finish(created.taskId, UnlockStage.SENT, UnlockStage.SENT.label)
            override fun onFailure(message: String) = finish(created.taskId, UnlockStage.FAILED, message)
        })
        return Result.success(created)
    }

    @Synchronized fun cancel() {
        val current = task ?: return
        if (current.stage.terminal) return
        finish(current.taskId, UnlockStage.CANCELLED, UnlockStage.CANCELLED.label)
    }

    @Synchronized private fun transition(taskId: String, stage: UnlockStage, detail: String) {
        val current = task ?: return
        if (current.taskId != taskId || current.stage.terminal) return
        task = current.copy(stage = stage, detail = detail)
        armTimeout(taskId)
        notifyObservers()
    }

    @Synchronized private fun finish(taskId: String, stage: UnlockStage, detail: String) {
        val current = task ?: return
        if (current.taskId != taskId || current.stage.terminal) return
        timeout?.cancel()
        timeout = null
        if (stage != UnlockStage.SENT) transport?.cancel()
        transport = null
        task = current.copy(stage = stage, detail = detail)
        notifyObservers()
    }

    private fun armTimeout(taskId: String) {
        timeout?.cancel()
        timeout = scheduler.schedule(stageTimeoutMillis) {
            synchronized(this) { finish(taskId, UnlockStage.TIMED_OUT, UnlockStage.TIMED_OUT.label) }
        }
    }

    private fun notifyObservers() {
        val current = task
        observers.toList().forEach { it(current) }
    }
}
