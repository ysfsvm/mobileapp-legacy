package coredevices.pebble.battery

import coredevices.database.LocalHeartbeatEntity
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun lessThanADayOfDischargeGivesNoEstimate() {
        val history = BatteryHistory.from(discharge(0, 12, 90.0))
        val estimate = assertIs<ChargeEstimate.NotEnoughData>(history.estimate)
        assertEquals(12.0, estimate.dischargeHours, 1e-9)
    }

    @Test
    fun steadyDischargeGivesDaysPerCharge() {
        val history = BatteryHistory.from(discharge(0, 48, 95.0))
        val estimate = assertIs<ChargeEstimate.Estimate>(history.estimate)
        assertEquals(48.0, estimate.dischargeHours, 1e-9)
        // 0.5 %/h = 12 %/day, 8.3 days; the noise at both ends is 0.0.
        assertEquals(12.0, estimate.pctPerDay, 1e-9)
        assertEquals(100 / 12.0, estimate.daysPerCharge, 1e-9)
        assertTrue(history.chargePeriods.isEmpty())
    }

    @Test
    fun chargingHoursAreLeftOutOfTheEstimate() {
        val before = discharge(0, 20, 40.0) // ends at 30 %
        val charging = listOf(record(21, 60.0), record(22, 85.0), record(23, 100.0))
        val after = discharge(24, 16, 99.5)
        val history = BatteryHistory.from(before + charging + after)
        assertEquals(listOf(ChargePeriod(t0 + 20.hours, t0 + 23.hours)), history.chargePeriods)
        val estimate = assertIs<ChargeEstimate.Estimate>(history.estimate)
        // 20 h before, 16 h after and the hour from 100 % at 23:00 to 99.5 % at 24:00.
        assertEquals(37.0, estimate.dischargeHours, 1e-9)
        assertEquals(12.0, estimate.pctPerDay, 1e-9)
    }

    @Test
    fun chargeTimerMarksChargingWithoutARise() {
        val records = discharge(0, 4, 50.0) + record(5, 47.5, chargeTimeMs = 600_000.0)
        val history = BatteryHistory.from(records)
        assertTrue(history.samples.last().charging)
        assertEquals(listOf(ChargePeriod(t0 + 4.hours, t0 + 5.hours)), history.chargePeriods)
    }

    @Test
    fun flatBatteryIsNoMeasurableDrop() {
        val history = BatteryHistory.from((0..30).map { record(it, 68.63) })
        assertIs<ChargeEstimate.NoMeasurableDrop>(history.estimate)
    }

    @Test
    fun dailyDrainUsesTheLocalDate() {
        // UTC+3: hours 0..20 UTC are 03:00..23:00 on the 14th, hours 21..30 fall on the 15th.
        val records = (0..30).map { record(it, 90.0 - it, tzOffsetMinutes = 180) }
        val days = BatteryHistory.from(records).dailyDrain
        assertEquals(
            listOf(
                DayDrain(LocalDate(2026, 11, 14), 20.0, 20.0),
                DayDrain(LocalDate(2026, 11, 15), 10.0, 10.0),
            ),
            days,
        )
    }

    @Test
    fun duplicatesAndUnknownRecordsAreNotPlotted() {
        val unknown = BatteryRecord(record(0, 0.0).entity, DecodedHeartbeat.Unknown(4, 600))
        val history = BatteryHistory.from(listOf(record(0, 90.0), record(0, 90.0), record(1, 89.0), unknown))
        assertEquals(2, history.samples.size)
        assertEquals(1, history.unknownFormatCount)
    }

    @Test
    fun hourContextsAreNewestFirst() {
        val contexts = BatteryHistory.from(discharge(0, 5, 80.0)).hourContexts(3)
        assertEquals(listOf(t0 + 5.hours, t0 + 4.hours, t0 + 3.hours), contexts.map { it.time })
        assertEquals(1.hours, contexts.first().interval)
    }
}
