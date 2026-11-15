package coredevices.pebble.services

import CommonApiConfig
import CoreAppVersion
import com.russhwolf.settings.MapSettings
import coredevices.database.AnalyticsHeartbeatDao
import coredevices.database.AnalyticsHeartbeatEntity
import coredevices.database.LocalHeartbeatDao
import coredevices.database.LocalHeartbeatEntity
import coredevices.pebble.Platform
import coredevices.pebble.battery.LocalHeartbeatStore
import coredevices.pebble.ui.SettingsKeys.KEY_ENABLE_MEMFAULT_UPLOADS
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AnalyticsHeartbeatQueueTest {
    private class FakeUploadDao : AnalyticsHeartbeatDao {
        val rows = mutableListOf<AnalyticsHeartbeatEntity>()
        var inserted = 0
        override suspend fun insert(row: AnalyticsHeartbeatEntity): Long {
            inserted++
            rows += row.copy(id = inserted.toLong())
            return inserted.toLong()
        }
        override suspend fun getBatch(limit: Int) = rows.take(limit)
        override suspend fun deleteByIds(ids: List<Long>) { rows.removeAll { it.id in ids } }
        override suspend fun count() = rows.size.toLong()
        override suspend fun deleteOldest(count: Long) = repeat(count.toInt()) { rows.removeAt(0) }
    }

    private class FakeLocalDao : LocalHeartbeatDao {
        val rows = MutableStateFlow<List<LocalHeartbeatEntity>>(emptyList())
        override suspend fun insert(row: LocalHeartbeatEntity): Long {
            rows.value += row
            return rows.value.size.toLong()
        }
        override suspend fun deleteReceivedBefore(cutoff: Long) {
            rows.value = rows.value.filter { it.receivedAt >= cutoff }
        }
        override fun observeAll(): Flow<List<LocalHeartbeatEntity>> = rows
        override suspend fun getAll() = rows.value
    }

    private class FixedClock(var now: Instant) : Clock {
        override fun now() = now
    }

    private val uploadDao = FakeUploadDao()
    private val localDao = FakeLocalDao()
    private val requests = mutableListOf<HttpRequestData>()
    private val settings = MapSettings()
    private val clock = FixedClock(Instant.fromEpochSeconds(1_794_735_530))
    private val payload = ByteArray(569) { it.toByte() }

    private fun TestScope.queue(bugUrl: String?): AnalyticsHeartbeatQueue {
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests += request
                respondOk()
            }
        })
        val apiConfig = object : CommonApiConfig {
            override val version = "test"
            override val bugUrl = bugUrl
            override val tokenUrl: String? = null
        }
        return AnalyticsHeartbeatQueue(
            ingest = AnalyticsIngest(HttpClient(engine), apiConfig, Platform.Android, CoreAppVersion("test")),
            dao = uploadDao,
            settings = settings,
            apiConfig = apiConfig,
            localStore = LocalHeartbeatStore(localDao, clock),
        )
    }

    private fun TestScope.process(queue: AnalyticsHeartbeatQueue) {
        queue.startProcessing(backgroundScope)
        queue.enqueue("Q123456789AB", "v4.47.0", payload)
        runCurrent()
        advanceTimeBy(11.seconds)
        runCurrent()
    }

    @Test
    fun withServerHeartbeatsAreUploadedAndNotKept() = runTest {
        process(queue("https://example.com/api"))
        assertEquals(1, uploadDao.inserted)
        assertEquals(1, requests.size)
        assertEquals("https://example.com/api/analytics/ingest", requests.single().url.toString())
        assertTrue(uploadDao.rows.isEmpty())
        assertTrue(localDao.rows.value.isEmpty())
    }

    @Test
    fun withoutServerHeartbeatsAreKeptLocally() = runTest {
        process(queue(null))
        assertEquals(0, uploadDao.inserted)
        assertTrue(requests.isEmpty())
        val row = localDao.rows.value.single()
        assertEquals("Q123456789AB", row.serial)
        assertEquals("v4.47.0", row.fwVersion)
        assertEquals(clock.now.toEpochMilliseconds(), row.receivedAt)
        assertTrue(payload.contentEquals(row.payload))
    }

    @Test
    fun analyticsDisabledKeepsNothing() = runTest {
        settings.putBoolean(KEY_ENABLE_MEMFAULT_UPLOADS, false)
        process(queue(null))
        assertTrue(localDao.rows.value.isEmpty())
        assertEquals(0, uploadDao.inserted)
    }

    @Test
    fun localRecordsOlderThanTheRetentionAreDropped() = runTest {
        val store = LocalHeartbeatStore(localDao, clock)
        store.store("A", null, 0, payload)
        clock.now += LocalHeartbeatStore.RETENTION + 1.days
        store.store("A", null, 0, payload)
        assertEquals(listOf(clock.now.toEpochMilliseconds()), localDao.rows.value.map { it.receivedAt })
    }
}
