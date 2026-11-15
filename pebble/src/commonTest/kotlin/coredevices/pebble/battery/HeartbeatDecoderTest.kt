package coredevices.pebble.battery

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

class HeartbeatDecoderTest {
    private val decoder = HeartbeatDecoder()

    private fun record(i: Int) = CAPTURED.copyOfRange(i * 569, (i + 1) * 569)

    private fun DecodedHeartbeat.Known.assertNumber(expected: Double, name: String) =
        assertEquals(expected, numbers.getValue(name), 1e-9, name)

    @Test
    fun layoutTableParses() {
        val layout = HeartbeatLayouts.known.single()
        assertEquals(3, layout.version)
        assertEquals(569, layout.size)
        // 124 rows: 3 header fields, 102 metrics and 19 scales folded into their metrics.
        assertEquals(105, layout.fields.size)
        val soc = layout.fields.single { it.name == "battery_soc_pct" }
        assertEquals(102, soc.offset)
        assertEquals(106, soc.scaleOffset)
    }

    @Test
    fun decodesFirstCapturedRecord() {
        val r = assertIs<DecodedHeartbeat.Known>(decoder.decode(record(0)))
        assertEquals(3, r.version)
        assertEquals(Instant.fromEpochSeconds(1794735530), r.timestamp)
        assertEquals("8924c18629a9b581144030327b6643f46d1517b2", r.buildId)
        assertEquals("v4.47.0-17-ga7f4018d9b", r.texts["fw_version"])
        assertEquals("TicToc", r.texts["watchface_name"])
        assertEquals("{8f3c8686-31a1-4f5f-91f5-01600c9bdc59}", r.texts["watchface_uuid"])
        r.assertNumber(79.0, "memory_pct_max")
        r.assertNumber(60.0, "uptime_s")
        r.assertNumber(68.63, "battery_soc_pct")
        r.assertNumber(0.0, "battery_soc_pct_drop")
        r.assertNumber(3.9, "battery_voltage")
        r.assertNumber(0.0, "battery_voltage_delta")
        r.assertNumber(0.0, "battery_tte_s")
        r.assertNumber(0.0, "battery_discharge_duration_ms")
        r.assertNumber(3441.0, "backlight_on_time_ms")
        r.assertNumber(46.0, "backlight_avg_intensity_pct")
        r.assertNumber(4.06, "cpu_running_pct")
        r.assertNumber(95.93, "cpu_sleep0_pct")
        r.assertNumber(0.0, "cpu_sleep1_pct")
        r.assertNumber(2.1, "task_cpu_kernel_main_pct")
        r.assertNumber(97.47, "task_cpu_idle_pct")
        r.assertNumber(9437.0, "watchface_time_ms")
        r.assertNumber(7296.0, "pfs_space_free_kb")
        r.assertNumber(32768.0, "flash_spi_erase_bytes")
        r.assertNumber(14011.0, "connectivity_connected_time_ms")
        r.assertNumber(9.0, "i2c_transfer_error_count")
        r.assertNumber(17.0, "display_update_count")
        r.assertNumber(164.0, "display_flush_time_ms")
        r.assertNumber(0.0, "battery_soc_pct_min")
    }

    @Test
    fun decodesSecondCapturedRecord() {
        val r = assertIs<DecodedHeartbeat.Known>(decoder.decode(record(1)))
        assertEquals(Instant.fromEpochSeconds(1794735627), r.timestamp)
        r.assertNumber(157.0, "uptime_s")
        r.assertNumber(74.0, "memory_pct_max")
        r.assertNumber(0.12, "cpu_running_pct")
        r.assertNumber(99.87, "cpu_sleep0_pct")
        r.assertNumber(99.93, "task_cpu_idle_pct")
        r.assertNumber(2.0, "accel_peek_count")
        r.assertNumber(97000.0, "connectivity_connected_time_ms")
        r.assertNumber(2.0, "display_update_count")
        r.assertNumber(15.0, "display_flush_time_ms")
        r.assertNumber(6.0, "i2c_transfer_error_count")
    }

    @Test
    fun signedScaledFieldIsSignExtended() {
        val bytes = record(0)
        // battery_voltage_delta = -12 / 1000
        bytes[120] = 0xf4.toByte(); bytes[121] = 0xff.toByte(); bytes[122] = 0xff.toByte(); bytes[123] = 0xff.toByte()
        val r = assertIs<DecodedHeartbeat.Known>(decoder.decode(bytes))
        r.assertNumber(-0.012, "battery_voltage_delta")
    }

    @Test
    fun unknownSizeIsNotDecoded() {
        val longer = record(0) + ByteArray(8)
        assertEquals(DecodedHeartbeat.Unknown(version = 3, size = 577), decoder.decode(longer))
        assertEquals(DecodedHeartbeat.Unknown(version = 3, size = 100), decoder.decode(record(0).copyOf(100)))
    }

    @Test
    fun unknownVersionIsNotDecoded() {
        val bytes = record(0)
        bytes[0] = 4
        assertEquals(DecodedHeartbeat.Unknown(version = 4, size = 569), decoder.decode(bytes))
        assertEquals(DecodedHeartbeat.Unknown(version = null, size = 0), decoder.decode(ByteArray(0)))
    }

    @Test
    fun layoutWithUnknownTypeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            HeartbeatLayout.parse(
                3,
                """
                native_heartbeat_record (13 bytes)
                     0     1  unsigned char8    version
                     1     8  unsigned64        timestamp
                     9     4  float32           something
                """.trimIndent(),
            )
        }
    }

    companion object {
        // Two records captured from the emulator over data logging (tag 87), back to back.
        val CAPTURED: ByteArray = Base64.decode(
            "A6p9+WoAAAAAiSTBhimptYEUQDAye2ZD9G0VF7JPAAAAUAAAAJgMAADsCgAAXAMAAAAIAAAACAAAAAAAAHY0LjQ3LjAtMTctZ2E3" +
                "ZjQwMThkOWIAAAAAAAAAAAAAAAAAAAA8AAAAzxoAAGQAAAAAAGQAPA8AAOgDAAAAAOgDAAAAAAAAAAAAAAAAcQ0AAC4AAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlgEAAGQAeSUAAGQAAAAAAGQAAAAAAGQA0gAA" +
                "AGQAGAAAAGQAAAAAAGQAEAAAAGQAAAAAAGQAAAAAAGQAAAAAAGQAAAAAAGQAEyYAAGQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAN0kAABUaWNUb2MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB7OGYzYzg2ODYtMzFhMS00ZjVmLTkx" +
                "ZjUtMDE2MDBjOWJkYzU5fQAAAAAAAAAAAACAHAAAAR0AAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAANwAAADIAAAADAAAAAQAAAAAAAAAAAAAAAAAAALs2AAC7NgAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAADoAwkAAAAAAAAAAAAAAAAAAABkAAAAAAARAAAApAAAAAAAAAADC375agAAAACJJMGGKam1gRRAMDJ7ZkP0bRUXskoA" +
                "AABPAAAAmAwAAOwKAABcAwAAAAgAAAAIAAAAAAAAdjQuNDcuMC0xNy1nYTdmNDAxOGQ5YgAAAAAAAAAAAAAAAAAAAJ0AAADPGgAA" +
                "ZAAAAAAAZAA8DwAA6AMAAAAA6AMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAMAAAAZAADJwAAZAAAAAAAZAAAAAAAZAACAAAAZAAEAAAAZAAAAAAAZAAAAAAAZAAAAAAAZAAAAAAA" +
                "ZAAAAAAAZAAAAAAAZAAJJwAAZAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA6HoBAFRpY1RvYwAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAHs4ZjNjODY4Ni0zMWExLTRmNWYtOTFmNS0wMTYwMGM5YmRjNTl9AAAAAAAAAAAAAAAcAAA1" +
                "AwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA3AAAA" +
                "MgAAAAMAAAABAAAAAAAAAAAAAAAAAAAA6HoBAOh6AQAAAAAAAAAAAAAAAAAAAAAAAAAAAOgDBgAAAAAAAAAAAAAAAAAAAGQAAAAA" +
                "AAIAAAAPAAAAAAAAAA=="
        )
    }
}
