package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.api.GithubRelease
import coredevices.api.LegacyReleasesApi
import coredevices.util.CoreConfigFlow
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A version of Core's PebbleOS as the legacy port tags it: `v4.47.0-legacy.2`, a build after a tag
 * (`v4.47.0-legacy.2-3-gabc1234`), or a plain Core tag with commits on top (`v4.47.0-30-g9ceecbb`).
 */
data class LegacyVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val legacy: Int,
    val commits: Int,
) : Comparable<LegacyVersion> {
    override fun compareTo(other: LegacyVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.legacy }, { it.commits })

    /** Core's PebbleOS starts at 4.9; Pebble's own firmware ended at 4.4. */
    val isCorePebbleOs: Boolean get() = major > 4 || (major == 4 && minor >= 9)

    companion object {
        private val TAG = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-legacy\.(\d+))?(?:-(\d+)-g[0-9a-f]+)?$""")

        fun parse(tag: String): LegacyVersion? = TAG.matchEntire(tag.trim())?.let { m ->
            LegacyVersion(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt(),
                legacy = m.groupValues[4].toIntOrNull() ?: 0,
                commits = m.groupValues[5].toIntOrNull() ?: 0,
            )
        }
    }
}

/** The board name in the port's bundle names, or null for a watch the port has no build for. */
fun WatchHardwarePlatform.legacyBundleBoard(): String? = when (this) {
    WatchHardwarePlatform.PEBBLE_SNOWY_DVT -> "snowy_dvt"
    WatchHardwarePlatform.PEBBLE_BOBBY_SMILES -> "snowy_s3"
    WatchHardwarePlatform.PEBBLE_SPALDING_PVT -> "spalding"
    else -> null
}

/** A release of the port with a bundle for one board. */
data class LegacyRelease(
    val tag: String,
    val version: LegacyVersion,
    val bundleUrl: String,
    val notes: String,
    val pageUrl: String,
    val published: Instant?,
)

/** The newest tagged release that has a bundle for [board]; drafts and untagged builds are skipped. */
fun newestLegacyRelease(releases: List<GithubRelease>, board: String): LegacyRelease? =
    releases.asSequence()
        .filter { !it.draft }
        .mapNotNull { release ->
            val version = LegacyVersion.parse(release.tag)?.takeIf { it.legacy > 0 && it.commits == 0 }
                ?: return@mapNotNull null
            val bundle = release.assets.firstOrNull { it.name == "normal_${board}_${release.tag}.pbz" }
                ?: return@mapNotNull null
            LegacyRelease(
                tag = release.tag,
                version = version,
                bundleUrl = bundle.url,
                notes = release.body.orEmpty(),
                pageUrl = release.pageUrl,
                published = release.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
        .maxByOrNull { it.version }

/** What the last look at the port's releases found for one board. */
data class LegacyCheck(val newest: LegacyRelease?, val checkedAt: Instant?, val failed: Boolean)

/**
 * Looks up the port's releases on GitHub at the app's background refresh interval, or only when
 * asked if the user turned that off. The last list is kept across restarts, so a check that is not
 * due answers from it.
 */
class LegacyFirmwareUpdates(
    private val api: LegacyReleasesApi,
    private val clock: Clock,
    private val coreConfig: CoreConfigFlow,
    private val settings: Settings,
) {
    private val logger = Logger.withTag("LegacyFirmwareUpdates")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val lastChecked: Instant?
        get() = settings.getLongOrNull(KEY_CHECKED_AT)?.let { Instant.fromEpochMilliseconds(it) }

    private fun stored(): List<GithubRelease>? = settings.getStringOrNull(KEY_RELEASES)?.let {
        runCatching { json.decodeFromString<List<GithubRelease>>(it) }.getOrNull()
    }

    /** The releases, fetched when [force]d or due, else as last fetched; null before any fetch. */
    private suspend fun releases(force: Boolean): List<GithubRelease>? = mutex.withLock {
        val now = clock.now()
        val last = lastChecked
        val config = coreConfig.value
        val interval = config.weatherSyncInterval.takeIf { config.legacyUpdateAutoCheck }
        val fresh = last != null && now - last < MIN_INTERVAL
        val due = !fresh && (force || (interval != null && (last == null || now - last >= interval)))
        if (!due) return stored()
        val releases = api.releases()
        settings.putLong(KEY_CHECKED_AT, now.toEpochMilliseconds())
        settings.putString(KEY_RELEASES, json.encodeToString(releases))
        releases
    }

    /** The newest release for [platform]'s board; looks on GitHub only when [force]d or due. */
    suspend fun lookup(platform: WatchHardwarePlatform, force: Boolean = false): LegacyCheck {
        val board = platform.legacyBundleBoard() ?: return LegacyCheck(null, lastChecked, failed = false)
        return try {
            val releases = releases(force)
            LegacyCheck(releases?.let { newestLegacyRelease(it, board) }, lastChecked, failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Couldn't list the port's releases: ${e.message}" }
            LegacyCheck(stored()?.let { newestLegacyRelease(it, board) }, lastChecked, failed = true)
        }
    }

    /** The release [tag] with its bundle for [platform]'s board; looks on GitHub if the last list lacks it. */
    suspend fun release(platform: WatchHardwarePlatform, tag: String): LegacyRelease? {
        val board = platform.legacyBundleBoard() ?: return null
        fun find(releases: List<GithubRelease>?) = releases?.let { list ->
            newestLegacyRelease(list.filter { it.tag == tag }, board)
        }
        return find(stored()) ?: find(releases(force = true))
    }

    /** The bundle of [release], downloaded from GitHub. */
    suspend fun download(release: LegacyRelease): ByteArray = api.download(release.bundleUrl)

    /** An update when a release for the watch's board is newer than what it runs. */
    suspend fun check(watch: WatchInfo, force: Boolean): FirmwareUpdateCheckResult {
        val running = LegacyVersion.parse(watch.runningFwVersion.stringVersion)
            ?: return FirmwareUpdateCheckResult.FoundNoUpdate
        val newest = lookup(watch.platform, force).newest?.takeIf { it.version > running }
            ?: return FirmwareUpdateCheckResult.FoundNoUpdate
        val version = FirmwareVersion.from(
            tag = newest.tag,
            isRecovery = false,
            gitHash = "",
            timestamp = newest.published ?: Instant.DISTANT_PAST,
            isDualSlot = false,
            isSlot0 = false,
        ) ?: return FirmwareUpdateCheckResult.FoundNoUpdate
        logger.i { "Port release ${newest.tag} for ${watch.platform}, running ${watch.runningFwVersion.stringVersion}" }
        return FirmwareUpdateCheckResult.FoundUpdate(
            version = version,
            url = newest.bundleUrl,
            notes = newest.notes,
            // Set by FirmwareUpdateManager, which knows what the watch is running.
            installsDirectly = false,
        )
    }

    companion object {
        /** A forced check right after another one answers from it: the card and the update check both ask. */
        private val MIN_INTERVAL = 1.minutes
        private const val KEY_CHECKED_AT = "legacy_releases_checked_at"
        private const val KEY_RELEASES = "legacy_releases"
    }
}
