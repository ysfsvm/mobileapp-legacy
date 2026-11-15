package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.disk.pbz.PbzFirmware
import io.rebble.libpebblecommon.metadata.pbz.manifest.PbzBundleFile
import io.rebble.libpebblecommon.metadata.pbz.manifest.PbzManifestWrapper
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.util.crc32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Puts files on the watch's filesystem: the ones a firmware bundle carries, or a single one. */
class BundleFileInstaller(
    private val putBytesSession: PutBytesSession,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
) : ConnectedPebble.BundleFiles {
    private val logger = Logger.withTag("BundleFileInstaller")

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
