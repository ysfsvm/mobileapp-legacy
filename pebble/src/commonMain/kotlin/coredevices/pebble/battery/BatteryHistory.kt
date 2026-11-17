package coredevices.pebble.battery

import coredevices.database.LocalHeartbeatEntity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toLocalDateTime
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

class BatteryHistory(
    val samples: List<BatterySample>,
    val chargePeriods: List<ChargePeriod>,
    val unknownFormatCount: Int,
) {
    companion object {
        /** The percentage comes from a voltage curve on some watches; ignore smaller rises. */
        const val CHARGE_RISE_PCT = 1.0

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

        const val SOC = "battery_soc_pct"
        const val VOLTAGE = "battery_voltage"
        const val CHARGE_TIME_MS = "battery_charge_time_ms"
    }
}
