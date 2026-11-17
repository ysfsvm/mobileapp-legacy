package coredevices.pebble.firmware

import coredevices.api.GithubRelease
import coredevices.api.GithubReleaseAsset
import coredevices.api.LegacyReleasesApi
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LegacyFirmwareTest {
    private fun release(tag: String, vararg boards: String, draft: Boolean = false) = GithubRelease(
        tag = tag,
        body = "notes $tag",
        pageUrl = "https://github.com/ysfsvm/PebbleOS-legacy/releases/tag/$tag",
        draft = draft,
        publishedAt = "2026-11-15T21:00:00Z",
        assets = boards.map { GithubReleaseAsset("normal_${it}_$tag.pbz", "https://example.invalid/$it/$tag.pbz") },
    )

    private class FakeApi(var releases: List<GithubRelease>, var fail: Boolean = false) : LegacyReleasesApi {
        var calls = 0
        override suspend fun releases(): List<GithubRelease> {
            calls++
            if (fail) throw Exception("offline")
            return releases
        }

        override suspend fun download(url: String) = url.encodeToByteArray()
    }

    private class FakeClock(var now: Instant = Instant.fromEpochSeconds(1_800_000_000)) : Clock {
        override fun now() = now
    }

    private fun version(tag: String) = FirmwareVersion(
        tag, Instant.DISTANT_PAST, 0, 0, 0, "", "", false, isDualSlot = false, isSlot0 = false,
    )

    private fun watch(running: String, platform: WatchHardwarePlatform = WatchHardwarePlatform.PEBBLE_SNOWY_DVT) =
        WatchInfo(
            runningFwVersion = version(running),
            recoveryFwVersion = version("v3.0.3-prf"),
            platform = platform,
            bootloaderTimestamp = Instant.DISTANT_PAST,
            board = "snowy_dvt",
            serial = "S",
            btAddress = "00:00:00:00:00:00",
            resourceCrc = 0,
            resourceTimestamp = Instant.DISTANT_PAST,
            language = "en_US",
            languageVersion = 1,
            capabilities = emptySet(),
            isUnfaithful = false,
            healthInsightsVersion = 1,
            javascriptVersion = 1,
            color = WatchColor.TimeBlack,
        )

    @Test
    fun parsesPortCoreAndDevTags() {
        assertEquals(LegacyVersion(4, 47, 0, 2, 0), LegacyVersion.parse("v4.47.0-legacy.2"))
        assertEquals(LegacyVersion(4, 47, 0, 1, 7), LegacyVersion.parse("v4.47.0-legacy.1-7-g880d40002c"))
        assertEquals(LegacyVersion(4, 47, 0, 0, 30), LegacyVersion.parse("v4.47.0-30-g9ceecbba47"))
        assertEquals(LegacyVersion(4, 48, 0, 0, 0), LegacyVersion.parse("4.48.0"))
        assertNull(LegacyVersion.parse("v4.47.0-legacy.2-dirty"))
        assertNull(LegacyVersion.parse("v3.0.3-prf"))
    }

    @Test
    fun ordersByBaseThenPortNumberThenCommits() {
        val order = listOf("v4.46.0-legacy.9", "v4.47.0-30-g9ceecbb", "v4.47.0-legacy.1", "v4.47.0-legacy.1-7-gabc", "v4.47.0-legacy.2", "v4.48.0")
            .map { LegacyVersion.parse(it)!! }
        assertEquals(order, order.shuffled().sorted())
        assertTrue(LegacyVersion.parse("v4.47.0-legacy.1")!!.isCorePebbleOs)
        assertTrue(!LegacyVersion.parse("v4.4.0")!!.isCorePebbleOs)
    }

    @Test
    fun boardsHaveBundles() {
        assertEquals("snowy_dvt", WatchHardwarePlatform.PEBBLE_SNOWY_DVT.legacyBundleBoard())
        assertEquals("snowy_s3", WatchHardwarePlatform.PEBBLE_BOBBY_SMILES.legacyBundleBoard())
        assertEquals("spalding", WatchHardwarePlatform.PEBBLE_SPALDING_PVT.legacyBundleBoard())
        assertNull(WatchHardwarePlatform.PEBBLE_SILK.legacyBundleBoard())
    }

    @Test
    fun newestReleaseSkipsDraftsUntaggedAndOtherBoards() {
        val releases = listOf(
            release("v4.47.0-legacy.4", "snowy_dvt", draft = true),
            release("v4.47.0-legacy.3", "spalding"),
            release("v4.48.0", "snowy_dvt"),
            release("v4.47.0-legacy.2", "snowy_dvt", "snowy_s3"),
            release("v4.47.0-legacy.1", "snowy_dvt"),
        )
        val dvt = newestLegacyRelease(releases, "snowy_dvt")!!
        assertEquals("v4.47.0-legacy.2", dvt.tag)
        assertEquals("https://example.invalid/snowy_dvt/v4.47.0-legacy.2.pbz", dvt.bundleUrl)
        assertEquals("notes v4.47.0-legacy.2", dvt.notes)
        assertEquals("v4.47.0-legacy.3", newestLegacyRelease(releases, "spalding")!!.tag)
        assertNull(newestLegacyRelease(releases, "silk"))
    }

    private fun updates(api: FakeApi, clock: FakeClock = FakeClock(), auto: Boolean = true, settings: MapSettings = MapSettings()) =
        LegacyFirmwareUpdates(
            api,
            clock,
            CoreConfigFlow(MutableStateFlow(CoreConfig(weatherSyncInterval = 6.hours, legacyUpdateAutoCheck = auto))),
            settings,
        )

    @Test
    fun offersOnlyANewerRelease() = runTest {
        val updates = updates(FakeApi(listOf(release("v4.47.0-legacy.2", "snowy_dvt"))))
        val found = assertIs<FirmwareUpdateCheckResult.FoundUpdate>(updates.check(watch("v4.47.0-legacy.1"), force = false))
        assertEquals("v4.47.0-legacy.2", found.version.stringVersion)
        assertEquals("https://example.invalid/snowy_dvt/v4.47.0-legacy.2.pbz", found.url)
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, updates.check(watch("v4.47.0-legacy.2"), force = false))
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, updates.check(watch("v4.47.0-legacy.2-3-gabc1234"), force = false))
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(updates.check(watch("v4.47.0-30-g9ceecbba47"), force = false))
    }

    @Test
    fun noUpdateForOtherWatchesOrWhenGithubFails() = runTest {
        val api = FakeApi(listOf(release("v4.47.0-legacy.2", "snowy_dvt")), fail = true)
        val updates = updates(api)
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, updates.check(watch("v4.47.0-legacy.1"), force = false))
        assertTrue(updates.lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT, force = true).failed)
        api.fail = false
        assertEquals(
            FirmwareUpdateCheckResult.FoundNoUpdate,
            updates.check(watch("v4.47.0-legacy.1", WatchHardwarePlatform.PEBBLE_SILK), force = false),
        )
    }

    @Test
    fun looksAtTheBackgroundRefreshInterval() = runTest {
        val api = FakeApi(listOf(release("v4.47.0-legacy.2", "snowy_dvt")))
        val clock = FakeClock()
        val updates = updates(api, clock)
        updates.lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT)
        clock.now += 5.hours
        assertEquals("v4.47.0-legacy.2", updates.lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT).newest?.tag)
        assertEquals(1, api.calls)
        clock.now += 2.hours
        updates.lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT)
        assertEquals(2, api.calls)
        assertEquals(clock.now, updates.lastChecked)
    }

    @Test
    fun manualLooksOnlyWhenAskedAndNotTwiceInAMinute() = runTest {
        val api = FakeApi(listOf(release("v4.47.0-legacy.2", "snowy_dvt")))
        val clock = FakeClock()
        val updates = updates(api, clock, auto = false)
        val before = updates.lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT)
        assertNull(before.checkedAt)
        assertNull(before.newest)
        assertEquals(FirmwareUpdateCheckResult.FoundNoUpdate, updates.check(watch("v4.47.0-legacy.1"), force = false))
        assertEquals(0, api.calls)
        assertEquals("v4.47.0-legacy.2", updates.lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT, force = true).newest?.tag)
        clock.now += 30.minutes / 60
        assertIs<FirmwareUpdateCheckResult.FoundUpdate>(updates.check(watch("v4.47.0-legacy.1"), force = true))
        assertEquals(1, api.calls)
        clock.now += 2.minutes
        updates.lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT, force = true)
        assertEquals(2, api.calls)
    }

    @Test
    fun aRestartAnswersFromTheLastList() = runTest {
        val settings = MapSettings()
        val clock = FakeClock()
        updates(FakeApi(listOf(release("v4.47.0-legacy.2", "snowy_dvt"))), clock, settings = settings)
            .lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT)
        val api = FakeApi(emptyList())
        clock.now += 1.hours
        val check = updates(api, clock, settings = settings).lookup(WatchHardwarePlatform.PEBBLE_SNOWY_DVT)
        assertEquals("v4.47.0-legacy.2", check.newest?.tag)
        assertEquals(0, api.calls)
    }

    @Test
    fun findsTheReleaseOfARunningTag() = runTest {
        val api = FakeApi(listOf(release("v4.47.0-legacy.2", "snowy_dvt"), release("v4.47.0-legacy.1", "snowy_dvt")))
        val updates = updates(api, auto = false)
        val release = updates.release(WatchHardwarePlatform.PEBBLE_SNOWY_DVT, "v4.47.0-legacy.1")!!
        assertEquals("https://example.invalid/snowy_dvt/v4.47.0-legacy.1.pbz", release.bundleUrl)
        assertEquals(1, api.calls)
        assertNull(updates.release(WatchHardwarePlatform.PEBBLE_SPALDING_PVT, "v4.47.0-legacy.1"))
        assertEquals(release.bundleUrl, updates.download(release).decodeToString())
    }
}
