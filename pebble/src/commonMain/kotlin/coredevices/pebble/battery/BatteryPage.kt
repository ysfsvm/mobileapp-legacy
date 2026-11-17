package coredevices.pebble.battery

import kotlinx.datetime.UtcOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What one hourly record measured. Every value comes from the watch; nothing is estimated. */
data class HourStats(
    val time: Instant,
    val offset: UtcOffset,
    val socPct: Double,
    val voltage: Double?,
    val charging: Boolean,
    /** Drop since the previous record, when that record is at most [BatteryPage.MAX_STEP] older. */
    val dropPct: Double?,
    val backlightS: Double,
    val backlightIntensityPct: Double?,
    val vibrationS: Double,
    val notifications: Int,
    val calls: Int,
    val callS: Double,
    val buttons: Int,
    val displayUpdates: Int,
    val appCpuPct: Double?,
    val cpuAwakePct: Double?,
    val connectedS: Double?,
    val expectedConnectedS: Double?,
    val disconnects: Int,
    val watchfaceName: String?,
    val watchfaceUuid: String?,
    val settings: Settings,
    /** Every field of the record, for the page's field list. */
    val raw: DecodedHeartbeat.Known,
) {
    fun has(tag: HourTag): Boolean = when (tag) {
        HourTag.Drop -> (dropPct ?: 0.0) >= BatteryPage.HEAVY_DROP_PCT
        HourTag.Backlight -> backlightS >= BatteryPage.HEAVY_BACKLIGHT_S
        HourTag.App -> (appCpuPct ?: 0.0) >= BatteryPage.HEAVY_APP_CPU_PCT
        HourTag.Notifications -> notifications >= BatteryPage.MANY_NOTIFICATIONS
        HourTag.Vibration -> vibrationS >= BatteryPage.LONG_VIBRATION_S
        HourTag.Calls -> calls > 0
        HourTag.Disconnected -> disconnects > 0 ||
            (connectedS != null && expectedConnectedS != null &&
                expectedConnectedS - connectedS >= BatteryPage.DISCONNECTED_S)
        HourTag.Charging -> charging
    }
}

/** Hour tags: each marks the hours whose record crosses one threshold. */
enum class HourTag { Drop, Backlight, App, Notifications, Vibration, Calls, Disconnected, Charging }

/** Consecutive hours with the same watchface. */
data class WatchfaceSpan(val name: String, val uuid: String?, val start: Instant, val end: Instant, val hours: Int)

/** Sums over the hours of a period, as the watch reported them. */
data class PeriodTotals(
    val hours: Int,
    val backlightS: Double,
    val vibrationS: Double,
    val notifications: Int,
    val calls: Int,
    val callS: Double,
    val buttons: Int,
    val displayUpdates: Int,
    val connectedPct: Double?,
    val disconnects: Int,
    val charges: Int,
)

/** The drop over discharging hours within a window, and how many hours it covers. */
data class MeasuredDrop(val dropPct: Double, val hours: Double)

/** A setting the last record reports that makes the watch use more power. */
enum class SettingTip { BacklightIntensity, BacklightTimeout, HealthTracking }

class BatteryPage(val hours: List<HourStats>) {
    fun window(start: Instant, end: Instant): List<HourStats> = hours.filter { it.time in start..end }

    fun totals(start: Instant, end: Instant): PeriodTotals {
        val h = window(start, end)
        val connected = h.mapNotNull { it.connectedS }.sum()
        val expected = h.mapNotNull { it.expectedConnectedS }.sum()
        return PeriodTotals(
            hours = h.size,
            backlightS = h.sumOf { it.backlightS },
            vibrationS = h.sumOf { it.vibrationS },
            notifications = h.sumOf { it.notifications },
            calls = h.sumOf { it.calls },
            callS = h.sumOf { it.callS },
            buttons = h.sumOf { it.buttons },
            displayUpdates = h.sumOf { it.displayUpdates },
            connectedPct = if (expected > 0) (connected / expected * 100).coerceAtMost(100.0) else null,
            disconnects = h.sumOf { it.disconnects },
            // A charge is a run of charging hours.
            charges = h.zipWithNext().count { (a, b) -> !a.charging && b.charging } +
                (if (h.firstOrNull()?.charging == true) 1 else 0),
        )
    }

    fun drop(start: Instant, end: Instant): MeasuredDrop {
        val h = window(start, end).filter { !it.charging && it.dropPct != null }
        return MeasuredDrop(h.sumOf { it.dropPct!! }, h.size.toDouble())
    }

    fun watchfaces(start: Instant, end: Instant): List<WatchfaceSpan> {
        val spans = mutableListOf<WatchfaceSpan>()
        for (h in window(start, end)) {
            val name = h.watchfaceName?.takeIf { it.isNotBlank() } ?: continue
            val last = spans.lastOrNull()
            spans += if (last != null && last.name == name && h.time - last.end <= MAX_STEP) {
                spans.removeAt(spans.lastIndex).copy(end = h.time, hours = last.hours + 1)
            } else {
                WatchfaceSpan(name, h.watchfaceUuid, h.time, h.time, 1)
            }
        }
        return spans
    }

    fun tips(): List<SettingTip> {
        val last = hours.lastOrNull() ?: return emptyList()
        return listOfNotNull(
            SettingTip.BacklightIntensity.takeIf { (last.settings.intensityPct ?: 0.0) > MEDIUM_BACKLIGHT_PCT },
            SettingTip.BacklightTimeout.takeIf { (last.settings.timeoutS ?: 0.0) > DEFAULT_TIMEOUT_S },
            SettingTip.HealthTracking.takeIf { last.settings.healthTracking == true },
        )
    }

    companion object {
        val MAX_STEP: Duration = 1.hours + 30.minutes
        const val HEAVY_DROP_PCT = 2.0
        const val HEAVY_BACKLIGHT_S = 60.0
        const val HEAVY_APP_CPU_PCT = 1.0
        const val MANY_NOTIFICATIONS = 10
        const val LONG_VIBRATION_S = 20.0
        const val DISCONNECTED_S = 5 * 60.0
        const val MEDIUM_BACKLIGHT_PCT = 50.0
        const val DEFAULT_TIMEOUT_S = 3.0

        fun from(history: BatteryHistory): BatteryPage {
            val samples = history.samples
            return BatteryPage(samples.mapIndexed { i, s ->
                val prev = samples.getOrNull(i - 1)
                val r = s.record
                fun n(field: String) = r.number(field)
                HourStats(
                    time = s.time,
                    offset = s.offset,
                    socPct = s.socPct,
                    voltage = s.voltage,
                    charging = s.charging,
                    dropPct = prev?.takeIf { !s.charging && s.time - it.time <= MAX_STEP }
                        ?.let { (it.socPct - s.socPct).coerceAtLeast(0.0) },
                    backlightS = (n("backlight_on_time_ms") ?: 0.0) / 1000,
                    backlightIntensityPct = n("backlight_avg_intensity_pct"),
                    vibrationS = (n("vibrator_on_time_ms") ?: 0.0) / 1000,
                    notifications = n("notification_received_count")?.toInt() ?: 0,
                    calls = n("phone_call_incoming_count")?.toInt() ?: 0,
                    callS = (n("phone_call_time_ms") ?: 0.0) / 1000,
                    buttons = n("button_pressed_count")?.toInt() ?: 0,
                    displayUpdates = n("display_update_count")?.toInt() ?: 0,
                    appCpuPct = n("task_cpu_app_pct"),
                    cpuAwakePct = n("cpu_running_pct"),
                    connectedS = n("connectivity_connected_time_ms")?.div(1000),
                    expectedConnectedS = n("connectivity_expected_time_ms")?.div(1000),
                    disconnects = DISCONNECT_FIELDS.sumOf { n(it)?.toInt() ?: 0 },
                    watchfaceName = r.texts["watchface_name"],
                    watchfaceUuid = r.texts["watchface_uuid"],
                    settings = Settings(
                        intensityPct = n("settings_backlight_intensity_pct"),
                        timeoutS = n("settings_backlight_timeout_s"),
                        healthTracking = n("settings_health_tracking_enabled")?.let { it > 0 },
                    ),
                    raw = r,
                )
            })
        }

        private val DISCONNECT_FIELDS = listOf(
            "ble_disconnect_conn_spvn_tmo_count",
            "ble_disconnect_rem_user_term_count",
            "ble_disconnect_conn_term_local_count",
            "ble_disconnect_lmp_ll_rsp_tmo_count",
            "ble_disconnect_conn_establishment_count",
            "ble_disconnect_other_count",
        )
    }
}

/** The settings an hourly record reports. */
data class Settings(val intensityPct: Double?, val timeoutS: Double?, val healthTracking: Boolean?)
