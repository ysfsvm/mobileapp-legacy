package coredevices.pebble.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coredevices.pebble.firmware.LegacyCheck
import coredevices.pebble.firmware.LegacyFirmwareUpdates
import coredevices.pebble.firmware.LegacyVersion
import coredevices.pebble.firmware.legacyBundleBoard
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.PebbleDevice
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val RELEASES_PAGE = "https://github.com/ysfsvm/PebbleOS-legacy/releases"
private const val DOCS_PAGE = "https://ysfsvm.github.io/pebble-legacy-docs/"

/** Which build of the legacy port the watch runs, whether a newer one is out, and where to read more. */
@Composable
fun LegacyPortInfo(watch: PebbleDevice) {
    val known = watch as? KnownPebbleDevice ?: return
    val running = LegacyVersion.parse(known.runningFwVersion)?.takeIf { it.isCorePebbleOs } ?: return
    if (known.watchType.legacyBundleBoard() == null) return
    val updates = koinInject<LegacyFirmwareUpdates>()
    var check by remember(known.serial) { mutableStateOf<LegacyCheck?>(null) }
    // Looks again whenever the app's own update check (on connection, background, or "Check for
    // Updates" in the watch's menu) has answered, so the line follows what it found.
    val appCheck = (watch as? ConnectedPebble.FirmwareStatus)?.firmwareUpdateAvailable
    LaunchedEffect(known.serial, known.runningFwVersion, appCheck) {
        if (appCheck?.checkingForUpdates != true) check = updates.lookup(known.watchType)
    }
    val uriHandler = LocalUriHandler.current
    val model = known.color?.uiDescription?.substringBefore(" - ") ?: known.watchType.watchType.modelDescription()
    val latest = check?.newest
    val status = when {
        check == null || appCheck?.checkingForUpdates == true -> "Looking for a newer build…"
        check?.failed == true -> "Couldn't reach GitHub"
        check?.checkedAt == null -> "Not checked yet"
        latest == null -> "No build for this watch on GitHub"
        latest.version > running -> "${latest.tag} is out"
        else -> "Up to date"
    }
    val notesPage = when {
        latest != null && latest.version > running -> latest.pageUrl
        running.legacy > 0 && running.commits == 0 -> "$RELEASES_PAGE/tag/${known.runningFwVersion}"
        else -> RELEASES_PAGE
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "PebbleOS port for the $model",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text("Running ${known.runningFwVersion} · $status", style = MaterialTheme.typography.bodyMedium)
        check?.checkedAt?.let {
            Text(
                "Checked ${ago(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            TextButton(onClick = { uriHandler.openUri(notesPage) }) { Text("Release notes") }
            TextButton(onClick = { uriHandler.openUri(DOCS_PAGE) }) { Text("Docs") }
        }
    }
}

private fun ago(t: Instant): String {
    val d = Clock.System.now() - t
    return when {
        d < 1.minutes -> "just now"
        d < 1.hours -> "${d.inWholeMinutes} min ago"
        d < 2.days -> "${d.inWholeHours} h ago"
        else -> "${d.inWholeDays} days ago"
    }
}
