package top.ruozhi.pinganbaiyun

import android.app.Application
import android.os.Handler
import android.os.Looper
import top.ruozhi.pinganbaiyun.storage.DoorRepository
import top.ruozhi.pinganbaiyun.storage.EncryptedDoorStore
import top.ruozhi.pinganbaiyun.unlock.AndroidGattTransport
import top.ruozhi.pinganbaiyun.unlock.CancelHandle
import top.ruozhi.pinganbaiyun.unlock.TaskScheduler
import top.ruozhi.pinganbaiyun.unlock.UnlockCoordinator
import top.ruozhi.pinganbaiyun.unlock.UnlockFlowController

class PingAnBaiYunApp : Application() {
    lateinit var repository: DoorRepository
        private set
    lateinit var coordinator: UnlockCoordinator
        private set
    lateinit var unlockFlow: UnlockFlowController
        private set

    override fun onCreate() {
        super.onCreate()
        repository = DoorRepository(EncryptedDoorStore(this))
        val handler = Handler(Looper.getMainLooper())
        coordinator = UnlockCoordinator(
            transportFactory = { AndroidGattTransport(this) },
            scheduler = TaskScheduler { delay, block ->
                val runnable = Runnable(block)
                handler.postDelayed(runnable, delay)
                CancelHandle { handler.removeCallbacks(runnable) }
            },
        )
        unlockFlow = UnlockFlowController(
            start = coordinator::start,
            latestDoor = { id ->
                (repository.load() as? top.ruozhi.pinganbaiyun.model.LoadResult.Success)
                    ?.snapshot?.doors?.firstOrNull { it.id == id }
            },
        )
    }
}
