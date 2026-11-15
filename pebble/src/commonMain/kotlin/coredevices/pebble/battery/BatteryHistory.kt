package coredevices.pebble.battery

import coredevices.database.LocalHeartbeatEntity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class BatteryRecord(
    val entity: LocalHeartbeatEntity,
    val decoded: DecodedHeartbeat,
) {
    val offset: UtcOffset get() = UtcOffset(minutes = entity.tzOffsetMinutes)
}

/** One decoded hourly record that carries a battery percentage. */
data class BatterySample(
    val time: Instant,
    val offset: UtcOffset,
    val socPct: Double,
    val voltage: Double?,
    val charging: Boolean,
    val record: DecodedHeartbeat.Known,
) {
    val localDate: LocalDate get() = time.toLocalDateTime(offset.asTimeZone()).date
}

data class ChargePeriod(val start: Instant, val end: Instant)

data class DayDrain(val date: LocalDate, val dropPct: Double, val hours: Double)

sealed interface ChargeEstimate {
    data class NotEnoughData(val dischargeHours: Double) : ChargeEstimate
    data class NoMeasurableDrop(val dischargeHours: Double) : ChargeEstimate
    data class Estimate(val pctPerDay: Double, val daysPerCharge: Double, val dischargeHours: Double) : ChargeEstimate
}

class BatteryHistory(
    val samples: List<BatterySample>,
    val chargePeriods: List<ChargePeriod>,
    val dailyDrain: List<DayDrain>,
    val estimate: ChargeEstimate,
    val unknownFormatCount: Int,
) {
    companion object {
        /** The percentage comes from a voltage curve on some watches; ignore smaller rises. */
        const val CHARGE_RISE_PCT = 1.0
        val MIN_DISCHARGE: Duration = 24.hours
        val ESTIMATE_WINDOW: Duration = 14.days
        private const val MIN_DROP_PCT = 1.0

        fun from(records: List<BatteryRecord>): BatteryHistory {
            val known = records.mapNotNull { r -> (r.decoded as? DecodedHeartbeat.Known)?.let { r to it } }
                .filter { (_, d) -> d.number(SOC) != null }
                .distinctBy { (_, d) -> d.timestamp }
                .sortedBy { (_, d) -> d.timestamp }
            val samples = mutableListOf<BatterySample>()
            for ((record, d) in known) {
                val soc = d.number(SOC)!!
                val prev = samples.lastOrNull()
                val charging = (d.number(CHARGE_TIME_MS) ?: 0.0) > 0.0 ||
                    (prev != null && soc - prev.socPct > CHARGE_RISE_PCT)
                samples += BatterySample(d.timestamp, record.offset, soc, d.number(VOLTAGE), charging, d)
            }
            return BatteryHistory(
                samples = samples,
                chargePeriods = chargePeriods(samples),
                dailyDrain = dailyDrain(samples),
                estimate = estimate(samples),
                unknownFormatCount = records.count { it.decoded is DecodedHeartbeat.Unknown },
            )
        }

        // A charging record covers the interval since the previous record.
        private fun chargePeriods(samples: List<BatterySample>): List<ChargePeriod> {
            val periods = mutableListOf<ChargePeriod>()
            samples.forEachIndexed { i, s ->
                if (!s.charging) return@forEachIndexed
                val start = samples.getOrNull(i - 1)?.time ?: s.time
                val last = periods.lastOrNull()
                if (last != null && last.end == start) {
                    periods[periods.lastIndex] = last.copy(end = s.time)
                } else {
                    periods += ChargePeriod(start, s.time)
                }
            }
            return periods
        }

        private fun dischargeSteps(samples: List<BatterySample>): List<Pair<BatterySample, BatterySample>> =
            samples.zipWithNext().filter { (_, b) -> !b.charging }

        private fun dailyDrain(samples: List<BatterySample>): List<DayDrain> =
            dischargeSteps(samples)
                .groupBy { (_, b) -> b.localDate }
                .map { (date, steps) ->
                    DayDrain(
                        date = date,
                        dropPct = steps.sumOf { (a, b) -> (a.socPct - b.socPct).coerceAtLeast(0.0) },
                        hours = steps.sumOf { (a, b) -> (b.time - a.time).inWholeMinutes / 60.0 },
                    )
                }
                .sortedBy { it.date }

        fun estimate(samples: List<BatterySample>): ChargeEstimate {
            val since = (samples.lastOrNull()?.time ?: return ChargeEstimate.NotEnoughData(0.0)) - ESTIMATE_WINDOW
            val steps = dischargeSteps(samples).filter { (a, _) -> a.time >= since }
            val hours = steps.sumOf { (a, b) -> (b.time - a.time).inWholeMinutes / 60.0 }
            if (hours < MIN_DISCHARGE.inWholeMinutes / 60.0) return ChargeEstimate.NotEnoughData(hours)
            val drop = steps.sumOf { (a, b) -> a.socPct - b.socPct }
            if (drop < MIN_DROP_PCT) return ChargeEstimate.NoMeasurableDrop(hours)
            val pctPerDay = drop / hours * 24
            return ChargeEstimate.Estimate(pctPerDay, 100 / pctPerDay, hours)
        }

        const val SOC = "battery_soc_pct"
        const val VOLTAGE = "battery_voltage"
        const val CHARGE_TIME_MS = "battery_charge_time_ms"
    }
}

/** What an hourly record says about where the power went. */
data class HourContext(
    val time: Instant,
    val offset: UtcOffset,
    val socPct: Double,
    val interval: Duration?,
    val cpuRunningPct: Double?,
    val cpuSleep0Pct: Double?,
    val cpuSleep1Pct: Double?,
    val backlightOnMs: Double?,
    val displayUpdates: Double?,
    val connectedMs: Double?,
    val bleIntervalBucket: BleIntervalBucket?,
    val charging: Boolean,
)

enum class BleIntervalBucket(val field: String) {
    Min("ble_conn_itvl_min_time_ms"),
    Mid("ble_conn_itvl_mid_time_ms"),
    Max("ble_conn_itvl_max_time_ms"),
    Other("ble_conn_itvl_other_time_ms"),
}

fun BatteryHistory.hourContexts(limit: Int): List<HourContext> {
    val start = (samples.size - limit).coerceAtLeast(0)
    return (samples.lastIndex downTo start).map { i ->
        val s = samples[i]
        val prev = samples.getOrNull(i - 1)
        val r = s.record
        val interval = prev?.let { s.time - it.time }?.takeIf { it <= 2.hours + 5.minutes }
        HourContext(
            time = s.time,
            offset = s.offset,
            socPct = s.socPct,
            interval = interval,
            cpuRunningPct = r.number("cpu_running_pct"),
            cpuSleep0Pct = r.number("cpu_sleep0_pct"),
            cpuSleep1Pct = r.number("cpu_sleep1_pct"),
            backlightOnMs = r.number("backlight_on_time_ms"),
            displayUpdates = r.number("display_update_count"),
            connectedMs = r.number("connectivity_connected_time_ms"),
            bleIntervalBucket = BleIntervalBucket.entries
                .mapNotNull { b -> r.number(b.field)?.takeIf { it > 0 }?.let { b to it } }
                .maxByOrNull { it.second }?.first,
            charging = s.charging,
        )
    }
}
