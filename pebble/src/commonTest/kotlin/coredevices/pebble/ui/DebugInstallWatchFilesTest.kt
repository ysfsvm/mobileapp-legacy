package coredevices.pebble.ui

import io.rebble.libpebblecommon.connection.endpointmanager.BundleFileOutcome
import io.rebble.libpebblecommon.connection.endpointmanager.BundleFileResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DebugInstallWatchFilesTest {
    private fun summary(vararg results: Pair<String, BundleFileOutcome>) =
        watchFilesSummary(results.map { BundleFileResult(it.first, it.second) }) { outcome, targets ->
            "$outcome: $targets"
        }

    @Test
    fun summaryGroupsTargetsByOutcome() {
        assertEquals(
            "Installed: emoji, other\nNotAsInManifest: wrong\nFailed: refused",
            summary(
                "refused" to BundleFileOutcome.Failed,
                "emoji" to BundleFileOutcome.Installed,
                "wrong" to BundleFileOutcome.NotAsInManifest,
                "other" to BundleFileOutcome.Installed,
            ),
        )
        assertEquals("Installed: emoji", summary("emoji" to BundleFileOutcome.Installed))
    }

    @Test
    fun noResultsHaveNoSummary() {
        assertNull(summary())
    }

    @Test
    fun resourcePackIsSuggestedAsEmojiPack() {
        assertEquals("emoji", suggestedWatchFileName("emoji_snowy_dvt.pbpack"))
        assertEquals("notes.txt", suggestedWatchFileName("notes.txt"))
    }
}
