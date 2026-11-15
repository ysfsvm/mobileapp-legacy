package coredevices.pebble.battery

import CommonApiConfig
import coredevices.database.LocalHeartbeatDao
import coredevices.database.LocalHeartbeatEntity
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Without an analytics server, heartbeats stay on the phone for the local Battery Life page. */
fun CommonApiConfig.keepsHeartbeatsLocally(): Boolean = bugUrl.isNullOrBlank()

class LocalHeartbeatStore(
    private val dao: LocalHeartbeatDao,
    private val clock: Clock,
) {
    suspend fun store(serial: String, fwVersion: String?, tzOffsetMinutes: Int, payload: ByteArray) {
        val now = clock.now()
        dao.insert(
            LocalHeartbeatEntity(
                serial = serial,
                fwVersion = fwVersion,
                tzOffsetMinutes = tzOffsetMinutes,
                receivedAt = now.toEpochMilliseconds(),
                payload = payload,
            )
        )
        dao.deleteReceivedBefore((now - RETENTION).toEpochMilliseconds())
    }

    companion object {
        val RETENTION = 180.days
    }
}
