package coredevices.pebble.battery

import coredevices.database.LocalHeartbeatDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalBatteryRepository(
    private val dao: LocalHeartbeatDao,
) {
    private val decoder = HeartbeatDecoder()

    fun records(): Flow<List<BatteryRecord>> = dao.observeAll()
        .map { rows -> rows.map { BatteryRecord(it, decoder.decode(it.payload)) } }
        .flowOn(Dispatchers.Default)

    suspend fun allRecords(): List<BatteryRecord> = withContext(Dispatchers.Default) {
        dao.getAll().map { BatteryRecord(it, decoder.decode(it.payload)) }
    }
}
