package coredevices.pebble.battery

import co.touchlab.kermit.Logger
import coredevices.pebble.services.AppstoreService
import coredevices.database.AppstoreSource
import coredevices.pebble.services.RealPebbleWebServices
import coredevices.pebble.services.StoreBatteryImpact
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** A watchface's battery impact from the app store, and the platform it was measured on. */
data class WatchfaceImpact(val platform: String, val impact: StoreBatteryImpact)

/** Looks up the store's battery impact of a watchface by UUID, once per UUID. */
class WatchfaceImpactLookup(
    private val webServices: RealPebbleWebServices,
    private val appstore: (AppstoreSource) -> AppstoreService,
) {
    private val logger = Logger.withTag("WatchfaceImpactLookup")
    private val mutex = Mutex()
    private val found = mutableMapOf<String, WatchfaceImpact?>()

    suspend fun impact(uuid: String, platform: WatchType): WatchfaceImpact? = mutex.withLock {
        if (uuid in found) return found[uuid]
        val result = try {
            val parsed = Uuid.parse(uuid.trim('{', '}'))
            webServices.searchUuidInSources(parsed).firstNotNullOfOrNull { (id, source) ->
                appstore(source).fetchAppStoreApp(id, platform)?.data?.firstOrNull()?.batteryImpact
            }?.takeIf { it.isNotEmpty() }?.let { impacts ->
                val key = if (platform.codename in impacts) platform.codename else impacts.keys.first()
                WatchfaceImpact(key, impacts.getValue(key))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "No store battery impact for $uuid" }
            null
        }
        found[uuid] = result
        result
    }
}
