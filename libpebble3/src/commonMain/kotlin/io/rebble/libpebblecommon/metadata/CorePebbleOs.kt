package io.rebble.libpebblecommon.metadata

import io.rebble.libpebblecommon.services.FirmwareVersion

/**
 * Whether firmware [major].[minor] is Core's PebbleOS (4.9 and later) rather than Pebble's own
 * firmware, which ended at 4.4. Legacy watches (Aplite, Basalt, Chalk) can run Core's PebbleOS
 * through community ports; it talks to the app over BLE only, like Core's own watches.
 */
fun isCorePebbleOs(major: Int, minor: Int): Boolean = major > 4 || (major == 4 && minor >= 9)

/** Whether this is Core's PebbleOS rather than Pebble's own firmware. */
fun FirmwareVersion.isCorePebbleOs(): Boolean = isCorePebbleOs(major, minor)
