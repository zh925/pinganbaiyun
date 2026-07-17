package top.ruozhi.pinganbaiyun.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorValidationTest {
    @Test fun `normalizes valid values`() {
        val value = DoorValidation.validate("  南门  ", "aa-bb-cc-dd-ee-ff", "abcdef0123456789").getOrThrow()
        assertEquals("南门", value.doorName)
        assertEquals("AA:BB:CC:DD:EE:FF", value.mac)
        assertEquals("ABCDEF0123456789", value.key)
    }

    @Test fun `rejects malformed secret without coercion`() {
        assertTrue(DoorValidation.validate("门", "AA:BB:CC:DD:EE:FF", "1234").isFailure)
        assertTrue(DoorValidation.validate("门", "AA:BB:CC:DD:EE:FF", "0123456789ABCDEG").isFailure)
    }
}
