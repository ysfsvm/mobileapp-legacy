package io.rebble.libpebblecommon.metadata

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorePebbleOsTest {
    @Test
    fun pebbleFirmwareIsNotCorePebbleOs() {
        assertFalse(isCorePebbleOs(3, 12))
        assertFalse(isCorePebbleOs(4, 3))
        assertFalse(isCorePebbleOs(4, 4))
    }

    @Test
    fun coreReleasesAreCorePebbleOs() {
        assertTrue(isCorePebbleOs(4, 9))
        assertTrue(isCorePebbleOs(4, 46))
        assertTrue(isCorePebbleOs(5, 0))
    }
}
