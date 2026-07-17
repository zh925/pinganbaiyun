package top.ruozhi.pinganbaiyun.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ruozhi.pinganbaiyun.model.DoorConfig

class UnlockCoordinatorTest {
    @Test fun `global lock rejects a concurrent task`() {
        val transport = FakeTransport()
        val coordinator = UnlockCoordinator({ transport }, FakeScheduler())
        coordinator.start(door("a"), UnlockOrigin.MANUAL).getOrThrow()
        assertTrue(coordinator.start(door("b"), UnlockOrigin.COLD_START).isFailure)
        assertEquals("a", coordinator.task?.door?.id)
    }

    @Test fun `cancel wins over a late success callback`() {
        val transport = FakeTransport()
        val coordinator = UnlockCoordinator({ transport }, FakeScheduler())
        coordinator.start(door("a"), UnlockOrigin.MANUAL)
        coordinator.cancel()
        transport.listener.onSent()
        assertEquals(UnlockStage.CANCELLED, coordinator.task?.stage)
        assertEquals(1, transport.cancelCount)
    }

    @Test fun `each stage has a finite timeout and releases transport`() {
        listOf(
            UnlockStage.CONNECTING,
            UnlockStage.DISCOVERING_SERVICES,
            UnlockStage.PREPARING_CHANNEL,
            UnlockStage.READING_SEED,
            UnlockStage.SENDING_COMMAND,
        ).forEach { stage ->
            val transport = FakeTransport()
            val scheduler = FakeScheduler()
            val coordinator = UnlockCoordinator({ transport }, scheduler, stageTimeoutMillis = 1)
            coordinator.start(door("a"), UnlockOrigin.MANUAL)
            transport.listener.onStage(stage)
            assertTrue("$stage must reset timeout", scheduler.scheduledCount >= 2)
            scheduler.runLatest()
            assertEquals(stage.name, UnlockStage.TIMED_OUT, coordinator.task?.stage)
            assertEquals(stage.name, 1, transport.cancelCount)
        }
    }

    @Test fun `write success uses neutral sent state`() {
        val transport = FakeTransport()
        val coordinator = UnlockCoordinator({ transport }, FakeScheduler())
        coordinator.start(door("a"), UnlockOrigin.MANUAL)
        transport.listener.onSent()
        assertEquals(UnlockStage.SENT, coordinator.task?.stage)
        assertEquals("开门指令已发送，请确认门禁状态", coordinator.task?.detail)
    }

    private fun door(id: String) = DoorConfig(id, "门-$id", "AA:BB:CC:DD:EE:01", "0123456789ABCDEF")

    private class FakeTransport : UnlockTransport {
        lateinit var listener: UnlockTransport.Listener
        var cancelCount = 0
        override fun start(door: DoorConfig, listener: UnlockTransport.Listener) { this.listener = listener }
        override fun cancel() { cancelCount++ }
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(var cancelled: Boolean, val block: () -> Unit)
        private val entries = mutableListOf<Entry>()
        val scheduledCount get() = entries.size
        override fun schedule(delayMillis: Long, block: () -> Unit): CancelHandle {
            val entry = Entry(false, block)
            entries += entry
            return CancelHandle { entry.cancelled = true }
        }
        fun runLatest() { entries.last { !it.cancelled }.block() }
    }
}
