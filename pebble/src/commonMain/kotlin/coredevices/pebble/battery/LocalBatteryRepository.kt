package coredevices.pebble.battery

import coredevices.database.LocalHeartbeatDao
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.getTempFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.time.Clock

class LocalBatteryRepository(
    private val dao: LocalHeartbeatDao,
    private val appContext: AppContext,
    private val clock: Clock,
) {
    private val decoder = HeartbeatDecoder()

    fun records(): Flow<List<BatteryRecord>> = dao.observeAll()
        .map { rows -> rows.map { BatteryRecord(it, decoder.decode(it.payload)) } }
        .flowOn(Dispatchers.Default)

    /** Writes every kept record to a JSON file to share, and returns its path. */
    suspend fun exportToFile(): Path = withContext(Dispatchers.IO) {
        val json = batteryExportJson(dao.getAll().map { BatteryRecord(it, decoder.decode(it.payload)) })
        val path = getTempFilePath(appContext, "battery-records-${clock.now().epochSeconds}.json", "exports")
        SystemFileSystem.sink(path).buffered().use { it.writeString(json) }
        path
    }
}
