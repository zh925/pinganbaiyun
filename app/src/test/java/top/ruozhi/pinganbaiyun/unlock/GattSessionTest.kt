package top.ruozhi.pinganbaiyun.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ruozhi.pinganbaiyun.model.DoorConfig

class GattSessionTest {
    @Test fun `runs deterministic connect discover descriptor read write sequence`() {
        val fixture = Fixture()
        fixture.start()
        fixture.driveSuccess()

        assertEquals(listOf(
            "connect",
            "discover",
            "enable:notify", "descriptor:notify:false",
            "enable:indicate", "descriptor:indicate:true",
            "read:read",
            "write:write:20",
            "close",
        ), fixture.client.events)
        assertEquals(listOf(
            UnlockStage.CONNECTING,
            UnlockStage.DISCOVERING_SERVICES,
            UnlockStage.PREPARING_CHANNEL,
            UnlockStage.READING_SEED,
            UnlockStage.SENDING_COMMAND,
        ), fixture.listener.stages)
        assertEquals(1, fixture.listener.sent)
        assertTrue(fixture.listener.failures.isEmpty())
    }

    @Test fun `every synchronous API not-started result fails closed and releases once`() {
        listOf("connect", "discover", "enable:notify", "descriptor:notify", "enable:indicate", "descriptor:indicate", "read", "write")
            .forEach { operation ->
                val fixture = Fixture(operation)
                fixture.start()
                fixture.driveSuccess()
                assertEquals("$operation must fail", 1, fixture.listener.failures.size)
                assertEquals("$operation must close once", 1, fixture.client.events.count { it == "close" })
                assertEquals("$operation must not send", 0, fixture.listener.sent)
            }
    }

    @Test fun `every asynchronous non-success status fails closed and ignores late callbacks`() {
        val failures = listOf("connect-status", "discover-status", "descriptor-status", "read-status", "write-status")
        failures.forEach { failure ->
            val fixture = Fixture()
            fixture.start()
            fixture.driveWithStatusFailure(failure)
            fixture.client.callback.onCommandWritten(true)
            assertEquals("$failure must fail", 1, fixture.listener.failures.size)
            assertEquals("$failure must close once", 1, fixture.client.events.count { it == "close" })
            assertEquals("$failure late success ignored", 0, fixture.listener.sent)
        }
    }

    @Test fun `missing service characteristic or descriptor fails before unknown operation`() {
        listOf(
            ServiceCase(false, channels, "service"),
            ServiceCase(true, channels.filterNot { GattFeature.WRITE in it.features }, "write"),
            ServiceCase(true, channels.map { if (it.id == "notify") it.copy(hasClientConfiguration = false) else it }, "descriptor"),
        ).forEach { case ->
            val fixture = Fixture()
            fixture.start()
            fixture.client.callback.onConnected(true)
            fixture.client.callback.onServicesDiscovered(true, case.found, case.items)
            assertEquals(case.label, 1, fixture.listener.failures.size)
            assertEquals(case.label, 1, fixture.client.events.count { it == "close" })
        }
    }

    @Test fun `invalid seed fails without writing a partial command`() {
        val fixture = Fixture()
        fixture.start()
        fixture.client.callback.onConnected(true)
        fixture.client.callback.onServicesDiscovered(true, true, channels)
        fixture.client.callback.onDescriptorWritten(true)
        fixture.client.callback.onDescriptorWritten(true)
        fixture.client.callback.onSeedRead(true, byteArrayOf())
        assertEquals(1, fixture.listener.failures.size)
        assertTrue(fixture.client.events.none { it.startsWith("write:") })
        assertEquals(1, fixture.client.events.count { it == "close" })
    }

    @Test fun `cancellation at every GATT stage closes once and ignores late success`() {
        (0..4).forEach { callbacksBeforeCancel ->
            val fixture = Fixture()
            fixture.start()
            if (callbacksBeforeCancel >= 1) fixture.client.callback.onConnected(true)
            if (callbacksBeforeCancel >= 2) fixture.client.callback.onServicesDiscovered(true, true, channels)
            if (callbacksBeforeCancel >= 3) {
                fixture.client.callback.onDescriptorWritten(true)
                fixture.client.callback.onDescriptorWritten(true)
            }
            if (callbacksBeforeCancel >= 4) fixture.client.callback.onSeedRead(true, byteArrayOf(1, 2, 3, 4, 5, 6))
            fixture.cancel()
            fixture.client.callback.onCommandWritten(true)
            assertEquals("stage $callbacksBeforeCancel", 1, fixture.client.events.count { it == "close" })
            assertEquals("stage $callbacksBeforeCancel", 0, fixture.listener.sent)
        }
    }

    private data class ServiceCase(val found: Boolean, val items: List<GattChannel>, val label: String)

    private class Fixture(failureAt: String? = null) {
        val client = FakeGattClient(failureAt)
        val listener = RecordingListener()
        private val session = GattSession(client)

        fun start() = session.start(door, listener)
        fun cancel() = session.cancel()

        fun driveSuccess() {
            client.callback.onConnected(true)
            client.callback.onServicesDiscovered(true, true, channels)
            client.callback.onDescriptorWritten(true)
            client.callback.onDescriptorWritten(true)
            client.callback.onSeedRead(true, byteArrayOf(1, 2, 3, 4, 5, 6))
            client.callback.onCommandWritten(true)
        }

        fun driveWithStatusFailure(failure: String) {
            if (failure == "connect-status") return client.callback.onConnected(false)
            client.callback.onConnected(true)
            if (failure == "discover-status") return client.callback.onServicesDiscovered(false, false, emptyList())
            client.callback.onServicesDiscovered(true, true, channels)
            if (failure == "descriptor-status") return client.callback.onDescriptorWritten(false)
            client.callback.onDescriptorWritten(true)
            client.callback.onDescriptorWritten(true)
            if (failure == "read-status") return client.callback.onSeedRead(false, byteArrayOf())
            client.callback.onSeedRead(true, byteArrayOf(1, 2, 3, 4, 5, 6))
            client.callback.onCommandWritten(failure != "write-status")
        }
    }

    private class FakeGattClient(private val failureAt: String?) : GattClient {
        lateinit var callback: GattClient.Callback
        val events = mutableListOf<String>()
        override fun connect(mac: String, callback: GattClient.Callback): Boolean {
            this.callback = callback; events += "connect"; return failureAt != "connect"
        }
        override fun discoverServices() = record("discover")
        override fun enableNotifications(channel: GattChannel) = record("enable:${channel.id}")
        override fun writeClientConfiguration(channel: GattChannel, indicate: Boolean) =
            record("descriptor:${channel.id}:$indicate", "descriptor:${channel.id}")
        override fun read(channel: GattChannel) = record("read:${channel.id}", "read")
        override fun write(channel: GattChannel, value: ByteArray) = record("write:${channel.id}:${value.size}", "write")
        override fun close() { events += "close" }
        private fun record(event: String, failureKey: String = event): Boolean {
            events += event
            return failureAt != failureKey
        }
    }

    private class RecordingListener : UnlockTransport.Listener {
        val stages = mutableListOf<UnlockStage>()
        val failures = mutableListOf<String>()
        var sent = 0
        override fun onStage(stage: UnlockStage) { stages += stage }
        override fun onSent() { sent++ }
        override fun onFailure(message: String) { failures += message }
    }

    companion object {
        private val door = DoorConfig("door", "测试门", "AA:BB:CC:DD:EE:01", "0123456789ABCDEF")
        private val channels = listOf(
            GattChannel("write", setOf(GattFeature.WRITE), true),
            GattChannel("indicate", setOf(GattFeature.INDICATE), true),
            GattChannel("read", setOf(GattFeature.READ), true),
            GattChannel("notify", setOf(GattFeature.NOTIFY), true),
        )
    }
}
