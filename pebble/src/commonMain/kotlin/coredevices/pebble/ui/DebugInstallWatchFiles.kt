package coredevices.pebble.ui

import androidx.compose.runtime.collectAsState
import coreapp.pebble.generated.resources.debug_emoji_download_failed
import coreapp.pebble.generated.resources.debug_emoji_downloading
import coreapp.pebble.generated.resources.debug_emoji_failed
import coreapp.pebble.generated.resources.debug_emoji_from_github
import coreapp.pebble.generated.resources.debug_emoji_installed
import coreapp.pebble.generated.resources.debug_emoji_installing
import coreapp.pebble.generated.resources.debug_emoji_last
import coreapp.pebble.generated.resources.debug_emoji_no_release
import coreapp.pebble.generated.resources.debug_emoji_none
import coreapp.pebble.generated.resources.debug_emoji_retry
import coreapp.pebble.generated.resources.debug_emoji_title
import coredevices.pebble.firmware.LegacyFirmwareUpdates
import coredevices.pebble.firmware.LegacyVersion
import coredevices.pebble.firmware.legacyBundleBoard
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.BundleFilesState
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import coreapp.pebble.generated.resources.Res
import coreapp.pebble.generated.resources.debug_watch_file_name
import coreapp.pebble.generated.resources.debug_watch_files_cancel
import coreapp.pebble.generated.resources.debug_watch_files_failed
import coreapp.pebble.generated.resources.debug_watch_files_install
import coreapp.pebble.generated.resources.debug_watch_files_installed
import coreapp.pebble.generated.resources.debug_watch_files_installing
import coreapp.pebble.generated.resources.debug_watch_files_none
import coreapp.pebble.generated.resources.debug_watch_files_pick
import coreapp.pebble.generated.resources.debug_watch_files_not_connected
import coreapp.pebble.generated.resources.debug_watch_files_skipped
import coredevices.ui.CoreLinearProgressIndicator
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.BundleFileOutcome
import io.rebble.libpebblecommon.connection.endpointmanager.BundleFileResult
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import rememberOpenDocumentLauncher
import kotlin.math.roundToInt

private sealed interface WatchFilesUiState {
    data object Idle : WatchFilesUiState
    data class AskName(val file: Path, val suggested: String) : WatchFilesUiState
    data class Installing(val progress: Float) : WatchFilesUiState
    data class Downloading(val tag: String) : WatchFilesUiState
    data class Done(val message: String) : WatchFilesUiState
}

private const val BUNDLE_EXTENSION = ".pbz"
private const val RESOURCE_PACK_EXTENSION = ".pbpack"
private const val EMOJI_PACK_TARGET = "emoji"

/** The outcome of a transfer as text, or null if nothing was there to transfer. */
internal fun watchFilesSummary(
    results: List<BundleFileResult>,
    describe: (BundleFileOutcome, String) -> String,
): String? = BundleFileOutcome.entries.mapNotNull { outcome ->
    results.filter { it.outcome == outcome }.takeIf { it.isNotEmpty() }
        ?.let { describe(outcome, it.joinToString { result -> result.target }) }
}.takeIf { it.isNotEmpty() }?.joinToString("\n")

internal fun suggestedWatchFileName(fileName: String): String =
    if (fileName.endsWith(RESOURCE_PACK_EXTENSION)) EMOJI_PACK_TARGET else fileName

/**
 * Puts the files of a firmware bundle on the watch's filesystem: the bundle of the running release
 * from GitHub, or a picked bundle. Any other picked file goes under a name that is asked for.
 */
@Composable
fun DebugInstallWatchFiles(watch: PebbleDevice?) {
    val appContext = koinInject<AppContext>()
    val legacyFirmware = koinInject<LegacyFirmwareUpdates>()
    val scope = rememberCoroutineScope()
    val currentWatch by rememberUpdatedState(watch)
    var state by remember { mutableStateOf<WatchFilesUiState>(WatchFilesUiState.Idle) }

    fun install(transfer: suspend ConnectedPebble.BundleFiles.() -> List<BundleFileResult>) {
        state = WatchFilesUiState.Installing(0f)
        scope.launch {
            val connected = currentWatch as? ConnectedPebble.BundleFiles
            val results = try {
                connected?.transfer()
            } catch (e: IllegalStateException) {
                state = WatchFilesUiState.Done(getString(Res.string.debug_watch_files_not_connected))
                return@launch
            }
            val texts = mapOf(
                BundleFileOutcome.Installed to getString(Res.string.debug_watch_files_installed),
                BundleFileOutcome.NotAsInManifest to getString(Res.string.debug_watch_files_skipped),
                BundleFileOutcome.Failed to getString(Res.string.debug_watch_files_failed),
            )
            state = WatchFilesUiState.Done(
                when (results) {
                    null -> getString(Res.string.debug_watch_files_not_connected)
                    else -> watchFilesSummary(results) { outcome, targets -> "${texts[outcome]}: $targets" }
                        ?: getString(Res.string.debug_watch_files_none)
                }
            )
        }
    }

    val onProgress: (Float) -> Unit = { state = WatchFilesUiState.Installing(it) }
    val pickFile = rememberOpenDocumentLauncher { picked ->
        picked?.firstOrNull()?.let { file ->
            val path = Path(getTempFwPath(appContext).parent!!, "temp-watch-file")
            SystemFileSystem.sink(path).use { sink ->
                file.source.use { source -> source.transferTo(sink) }
            }
            if (file.fileName.endsWith(BUNDLE_EXTENSION)) {
                install { installBundleFiles(path, onProgress) }
            } else {
                state = WatchFilesUiState.AskName(path, suggestedWatchFileName(file.fileName))
            }
        }
    }

    Text(stringResource(Res.string.debug_emoji_title), style = MaterialTheme.typography.titleMedium)
    val autoState = (watch as? ConnectedPebble.BundleFiles)?.bundleFilesState?.collectAsState()?.value
    Text(
        when (autoState) {
            null, is BundleFilesState.Idle -> stringResource(Res.string.debug_emoji_none)
            else -> stringResource(Res.string.debug_emoji_last, autoBundleText(autoState))
        },
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
    // A watch running a tagged release of the port can get that release's emoji pack again.
    val known = watch as? KnownPebbleDevice
    val releaseTag = known?.runningFwVersion?.takeIf { tag ->
        LegacyVersion.parse(tag)?.let { it.legacy > 0 && it.commits == 0 } == true &&
            known.watchType.legacyBundleBoard() != null
    }
    if (releaseTag != null && state !is WatchFilesUiState.Installing && state !is WatchFilesUiState.Downloading) {
        Button(onClick = {
            state = WatchFilesUiState.Downloading(releaseTag)
            scope.launch {
                val bundle = try {
                    val release = legacyFirmware.release(known.watchType, releaseTag)
                    if (release == null) {
                        state = WatchFilesUiState.Done(getString(Res.string.debug_emoji_no_release, releaseTag))
                        return@launch
                    }
                    legacyFirmware.download(release)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state = WatchFilesUiState.Done(getString(Res.string.debug_emoji_download_failed, e.message ?: "error"))
                    return@launch
                }
                val path = Path(getTempFwPath(appContext).parent!!, "temp-release-bundle.pbz")
                SystemFileSystem.sink(path).buffered().use { it.write(bundle) }
                install { installBundleFiles(path, onProgress) }
            }
        }) {
            Text(stringResource(Res.string.debug_emoji_from_github, releaseTag))
        }
    }

    when (val current = state) {
        is WatchFilesUiState.Downloading -> {
            Text(
                stringResource(Res.string.debug_emoji_downloading, current.tag),
                style = MaterialTheme.typography.titleMedium,
            )
            CoreLinearProgressIndicator()
        }

        is WatchFilesUiState.Installing -> {
            Text(
                stringResource(Res.string.debug_watch_files_installing, (current.progress * 100).roundToInt()),
                style = MaterialTheme.typography.titleMedium,
            )
            CoreLinearProgressIndicator(progress = { current.progress })
        }

        else -> Button(onClick = { pickFile(listOf("*/*")) }) {
            Text(stringResource(Res.string.debug_watch_files_pick))
        }
    }
    (state as? WatchFilesUiState.Done)?.let {
        Text(it.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
    (state as? WatchFilesUiState.AskName)?.let { ask ->
        var name by remember(ask) { mutableStateOf(ask.suggested) }
        AlertDialog(
            onDismissRequest = { state = WatchFilesUiState.Idle },
            title = { Text(stringResource(Res.string.debug_watch_file_name)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val target = name.trim()
                        install {
                            val installed = installFile(ask.file, target, onProgress)
                            val outcome = if (installed) BundleFileOutcome.Installed else BundleFileOutcome.Failed
                            listOf(BundleFileResult(target, outcome))
                        }
                    },
                ) { Text(stringResource(Res.string.debug_watch_files_install)) }
            },
            dismissButton = {
                TextButton(onClick = { state = WatchFilesUiState.Idle }) {
                    Text(stringResource(Res.string.debug_watch_files_cancel))
                }
            },
        )
    }
}

@Composable
private fun autoBundleText(state: BundleFilesState): String = when (state) {
    is BundleFilesState.Idle -> stringResource(Res.string.debug_emoji_none)
    is BundleFilesState.Installing -> {
        val progress by state.progress.collectAsState()
        stringResource(Res.string.debug_emoji_installing, (progress * 100).roundToInt())
    }
    is BundleFilesState.Installed -> stringResource(Res.string.debug_emoji_installed)
    is BundleFilesState.WillRetry -> stringResource(Res.string.debug_emoji_retry, state.attempt, state.maxAttempts)
    is BundleFilesState.Failed -> stringResource(Res.string.debug_emoji_failed, state.reason)
}
