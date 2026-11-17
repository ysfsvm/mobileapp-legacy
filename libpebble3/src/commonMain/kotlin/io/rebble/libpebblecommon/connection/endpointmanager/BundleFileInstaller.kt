package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.ErrorTracker
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.UserFacingError
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.disk.pbz.PbzFirmware
import io.rebble.libpebblecommon.metadata.pbz.manifest.PbzBundleFile
import io.rebble.libpebblecommon.metadata.pbz.manifest.PbzManifestWrapper
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.util.crc32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

enum class BundleFileOutcome {
    Installed,

    /** Not sent: the file in the bundle is not the one its manifest describes. */
    NotAsInManifest,
    Failed,
}

data class BundleFileResult(val target: String, val outcome: BundleFileOutcome)

/** What the install of a firmware update's files is doing on this connection. */
sealed interface BundleFilesState {
    data object Idle : BundleFilesState

    /** Sending [targets]; [attempt] counts the connections that tried, this one included. */
    data class Installing(val targets: List<String>, val attempt: Int, val progress: StateFlow<Float>) :
        BundleFilesState

    data class Installed(val targets: List<String>) : BundleFilesState

    /** Not all of [targets] went over; the next connection tries again. */
    data class WillRetry(val targets: List<String>, val attempt: Int, val maxAttempts: Int) : BundleFilesState

    /** Given up on [targets] (empty when the bundle could not be read). */
    data class Failed(val targets: List<String>, val reason: String) : BundleFilesState
}

/** Puts files on the watch's filesystem: the ones a firmware bundle carries, or a single one. */
class BundleFileInstaller(
    private val identifier: PebbleIdentifier,
    private val putBytesSession: PutBytesSession,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
    private val pendingBundleFiles: PendingBundleFiles,
    private val errorTracker: ErrorTracker,
) : ConnectedPebble.BundleFiles {
    private val logger = Logger.withTag("BundleFileInstaller-$identifier")
    private val _state = MutableStateFlow<BundleFilesState>(BundleFilesState.Idle)
    override val bundleFilesState: StateFlow<BundleFilesState> = _state.asStateFlow()

    /** Installs the files of the bundle the watch was last updated from, if it now runs that firmware. */
    fun init(runningFwVersion: FirmwareVersion) {
        val pending = pendingBundleFiles.get(identifier) ?: return
        if (!runningFwVersion.isSameBuildAs(pending.versionTag)) {
            logger.w {
                "Not installing the files of ${pending.versionTag}: the watch runs ${runningFwVersion.stringVersion}"
            }
            pendingBundleFiles.clear(identifier)
            return
        }
        connectionCoroutineScope.launch { installPending() }
    }

    private suspend fun installPending() {
        val pending = pendingBundleFiles.startAttempt(identifier) ?: return
        if (pending.attempts > MAX_ATTEMPTS) {
            giveUp("the transfer did not finish in $MAX_ATTEMPTS attempts")
            return
        }
        val manifest = try {
            PbzFirmware(Path(pending.bundlePath)).manifests.firstOrNull {
                it.manifestPath.toString() == pending.manifestPath &&
                    it.manifest.firmware.versionTag == pending.versionTag
            }
        } catch (e: Exception) {
            logger.w(e) { "Failed to read firmware bundle: ${e.message}" }
            null
        }
        if (manifest == null) {
            giveUp("the firmware bundle is no longer on the phone")
            return
        }
        logger.i { "Installing the files of ${pending.versionTag} (attempt ${pending.attempts})" }
        val targets = manifest.manifest.files.map { it.target }
        val progress = MutableStateFlow(0f)
        _state.value = BundleFilesState.Installing(targets, pending.attempts, progress.asStateFlow())
        val results = install(manifest) { progress.value = it }
        when {
            results.none { it.outcome == BundleFileOutcome.Failed } -> {
                pendingBundleFiles.clear(identifier)
                _state.value = BundleFilesState.Installed(
                    results.filter { it.outcome == BundleFileOutcome.Installed }.map { it.target }
                )
            }
            pending.attempts >= MAX_ATTEMPTS -> giveUp("the transfer failed $MAX_ATTEMPTS times", targets)
            else -> {
                logger.w { "Files of ${pending.versionTag} not installed; trying again on the next connection" }
                _state.value = BundleFilesState.WillRetry(targets, pending.attempts, MAX_ATTEMPTS)
            }
        }
    }

    private fun giveUp(reason: String, targets: List<String> = emptyList()) {
        logger.e { "Giving up on the files of the firmware update: $reason" }
        _state.value = BundleFilesState.Failed(targets, reason)
        pendingBundleFiles.clear(identifier)
        errorTracker.reportError(
            UserFacingError.FailedToInstallBundleFiles(
                "The files that came with the firmware update were not installed: $reason"
            )
        )
    }

    override suspend fun installBundleFiles(
        bundle: Path,
        onProgress: (Float) -> Unit,
    ): List<BundleFileResult> = onConnection {
        val manifest = try {
            PbzFirmware(bundle).manifests.firstOrNull { it.manifest.files.isNotEmpty() }
        } catch (e: Exception) {
            logger.w(e) { "Failed to read firmware bundle: ${e.message}" }
            null
        }
        manifest?.let { install(it, onProgress) }.orEmpty()
    }

    override suspend fun installFile(file: Path, target: String, onProgress: (Float) -> Unit): Boolean =
        onConnection {
            val size = SystemFileSystem.metadataOrNull(file)?.size ?: 0
            if (size <= 0 || target.isEmpty()) {
                logger.w { "Nothing to install as '$target' in $file" }
                return@onConnection false
            }
            send(target, size, { SystemFileSystem.source(file).buffered() }) {
                onProgress(it.toFloat() / size)
            }
        }

    internal suspend fun install(
        manifest: PbzManifestWrapper,
        onProgress: (Float) -> Unit = {},
    ): List<BundleFileResult> {
        val files = manifest.manifest.files
        val totalSize = files.sumOf { it.size }.coerceAtLeast(1)
        var sizeDone = 0L
        val results = mutableListOf<BundleFileResult>()
        for (file in files) {
            val before = sizeDone
            val outcome = when {
                results.any { it.outcome == BundleFileOutcome.Failed } -> BundleFileOutcome.Failed
                !matchesManifest(manifest, file) -> BundleFileOutcome.NotAsInManifest
                send(file.target, file.size, { manifest.getBundleFile(file).buffered() }) {
                    onProgress((before + it).toFloat() / totalSize)
                } -> BundleFileOutcome.Installed
                else -> BundleFileOutcome.Failed
            }
            sizeDone += file.size
            results += BundleFileResult(file.target, outcome)
        }
        return results
    }

    private fun matchesManifest(manifest: PbzManifestWrapper, file: PbzBundleFile): Boolean {
        val crc = try {
            if (file.target.isEmpty() || file.size <= 0) {
                null
            } else {
                manifest.getBundleFile(file).buffered().use { source ->
                    source.crc32(file.size)?.takeIf { source.exhausted() }
                }
            }
        } catch (e: Exception) {
            logger.w(e) { "Failed to read ${file.name}: ${e.message}" }
            null
        }
        val matches = crc != null && crc.toLong() == file.crc
        if (!matches) {
            logger.w {
                "Skipping ${file.name}: it does not match the manifest " +
                    "(target '${file.target}', size ${file.size}, CRC ${file.crc}, found CRC $crc)"
            }
        }
        return matches
    }

    private suspend fun send(
        target: String,
        size: Long,
        open: () -> Source,
        onSent: (Long) -> Unit,
    ): Boolean = try {
        open().use { source ->
            putBytesSession.beginSession(
                size = size.toUInt(),
                type = ObjectType.FILE,
                bank = 0u,
                filename = target,
                source = source,
                sendInstall = true,
            ).collect {
                if (it is PutBytesSession.SessionState.Sending) onSent(it.totalSent.toLong())
            }
        }
        logger.i { "Installed '$target' ($size bytes)" }
        true
    } catch (e: Exception) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        logger.w(e) { "Failed to install '$target': ${e.message}" }
        false
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    /** Runs [block] for as long as the watch stays connected, whatever the caller's dispatcher. */
    private suspend fun <T> onConnection(block: suspend () -> T): T {
        val work = connectionCoroutineScope.async { block() }
        try {
            return work.await()
        } catch (e: CancellationException) {
            work.cancel()
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("The watch disconnected", e)
        }
    }
}
