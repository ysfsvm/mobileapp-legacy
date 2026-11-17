package coredevices.pebble.battery

import coredevices.database.LocalHeartbeatEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class BatteryHistoryTest {
    // 2026-11-14 00:00:00 UTC
    private val t0 = Instant.fromEpochSeconds(1_794_614_400)

    private fun record(
        hour: Int,
        soc: Double,
        chargeTimeMs: Double = 0.0,
        tzOffsetMinutes: Int = 0,
    ) = BatteryRecord(
        entity = LocalHeartbeatEntity(
            serial = "S",
            fwVersion = null,
            tzOffsetMinutes = tzOffsetMinutes,
            receivedAt = (t0 + hour.hours).toEpochMilliseconds(),
            payload = ByteArray(0),
        ),
        decoded = DecodedHeartbeat.Known(
            version = 3,
            timestamp = t0 + hour.hours,
            buildId = "",
            numbers = mapOf(
                BatteryHistory.SOC to soc,
                BatteryHistory.VOLTAGE to 3.6 + soc / 400,
                BatteryHistory.CHARGE_TIME_MS to chargeTimeMs,
            ),
            texts = emptyMap(),
        ),
    )

    // 0.5 %/h with +-0.4 % of voltage-curve noise; the watch never reports a rise while discharging,
    // but the noise may still flatten or steepen single hours.
    private fun discharge(fromHour: Int, hours: Int, startSoc: Double) = (0..hours).map { h ->
        val noise = listOf(0.0, 0.4, -0.3, 0.2)[h % 4]
        record(fromHour + h, startSoc - h * 0.5 + noise)
    }

    @Test
    fun chargingHoursFormOnePeriod() {
        val before = discharge(0, 20, 40.0) // ends at 30 %
        val charging = listOf(record(21, 60.0), record(22, 85.0), record(23, 100.0))
        val after = discharge(24, 16, 99.5)
        val history = BatteryHistory.from(before + charging + after)
        assertEquals(listOf(ChargePeriod(t0 + 20.hours, t0 + 23.hours)), history.chargePeriods)
        assertTrue(history.samples.filter { it.time > t0 + 23.hours }.none { it.charging })
    }

    @Test
    fun chargeTimerMarksChargingWithoutARise() {
        val records = discharge(0, 4, 50.0) + record(5, 47.5, chargeTimeMs = 600_000.0)
        val history = BatteryHistory.from(records)
        assertTrue(history.samples.last().charging)
        assertEquals(listOf(ChargePeriod(t0 + 4.hours, t0 + 5.hours)), history.chargePeriods)
    }

    @Test
    fun duplicatesAndUnknownRecordsAreNotPlotted() {
        val unknown = BatteryRecord(record(0, 0.0).entity, DecodedHeartbeat.Unknown(4, 600))
        val history = BatteryHistory.from(listOf(record(0, 90.0), record(0, 90.0), record(1, 89.0), unknown))
        assertEquals(2, history.samples.size)
        assertEquals(1, history.unknownFormatCount)
    }
}
