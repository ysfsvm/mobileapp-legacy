package coredevices.pebble.battery

import coredevices.database.LocalHeartbeatEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class BatteryPageTest {
    // 2026-11-14 00:00:00 UTC
    private val t0 = Instant.fromEpochSeconds(1_794_614_400)

    private fun record(
        hour: Int,
        soc: Double,
        numbers: Map<String, Double> = emptyMap(),
        watchface: String? = "TicToc",
    ) = BatteryRecord(
        entity = LocalHeartbeatEntity(
            serial = "S",
            fwVersion = null,
            tzOffsetMinutes = 0,
            receivedAt = (t0 + hour.hours).toEpochMilliseconds(),
            payload = ByteArray(0),
        ),
        decoded = DecodedHeartbeat.Known(
            version = 3,
            timestamp = t0 + hour.hours,
            buildId = "",
            numbers = mapOf(BatteryHistory.SOC to soc, BatteryHistory.VOLTAGE to 3.8) + numbers,
            texts = listOfNotNull(
                watchface?.let { "watchface_name" to it },
                watchface?.let { "watchface_uuid" to "{$it}" },
            ).toMap(),
        ),
    )

    private fun page(vararg records: BatteryRecord) = BatteryPage.from(BatteryHistory.from(records.toList()))

    @Test
    fun dropIsTheFallSinceTheRecordBefore() {
        val p = page(record(0, 80.0), record(1, 79.0), record(2, 76.5))
        assertNull(p.hours[0].dropPct)
        assertEquals(1.0, p.hours[1].dropPct)
        assertEquals(2.5, p.hours[2].dropPct)
        assertTrue(p.hours[2].has(HourTag.Drop))
        assertTrue(!p.hours[1].has(HourTag.Drop))
    }

    @Test
    fun noDropAcrossAGapOrWhileCharging() {
        val p = page(
            record(0, 80.0),
            record(3, 75.0),
            record(4, 90.0, mapOf(BatteryHistory.CHARGE_TIME_MS to 600_000.0)),
        )
        assertNull(p.hours[1].dropPct)
        assertNull(p.hours[2].dropPct)
        assertTrue(p.hours[2].has(HourTag.Charging))
    }

    @Test
    fun tagsFollowTheirThresholds() {
        val p = page(
            record(0, 80.0),
            record(1, 79.0, mapOf(
                "backlight_on_time_ms" to 60_000.0,
                "notification_received_count" to 10.0,
                "vibrator_on_time_ms" to 19_000.0,
                "task_cpu_app_pct" to 1.0,
                "ble_disconnect_rem_user_term_count" to 1.0,
            )),
            record(2, 78.0, mapOf(
                "backlight_on_time_ms" to 59_000.0,
                "phone_call_incoming_count" to 1.0,
                "connectivity_connected_time_ms" to 3_000_000.0,
                "connectivity_expected_time_ms" to 3_600_000.0,
            )),
        )
        val h1 = p.hours[1]
        assertTrue(h1.has(HourTag.Backlight) && h1.has(HourTag.Notifications) && h1.has(HourTag.App))
        assertTrue(!h1.has(HourTag.Vibration) && h1.has(HourTag.Disconnected))
        val h2 = p.hours[2]
        assertTrue(!h2.has(HourTag.Backlight) && h2.has(HourTag.Calls) && h2.has(HourTag.Disconnected))
    }

    @Test
    fun totalsSumTheWindow() {
        val p = page(
            record(0, 80.0, mapOf("backlight_on_time_ms" to 1_000.0, "connectivity_connected_time_ms" to 3_600_000.0,
                "connectivity_expected_time_ms" to 3_600_000.0)),
            record(1, 79.0, mapOf("backlight_on_time_ms" to 2_000.0, "button_pressed_count" to 5.0,
                "connectivity_connected_time_ms" to 1_800_000.0, "connectivity_expected_time_ms" to 3_600_000.0)),
            record(2, 90.0, mapOf(BatteryHistory.CHARGE_TIME_MS to 1.0)),
            record(3, 89.0),
            record(4, 95.0, mapOf(BatteryHistory.CHARGE_TIME_MS to 1.0)),
        )
        val t = p.totals(t0, t0 + 4.hours)
        assertEquals(5, t.hours)
        assertEquals(3.0, t.backlightS)
        assertEquals(5, t.buttons)
        assertEquals(75.0, t.connectedPct)
        assertEquals(2, t.charges)
        val sinceHour1 = p.totals(t0 + 1.hours, t0 + 4.hours)
        assertEquals(4, sinceHour1.hours)
        assertEquals(2.0, sinceHour1.backlightS)
    }

    @Test
    fun measuredDropCountsOnlyDischargingHours() {
        val p = page(
            record(0, 80.0),
            record(1, 78.0),
            record(2, 90.0, mapOf(BatteryHistory.CHARGE_TIME_MS to 1.0)),
            record(3, 89.0),
        )
        val d = p.drop(t0, t0 + 3.hours)
        assertEquals(3.0, d.dropPct)
        assertEquals(2.0, d.hours)
    }

    @Test
    fun watchfaceSpansSplitOnChangeAndGap() {
        val p = page(
            record(0, 80.0, watchface = "A"),
            record(1, 79.0, watchface = "A"),
            record(2, 78.0, watchface = "B"),
            record(5, 75.0, watchface = "B"),
            record(6, 74.0, watchface = null),
        )
        val spans = p.watchfaces(t0, t0 + 6.hours)
        assertEquals(listOf("A" to 2, "B" to 1, "B" to 1), spans.map { it.name to it.hours })
        assertEquals("{A}", spans[0].uuid)
    }

    @Test
    fun tipsComeFromTheLastRecordsSettings() {
        val p = page(
            record(0, 80.0),
            record(1, 79.0, mapOf(
                "settings_backlight_intensity_pct" to 75.0,
                "settings_backlight_timeout_s" to 3.0,
                "settings_health_tracking_enabled" to 1.0,
            )),
        )
        assertEquals(listOf(SettingTip.BacklightIntensity, SettingTip.HealthTracking), p.tips())
        assertEquals(emptyList(), page(record(0, 80.0)).tips())
    }
}
