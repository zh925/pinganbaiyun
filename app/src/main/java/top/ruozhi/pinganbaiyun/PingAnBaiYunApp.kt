package top.ruozhi.pinganbaiyun

import android.app.Application
import android.os.Handler
import android.os.Looper
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.storage.DoorRepository
import top.ruozhi.pinganbaiyun.storage.EncryptedDoorStore
import top.ruozhi.pinganbaiyun.unlock.AndroidGattTransport
import top.ruozhi.pinganbaiyun.unlock.CancelHandle
import top.ruozhi.pinganbaiyun.unlock.TaskScheduler
import top.ruozhi.pinganbaiyun.unlock.UnlockCoordinator
import top.ruozhi.pinganbaiyun.unlock.UnlockOrigin
import java.util.concurrent.atomic.AtomicBoolean

class PingAnBaiYunApp : Application() {
    lateinit var repository: DoorRepository
        private set
    lateinit var coordinator: UnlockCoordinator
        private set

    private val coldStartConsumed = AtomicBoolean(false)
    @Volatile var pendingUnlock: PendingUnlock? = null

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
    }

    fun consumeColdStart(): Boolean = coldStartConsumed.compareAndSet(false, true)

    data class PendingUnlock(val door: DoorConfig, val origin: UnlockOrigin)
}
