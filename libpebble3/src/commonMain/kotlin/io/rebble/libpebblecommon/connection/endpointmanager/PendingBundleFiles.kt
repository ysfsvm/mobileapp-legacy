package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.metadata.pbz.manifest.PbzManifestWrapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The firmware bundle whose files a watch is still owed once it runs that bundle's firmware. Stored,
 * because the update ends with the watch rebooting and the app may not live until it is back.
 */
class PendingBundleFiles(private val settings: Settings) {
    private val logger = Logger.withTag("PendingBundleFiles")

    @Serializable
    data class Pending(
        val bundlePath: String,
        val manifestPath: String,
        val versionTag: String,
        val attempts: Int = 0,
    )

    /** Replaces what an earlier update left; a bundle without files leaves nothing. */
    fun record(identifier: PebbleIdentifier, manifest: PbzManifestWrapper) {
        val versionTag = manifest.manifest.firmware.versionTag
        if (manifest.manifest.files.isEmpty() || versionTag == null) {
            clear(identifier)
            return
        }
        store(identifier, Pending(manifest.zipPath.toString(), manifest.manifestPath.toString(), versionTag))
    }

    fun get(identifier: PebbleIdentifier): Pending? = try {
        settings.getStringOrNull(key(identifier))?.let { Json.decodeFromString<Pending>(it) }
    } catch (e: Exception) {
        logger.w(e) { "Unreadable pending bundle files for $identifier" }
        null
    }

    /** Counts an attempt before it is made, so that one which never returns is counted as well. */
    fun startAttempt(identifier: PebbleIdentifier): Pending? =
        get(identifier)?.let { it.copy(attempts = it.attempts + 1) }?.also { store(identifier, it) }

    fun clear(identifier: PebbleIdentifier) = settings.remove(key(identifier))

    private fun store(identifier: PebbleIdentifier, pending: Pending) =
        settings.putString(key(identifier), Json.encodeToString(pending))

    private fun key(identifier: PebbleIdentifier) = "pending_bundle_files_${identifier.asString}"
}
