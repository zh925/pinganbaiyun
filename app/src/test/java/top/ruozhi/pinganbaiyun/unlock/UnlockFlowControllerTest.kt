package top.ruozhi.pinganbaiyun.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ruozhi.pinganbaiyun.model.DoorConfig

class UnlockFlowControllerTest {
    @Test fun `launcher cold-start token is consumed once across activity recreation`() {
        val fixture = Fixture()
        assertTrue(fixture.flow.coldStart(door("old"), true, UnlockPrecondition.READY) is UnlockFlowAction.Started)
        assertEquals(UnlockFlowAction.NoAction, fixture.flow.coldStart(door("old"), true, UnlockPrecondition.READY))
        assertEquals(UnlockFlowAction.NoAction, fixture.flow.coldStart(door("old"), false, UnlockPrecondition.READY))
        assertEquals(1, fixture.started.size)
    }

    @Test fun `permission and bluetooth returns continue one pending snapshot after recreation`() {
        val fixture = Fixture()
        val original = door("snapshot")
        assertEquals(UnlockFlowAction.NeedPermission, fixture.flow.coldStart(original, true, UnlockPrecondition.PERMISSION_REQUIRED))
        assertEquals(UnlockFlowAction.NeedBluetooth, fixture.flow.resume(UnlockPrecondition.BLUETOOTH_REQUIRED))
        // A recreated Activity calls coldStart with launcherNewActivity=false, then resumes the process-scoped pending request.
        assertEquals(UnlockFlowAction.NoAction, fixture.flow.coldStart(original, false, UnlockPrecondition.READY))
        assertTrue(fixture.flow.resume(UnlockPrecondition.READY) is UnlockFlowAction.Started)
        assertEquals(listOf(original), fixture.started.map { it.first })
    }

    @Test fun `explicit retry reads latest saved snapshot and deleted door cannot retry`() {
        val fixture = Fixture()
        fixture.latest = door("latest")
        assertTrue(fixture.flow.retry("door", UnlockPrecondition.READY) is UnlockFlowAction.Started)
        assertEquals("latest", fixture.started.single().first.doorName)

        fixture.latest = null
        assertEquals(UnlockFlowAction.MissingDoor, fixture.flow.retry("door", UnlockPrecondition.READY))
    }

    @Test fun `denied preflight can be cancelled without starting transport`() {
        val fixture = Fixture()
        assertEquals(UnlockFlowAction.NeedPermission, fixture.flow.request(door("pending"), UnlockOrigin.MANUAL, UnlockPrecondition.PERMISSION_REQUIRED))
        fixture.flow.cancelPending()
        assertEquals(UnlockFlowAction.NoAction, fixture.flow.resume(UnlockPrecondition.READY))
        assertTrue(fixture.started.isEmpty())
    }

    private class Fixture {
        val started = mutableListOf<Pair<DoorConfig, UnlockOrigin>>()
        var latest: DoorConfig? = null
        val flow = UnlockFlowController(
            start = { door, origin ->
                started += door to origin
                Result.success(UnlockTask("task-${started.size}", door, origin, UnlockStage.PREPARING))
            },
            latestDoor = { latest },
        )
    }

    companion object {
        private fun door(name: String) = DoorConfig("door", name, "AA:BB:CC:DD:EE:01", "0123456789ABCDEF")
    }
}
