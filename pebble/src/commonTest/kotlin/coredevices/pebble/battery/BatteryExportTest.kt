package coredevices.pebble.battery

import coredevices.database.LocalHeartbeatEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryExportTest {
    private val decoder = HeartbeatDecoder()

    private fun record(payload: ByteArray) = BatteryRecord(
        LocalHeartbeatEntity(
            serial = "Q123456789AB",
            fwVersion = "v4.47.0",
            tzOffsetMinutes = 180,
            receivedAt = 1_794_735_600_000,
            payload = payload,
        ),
        decoder.decode(payload),
    )

    @Test
    fun exportsDecodedValuesAndTheRawRecord() {
        val payload = HeartbeatDecoderTest.CAPTURED.copyOfRange(0, 569)
        val unknown = payload.copyOf(600)
        val json = Json.parseToJsonElement(batteryExportJson(listOf(record(payload), record(unknown)))).jsonArray

        val known = json[0].jsonObject
        assertEquals("known", known.getValue("format").jsonPrimitive.content)
        assertEquals("Q123456789AB", known.getValue("serial").jsonPrimitive.content)
        assertEquals("2026-11-15T09:40:00Z", known.getValue("received_at").jsonPrimitive.content)
        assertEquals(180, known.getValue("tz_offset_minutes").jsonPrimitive.int)
        assertEquals("2026-11-15T09:38:50Z", known.getValue("timestamp").jsonPrimitive.content)
        assertEquals(68.63, known.getValue("metrics").jsonObject.getValue("battery_soc_pct").jsonPrimitive.double, 1e-9)
        assertEquals("TicToc", known.getValue("texts").jsonObject.getValue("watchface_name").jsonPrimitive.content)
        assertTrue(payload.contentEquals(Base64.decode(known.getValue("raw").jsonPrimitive.content)))

        val other = json[1].jsonObject
        assertEquals("unknown", other.getValue("format").jsonPrimitive.content)
        assertEquals(600, other.getValue("size").jsonPrimitive.int)
        assertFalse("metrics" in other)
        assertTrue(unknown.contentEquals(Base64.decode(other.getValue("raw").jsonPrimitive.content)))
    }
}
