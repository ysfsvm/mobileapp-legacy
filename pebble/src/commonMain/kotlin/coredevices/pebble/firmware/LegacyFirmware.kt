package coredevices.pebble.firmware

import co.touchlab.kermit.Logger
import coredevices.api.GithubRelease
import coredevices.api.LegacyReleasesApi
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Finds the port's releases on GitHub, at most once every half hour. */
class LegacyFirmwareUpdates(
    private val api: LegacyReleasesApi,
    private val clock: Clock,
) {
    private val logger = Logger.withTag("LegacyFirmwareUpdates")
    private val mutex = Mutex()
    private var cached: Pair<Instant, List<GithubRelease>>? = null

    private suspend fun releases(): List<GithubRelease> = mutex.withLock {
        cached?.takeIf { clock.now() - it.first < CACHE_TTL }?.second
            ?: api.releases().also { cached = clock.now() to it }
    }

    /** The newest release for the watch's board; null when there is none or GitHub can't be reached. */
    suspend fun newest(platform: WatchHardwarePlatform): LegacyRelease? {
        val board = platform.legacyBundleBoard() ?: return null
        return try {
            newestLegacyRelease(releases(), board)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Couldn't list the port's releases: ${e.message}" }
            null
        }
    }

    /** An update when a release for the watch's board is newer than what it runs. */
    suspend fun check(watch: WatchInfo): FirmwareUpdateCheckResult {
        val running = LegacyVersion.parse(watch.runningFwVersion.stringVersion)
            ?: return FirmwareUpdateCheckResult.FoundNoUpdate
        val newest = newest(watch.platform)?.takeIf { it.version > running }
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
        private val CACHE_TTL = 30.minutes
    }
}
