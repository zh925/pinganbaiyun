package top.ruozhi.pinganbaiyun.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.unlock.UnlockOrigin
import top.ruozhi.pinganbaiyun.unlock.UnlockStage
import top.ruozhi.pinganbaiyun.unlock.UnlockTask

class UiStateMapperTest {
    private val door = DoorConfig("1", "北门", "A4:C1:38:2F:7B:10", "8F20D44C19B7A631")

    @Test fun `maps every unlock result to an explicit visual scene`() {
        assertEquals(UiScene.DOOR_LIST, UiStateMapper.sceneFor(null))
        assertEquals(UiScene.PROGRESS, UiStateMapper.sceneFor(task(UnlockStage.CONNECTING)))
        assertEquals(UiScene.SENT, UiStateMapper.sceneFor(task(UnlockStage.SENT)))
        assertEquals(UiScene.TIMED_OUT, UiStateMapper.sceneFor(task(UnlockStage.TIMED_OUT)))
        assertEquals(UiScene.CANCELLED, UiStateMapper.sceneFor(task(UnlockStage.CANCELLED)))
        assertEquals(UiScene.DEVICE_UNREACHABLE, UiStateMapper.sceneFor(task(UnlockStage.FAILED, "门禁设备连接失败")))
        assertEquals(UiScene.PROTOCOL_FAILED, UiStateMapper.sceneFor(task(UnlockStage.FAILED, "门禁协议特征不完整")))
        assertEquals(UiScene.PROTOCOL_FAILED, UiStateMapper.sceneFor(task(UnlockStage.FAILED, "开门指令写入失败")))
    }

    @Test fun `maps transport stages to the four prototype progress rows`() {
        val expected = mapOf(
            UnlockStage.PREPARING to 0,
            UnlockStage.CONNECTING to 0,
            UnlockStage.DISCOVERING_SERVICES to 0,
            UnlockStage.PREPARING_CHANNEL to 0,
            UnlockStage.READING_SEED to 1,
            UnlockStage.SENDING_COMMAND to 2,
            UnlockStage.CONFIRMING_WRITE to 3,
        )
        expected.forEach { (stage, index) -> assertEquals(stage.name, index, UiStateMapper.progressStep(stage)) }
    }

    @Test fun `defines every non-transport scene required by the prototype`() {
        val required = setOf(
            UiScene.LOADING,
            UiScene.EMPTY,
            UiScene.PERMISSION_REQUIRED,
            UiScene.BLUETOOTH_DISABLED,
            UiScene.DATA_CORRUPT,
        )
        assertEquals(5, required.size)
    }

    private fun task(stage: UnlockStage, detail: String = stage.label) =
        UnlockTask("task", door, UnlockOrigin.MANUAL, stage, detail)
}
