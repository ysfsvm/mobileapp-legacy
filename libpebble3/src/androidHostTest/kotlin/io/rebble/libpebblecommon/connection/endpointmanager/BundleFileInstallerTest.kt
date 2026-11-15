package io.rebble.libpebblecommon.connection.endpointmanager

import TestPebbleProtocolHandler
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.disk.pbz.TestBundleFile
import io.rebble.libpebblecommon.disk.pbz.stm32Crc
import io.rebble.libpebblecommon.disk.pbz.writeTestPbz
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.packets.PutBytesAbort
import io.rebble.libpebblecommon.packets.PutBytesCommit
import io.rebble.libpebblecommon.packets.PutBytesInit
import io.rebble.libpebblecommon.packets.PutBytesInstall
import io.rebble.libpebblecommon.packets.PutBytesOutgoingPacket
import io.rebble.libpebblecommon.packets.PutBytesPut
import io.rebble.libpebblecommon.packets.PutBytesResponse
import io.rebble.libpebblecommon.packets.PutBytesResult
import io.rebble.libpebblecommon.services.PutBytesService
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Acknowledges every PutBytes packet, except the ones [nack] picks. */
class FakePutBytesWatch(private val nack: (PutBytesOutgoingPacket) -> Boolean = { false }) {
    val packets = mutableListOf<PutBytesOutgoingPacket>()
    val handler = TestPebbleProtocolHandler { packet ->
        packet as PutBytesOutgoingPacket
        packets += packet
        if (packet !is PutBytesAbort) {
            receivePacket(PutBytesResponse().apply {
                result.set(if (nack(packet)) PutBytesResult.NACK.value else PutBytesResult.ACK.value)
                cookie.set(COOKIE)
            })
        }
    }

    fun putBytesSession(scope: TestScope): PutBytesSession {
        val service = PutBytesService(handler, ConnectionCoroutineScope(scope.backgroundScope.coroutineContext))
        service.init()
        scope.testScheduler.runCurrent()
        return PutBytesSession(service)
    }

    /** Asserts that exactly these files were sent, each as one complete file transfer. */
    fun assertFilesSent(vararg files: Pair<String, ByteArray>) {
        val remaining = packets.toMutableList()
        for ((target, content) in files) {
            val init = assertIs<PutBytesInit>(remaining.removeAt(0))
            assertEquals(content.size.toUInt(), init.objectSize.get())
            assertEquals(ObjectType.FILE.value, init.objectType.get())
            assertEquals(0u.toUByte(), init.bank.get())
            assertTrue(init.filename.present)
            assertEquals(target, init.filename.value.get())
            assertFalse(init.resumeMagic.present)
            val sent = mutableListOf<Byte>()
            while (remaining.first() is PutBytesPut) {
                val put = remaining.removeAt(0) as PutBytesPut
                assertEquals(COOKIE, put.cookie.get())
                sent += put.payload.get().asByteArray().toList()
            }
            assertContentEquals(content, sent.toByteArray())
            val commit = assertIs<PutBytesCommit>(remaining.removeAt(0))
            assertEquals(stm32Crc(content), commit.objectCrc.get().toLong())
            assertEquals(COOKIE, assertIs<PutBytesInstall>(remaining.removeAt(0)).cookie.get())
        }
        assertEquals(emptyList(), remaining)
    }

    companion object {
        const val COOKIE = 7u
    }
}

class BundleFileInstallerTest {
    private val pack = ByteArray(4500) { (it * 7).toByte() }
    private val other = ByteArray(10) { it.toByte() }
    private val emoji = TestBundleFile("emoji_snowy_dvt.pbpack", "emoji", pack)

    private fun TestScope.installer(watch: FakePutBytesWatch) = BundleFileInstaller(
        watch.putBytesSession(this),
        ConnectionCoroutineScope(backgroundScope.coroutineContext),
    )

    private fun installed(target: String) = BundleFileResult(target, BundleFileOutcome.Installed)
    private fun skipped(target: String) = BundleFileResult(target, BundleFileOutcome.NotAsInManifest)
    private fun failed(target: String) = BundleFileResult(target, BundleFileOutcome.Failed)

    @Test
    fun sendsEachFileOfTheBundle() = runTest {
        val watch = FakePutBytesWatch()
        val progress = mutableListOf<Float>()
        val results = installer(watch).installBundleFiles(
            writeTestPbz(files = listOf(emoji, TestBundleFile("other.bin", "other", other)))
        ) { progress += it }

        assertEquals(listOf(installed("emoji"), installed("other")), results)
        watch.assertFilesSent("emoji" to pack, "other" to other)
        assertEquals(progress.sorted(), progress)
        assertEquals(1.0f, progress.last())
    }

    @Test
    fun bundleWithoutFilesSendsNothing() = runTest {
        val watch = FakePutBytesWatch()
        assertEquals(emptyList(), installer(watch).installBundleFiles(writeTestPbz()))
        assertEquals(emptyList(), watch.packets)
    }

    @Test
    fun unreadableBundleSendsNothing() = runTest {
        val watch = FakePutBytesWatch()
        val notABundle = File.createTempFile("not_a_bundle", ".pbz").apply { deleteOnExit() }
        assertEquals(emptyList(), installer(watch).installBundleFiles(Path(notABundle.absolutePath)))
        assertEquals(emptyList(), watch.packets)
    }

    @Test
    fun fileNotAsInManifestIsSkipped() = runTest {
        val watch = FakePutBytesWatch()
        val results = installer(watch).installBundleFiles(
            writeTestPbz(
                files = listOf(
                    TestBundleFile("wrong_crc.bin", "a", pack, crc = stm32Crc(pack) + 1),
                    TestBundleFile("shorter_than_listed.bin", "b", pack, size = pack.size + 1),
                    TestBundleFile("longer_than_listed.bin", "c", pack, size = pack.size - 4),
                    TestBundleFile("no_target.bin", "", pack),
                    TestBundleFile("other.bin", "other", other),
                )
            )
        )

        assertEquals(listOf(skipped("a"), skipped("b"), skipped("c"), skipped(""), installed("other")), results)
        watch.assertFilesSent("other" to other)
    }

    @Test
    fun refusedFileFailsAndStopsTheRest() = runTest {
        val watch = FakePutBytesWatch(nack = { it is PutBytesCommit })
        val results = installer(watch).installBundleFiles(
            writeTestPbz(files = listOf(emoji, TestBundleFile("other.bin", "other", other)))
        )

        assertEquals(listOf(failed("emoji"), failed("other")), results)
        assertEquals(1, watch.packets.count { it is PutBytesInit })
        assertEquals(0, watch.packets.count { it is PutBytesInstall })
    }

    @Test
    fun silentWatchFails() = runTest {
        val scope = ConnectionCoroutineScope(backgroundScope.coroutineContext)
        val session = PutBytesSession(PutBytesService(TestPebbleProtocolHandler {}, scope))
        val results = BundleFileInstaller(session, scope).installBundleFiles(writeTestPbz(files = listOf(emoji)))

        assertEquals(listOf(failed("emoji")), results)
        assertEquals(null, session.currentSession.value)
    }

    @Test
    fun sendsSingleFileUnderGivenName() = runTest {
        val watch = FakePutBytesWatch()
        val file = File.createTempFile("pack", ".pbpack").apply {
            deleteOnExit()
            writeBytes(pack)
        }
        val progress = mutableListOf<Float>()
        assertTrue(installer(watch).installFile(Path(file.absolutePath), "emoji") { progress += it })
        watch.assertFilesSent("emoji" to pack)
        assertEquals(1.0f, progress.last())
    }

    @Test
    fun emptySingleFileIsNotSent() = runTest {
        val watch = FakePutBytesWatch()
        val file = File.createTempFile("empty", ".pbpack").apply { deleteOnExit() }
        assertFalse(installer(watch).installFile(Path(file.absolutePath), "emoji"))
        assertFalse(installer(watch).installFile(Path(file.absolutePath + ".missing"), "emoji"))
        assertEquals(emptyList(), watch.packets)
    }
}
