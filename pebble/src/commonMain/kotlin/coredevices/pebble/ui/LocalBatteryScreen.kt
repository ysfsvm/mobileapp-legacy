package coredevices.pebble.ui

import PlatformShareLauncher
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import coreapp.pebble.generated.resources.Res
import coreapp.pebble.generated.resources.battery_local_accuracy
import coreapp.pebble.generated.resources.battery_local_ble_max
import coreapp.pebble.generated.resources.battery_local_ble_mid
import coreapp.pebble.generated.resources.battery_local_ble_min
import coreapp.pebble.generated.resources.battery_local_ble_other
import coreapp.pebble.generated.resources.battery_local_charging
import coreapp.pebble.generated.resources.battery_local_charging_legend
import coreapp.pebble.generated.resources.battery_local_chart_level
import coreapp.pebble.generated.resources.battery_local_chart_voltage
import coreapp.pebble.generated.resources.battery_local_daily
import coreapp.pebble.generated.resources.battery_local_empty
import coreapp.pebble.generated.resources.battery_local_estimate
import coreapp.pebble.generated.resources.battery_local_estimate_detail
import coreapp.pebble.generated.resources.battery_local_export
import coreapp.pebble.generated.resources.battery_local_export_failed
import coreapp.pebble.generated.resources.battery_local_hour_ble
import coreapp.pebble.generated.resources.battery_local_hour_cpu
import coreapp.pebble.generated.resources.battery_local_hour_screen
import coreapp.pebble.generated.resources.battery_local_hourly
import coreapp.pebble.generated.resources.battery_local_last_record
import coreapp.pebble.generated.resources.battery_local_no_drop
import coreapp.pebble.generated.resources.battery_local_not_enough
import coreapp.pebble.generated.resources.battery_local_partial_day
import coreapp.pebble.generated.resources.battery_local_title
import coreapp.pebble.generated.resources.battery_local_unknown_format
import coredevices.pebble.battery.BatteryHistory
import coredevices.pebble.battery.BatterySample
import coredevices.pebble.battery.BleIntervalBucket
import coredevices.pebble.battery.ChargeEstimate
import coredevices.pebble.battery.ChargePeriod
import coredevices.pebble.battery.DayDrain
import coredevices.pebble.battery.HourContext
import coredevices.pebble.battery.LocalBatteryRepository
import coredevices.pebble.battery.hourContexts
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val ChargeShadeColor = Color(0xFF4CAF50).copy(alpha = 0.25f)
private val ChartWindow = 7.days
private val MaxLineGap = 2.hours + 30.minutes
private const val DailyRows = 14
private const val HourRows = 24
private const val FullDayHours = 20.0

@Composable
fun LocalBatteryScreen(topBarParams: TopBarParams) {
    val repository = koinInject<LocalBatteryRepository>()
    val records by remember { repository.records() }.collectAsState(null)
    val shareLauncher = koinInject<PlatformShareLauncher>()
    val scope = rememberCoroutineScope()
    val title = stringResource(Res.string.battery_local_title)
    val exportLabel = stringResource(Res.string.battery_local_export)
    val exportFailed = stringResource(Res.string.battery_local_export_failed)
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.title(title)
        topBarParams.actions {
            IconButton(onClick = {
                scope.launch {
                    try {
                        shareLauncher.share(null, repository.exportToFile(), "application/json")
                    } catch (e: Exception) {
                        Logger.withTag("LocalBatteryScreen").w(e) { "Export failed" }
                        topBarParams.showSnackbar(exportFailed)
                    }
                }
            }) {
                Icon(Icons.Default.Share, contentDescription = exportLabel)
            }
        }
    }

    val all = records
    if (all == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    // Rows come oldest first, so the last serial is the watch heard from most recently.
    val serials = all.map { it.entity.serial }.distinct().reversed()
    var selected by remember { mutableStateOf<String?>(null) }
    val serial = selected?.takeIf { it in serials } ?: serials.firstOrNull()
    if (serial == null) {
        CenteredMessage(stringResource(Res.string.battery_local_empty))
        return
    }
    val history = remember(all, serial) { BatteryHistory.from(all.filter { it.entity.serial == serial }) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (serials.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                serials.forEach { s ->
                    FilterChip(selected = s == serial, onClick = { selected = s }, label = { Text(s) })
                }
            }
        }
        val last = history.samples.lastOrNull()
        if (last == null) {
            Text(
                stringResource(Res.string.battery_local_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            SummaryCard(last, history.estimate)
            val end = last.time
            val start = end - ChartWindow
            BatteryCard(stringResource(Res.string.battery_local_chart_level)) {
                BatteryLineChart(
                    history.samples, { it.socPct }, 0.0, 100.0, history.chargePeriods, start, end, last.offset,
                    MaterialTheme.colorScheme.primary, unit = "%",
                )
                Text(
                    stringResource(Res.string.battery_local_charging_legend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val volts = history.samples.filter { it.time >= start }.mapNotNull { it.voltage }
            if (volts.isNotEmpty()) {
                BatteryCard(stringResource(Res.string.battery_local_chart_voltage)) {
                    BatteryLineChart(
                        history.samples, { it.voltage }, volts.min() - 0.05, volts.max() + 0.05,
                        history.chargePeriods, start, end, last.offset,
                        MaterialTheme.colorScheme.tertiary, unit = " V",
                    )
                }
            }
            if (history.dailyDrain.isNotEmpty()) {
                BatteryCard(stringResource(Res.string.battery_local_daily)) {
                    DailyDrainRows(history.dailyDrain.takeLast(DailyRows).reversed())
                }
            }
            BatteryCard(stringResource(Res.string.battery_local_hourly)) {
                history.hourContexts(HourRows).forEach { HourRow(it) }
            }
        }
        if (history.unknownFormatCount > 0) {
            Text(
                stringResource(Res.string.battery_local_unknown_format, history.unknownFormatCount.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BatteryCard(title: String, content: @Composable () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SummaryCard(last: BatterySample, estimate: ChargeEstimate) {
    val time = last.time.toLocalDateTime(last.offset.asTimeZone())
    val fw = last.record.texts["fw_version"].orEmpty()
    BatteryCard(stringResource(Res.string.battery_local_title)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${last.socPct.fmt(0)}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            last.voltage?.let { Text("${it.fmt(2)} V", style = MaterialTheme.typography.titleMedium) }
        }
        Text(
            stringResource(Res.string.battery_local_last_record, "${time.date} ${time.hm()}", fw),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        when (estimate) {
            is ChargeEstimate.Estimate -> {
                Text(
                    stringResource(Res.string.battery_local_estimate, estimate.daysPerCharge.fmt(1)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        Res.string.battery_local_estimate_detail,
                        "${estimate.pctPerDay.fmt(1)}%",
                        estimate.dischargeHours.fmt(0),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is ChargeEstimate.NotEnoughData -> Text(
                stringResource(Res.string.battery_local_not_enough, estimate.dischargeHours.fmt(0)),
                style = MaterialTheme.typography.bodyMedium,
            )
            is ChargeEstimate.NoMeasurableDrop -> Text(
                stringResource(Res.string.battery_local_no_drop, estimate.dischargeHours.fmt(0)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            stringResource(Res.string.battery_local_accuracy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatteryLineChart(
    samples: List<BatterySample>,
    value: (BatterySample) -> Double?,
    yMin: Double,
    yMax: Double,
    charges: List<ChargePeriod>,
    start: Instant,
    end: Instant,
    offset: UtcOffset,
    color: Color,
    unit: String,
) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val overlay = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val decimals = if (yMax - yMin < 10) 2 else 0
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val labelH = 14.dp.toPx()
        val chartH = size.height - labelH
        val span = (end - start).inWholeSeconds.coerceAtLeast(1).toFloat()
        val range = (yMax - yMin).takeIf { it > 0 } ?: 1.0
        fun x(t: Instant) = ((t - start).inWholeSeconds / span * size.width).coerceIn(0f, size.width)
        fun y(v: Double) = chartH - ((v - yMin) / range).toFloat().coerceIn(0f, 1f) * chartH

        drawRect(overlay, Offset.Zero, Size(size.width, chartH))
        for (c in charges) {
            if (c.end < start) continue
            val x0 = x(maxOf(c.start, start))
            drawRect(ChargeShadeColor, Offset(x0, 0f), Size((x(c.end) - x0).coerceAtLeast(2.dp.toPx()), chartH))
        }
        val tz = offset.asTimeZone()
        var day = start.toLocalDateTime(tz).date.plus(DatePeriod(days = 1))
        while (true) {
            val midnight = day.atStartOfDayIn(tz)
            if (midnight > end) break
            val dx = x(midnight)
            drawLine(grid, Offset(dx, 0f), Offset(dx, chartH), 1.dp.toPx())
            drawText(tm.measure(day.day.toString(), labelStyle), topLeft = Offset(dx + 2.dp.toPx(), chartH + 1.dp.toPx()))
            day = day.plus(DatePeriod(days = 1))
        }

        val path = Path()
        var prev: Instant? = null
        var lastPoint: Offset? = null
        for (s in samples) {
            if (s.time < start) continue
            val v = value(s) ?: continue
            val p = Offset(x(s.time), y(v))
            if (prev == null || s.time - prev > MaxLineGap) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            prev = s.time
            lastPoint = p
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        lastPoint?.let { drawCircle(color, 3.dp.toPx(), it) }

        drawText(tm.measure("${yMax.fmt(decimals)}$unit", labelStyle), topLeft = Offset(2.dp.toPx(), 1.dp.toPx()))
        val minLabel = tm.measure("${yMin.fmt(decimals)}$unit", labelStyle)
        drawText(minLabel, topLeft = Offset(2.dp.toPx(), chartH - minLabel.size.height - 1.dp.toPx()))
    }
}

@Composable
private fun DailyDrainRows(days: List<DayDrain>) {
    val maxDrop = days.maxOf { it.dropPct }.coerceAtLeast(1.0)
    val barColor = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    days.forEach { d ->
        val partial = d.hours < FullDayHours
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.date.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
            Canvas(Modifier.weight(1f).height(10.dp)) {
                drawRect(track, Offset.Zero, size)
                val w = (d.dropPct / maxDrop).toFloat().coerceIn(0f, 1f) * size.width
                drawRect(if (partial) barColor.copy(alpha = 0.4f) else barColor, Offset.Zero, Size(w, size.height))
            }
            Spacer(Modifier.width(8.dp))
            Text("${d.dropPct.fmt(1)}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp))
        }
        if (partial) {
            Text(
                stringResource(Res.string.battery_local_partial_day, d.hours.fmt(0)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 84.dp),
            )
        }
    }
}

@Composable
private fun HourRow(h: HourContext) {
    val time = h.time.toLocalDateTime(h.offset.asTimeZone())
    val dash = "–"
    fun pct(v: Double?) = v?.let { "${it.fmt(1)}%" } ?: dash
    val bucket = when (h.bleIntervalBucket) {
        BleIntervalBucket.Min -> stringResource(Res.string.battery_local_ble_min)
        BleIntervalBucket.Mid -> stringResource(Res.string.battery_local_ble_mid)
        BleIntervalBucket.Max -> stringResource(Res.string.battery_local_ble_max)
        BleIntervalBucket.Other -> stringResource(Res.string.battery_local_ble_other)
        null -> dash
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${time.date} ${time.hm()}", style = MaterialTheme.typography.labelLarge)
            Text("${h.socPct.fmt(0)}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (h.charging) {
                Text(
                    stringResource(Res.string.battery_local_charging),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val detail = MaterialTheme.typography.bodySmall
        val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            stringResource(Res.string.battery_local_hour_cpu, pct(h.cpuRunningPct), pct(h.cpuSleep0Pct), pct(h.cpuSleep1Pct)),
            style = detail, color = detailColor,
        )
        Text(
            stringResource(
                Res.string.battery_local_hour_screen,
                h.backlightOnMs?.let { (it / 1000).fmt(0) } ?: dash,
                h.displayUpdates?.fmt(0) ?: dash,
            ),
            style = detail, color = detailColor,
        )
        Text(
            stringResource(Res.string.battery_local_hour_ble, h.connectedMs?.let { (it / 60_000).fmt(0) } ?: dash, bucket),
            style = detail, color = detailColor,
        )
    }
}

private fun LocalDateTime.hm(): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private fun Double.fmt(decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (this * factor).roundToLong()
    val sign = if (scaled < 0) "-" else ""
    val whole = abs(scaled) / factor
    if (decimals == 0) return "$sign$whole"
    val fraction = (abs(scaled) % factor).toString().padStart(decimals, '0')
    return "$sign$whole.$fraction"
}
