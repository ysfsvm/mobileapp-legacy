package io.rebble.libpebblecommon.disk.pbz

import io.rebble.libpebblecommon.util.Crc32Calculator
import kotlinx.io.files.Path
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun stm32Crc(bytes: ByteArray): Long =
    Crc32Calculator().apply { addBytes(bytes.asUByteArray()) }.finalize().toLong()

class TestBundleFile(
    val name: String,
    val target: String,
    val content: ByteArray,
    val size: Int = content.size,
    val crc: Long = stm32Crc(content),
)

/** Writes a single-manifest firmware bundle to a temporary file. */
fun writeTestPbz(
    versionTag: String = "v4.47.0-test",
    files: List<TestBundleFile>? = null,
): Path {
    val firmware = "firmware".encodeToByteArray()
    val filesJson = files?.joinToString(prefix = ""","files":[""", postfix = "]") {
        """{"name":"${it.name}","target":"${it.target}","size":${it.size},"crc":${it.crc}}"""
    }.orEmpty()
    val manifest = """{"manifestVersion":2,"generatedAt":1,"firmware":{"name":"pebbleos.bin",""" +
        """"type":"normal","timestamp":1,"commit":"0","hwrev":"snowy_dvt","size":${firmware.size},""" +
        """"crc":${stm32Crc(firmware)},"versionTag":"$versionTag"}$filesJson,"type":"firmware"}"""
    val pbz = File.createTempFile("test_bundle", ".pbz").apply { deleteOnExit() }
    ZipOutputStream(pbz.outputStream()).use { zip ->
        fun entry(name: String, content: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        entry("manifest.json", manifest.encodeToByteArray())
        entry("pebbleos.bin", firmware)
        files?.forEach { entry(it.name, it.content) }
    }
    return Path(pbz.absolutePath)
}
