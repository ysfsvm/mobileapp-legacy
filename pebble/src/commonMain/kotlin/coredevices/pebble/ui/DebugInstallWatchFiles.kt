package coredevices.pebble.ui

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
 * Puts the files of a picked firmware bundle on the watch's filesystem, or any other picked file
 * under a name that is asked for.
 */
@Composable
fun DebugInstallWatchFiles(watch: PebbleDevice?) {
    val appContext = koinInject<AppContext>()
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

    when (val current = state) {
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
