package coredevices.pebble.ui

import PlatformShareLauncher
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import coreapp.pebble.generated.resources.Res
import coreapp.pebble.generated.resources.battery_local_empty
import coreapp.pebble.generated.resources.battery_local_export
import coreapp.pebble.generated.resources.battery_local_export_failed
import coreapp.pebble.generated.resources.battery_local_title
import coreapp.pebble.generated.resources.battery_local_unknown_format
import coredevices.pebble.battery.BatteryHistory
import coredevices.pebble.battery.BatteryPage
import coredevices.pebble.battery.HourStats
import coredevices.pebble.battery.HourTag
import coredevices.pebble.battery.LocalBatteryRepository
import coredevices.pebble.battery.PeriodTotals
import coredevices.pebble.battery.SettingTip
import coredevices.pebble.battery.WatchfaceImpact
import coredevices.pebble.battery.WatchfaceImpactLookup
import coredevices.pebble.battery.WatchfaceSpan
import io.rebble.libpebblecommon.connection.KnownPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// Drawn dark whatever the app theme is, like Core's battery page. Charts sit on a neutral surface so
// each measure keeps its own hue; teal is only the accent.
private object Palette {
    val Page = Color(0xFF000000)
    val Card = Color(0xFF141413)
    val CardBorder = Color(0xFF2A2A28)
    val Accent = Color(0xFF0F6E64)
    val AccentText = Color(0xFF5EEAD4)
    val ChartBack = Color(0xFF1A1A19)
    val Voltage = Color(0xFFE6E6E6)
    val Grid = Color(0x2EFFFFFF)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFFA3A39F)
    val Faint = Color(0xFF6F6F6B)
    val ChipBorder = Color(0xFF3A3A37)
    val Track = Color(0xFF2A2A28)
    val Charging = Color(0x24FFFFFF)
    val Tooltip = Color(0xF02C2C2A)
}

// Battery level as a status: good, warning, critical.
private val LevelGood = Color(0xFF0CA30C)
private val LevelLow = Color(0xFFFAB219)
private val LevelCritical = Color(0xFFD03B3B)
private fun levelColor(pct: Double) = when {
    pct >= 50 -> LevelGood
    pct >= 20 -> LevelLow
    else -> LevelCritical
}

// One hue per measure, the same in the activity rows, the tags and the chart highlight. The order is
// a validated categorical order (blue, orange, aqua, yellow, magenta, violet, red) for a dark surface.
private val DisplayColor = Color(0xFF3987E5)
private val NotificationColor = Color(0xFFD95926)
private val CpuColor = Color(0xFF199E70)
private val BacklightColor = Color(0xFFC98500)
private val VibrationColor = Color(0xFFD55181)
private val AppColor = Color(0xFF9085E9)
private val OfflineColor = Color(0xFFE66767)

private val TagColors = mapOf(
    HourTag.Drop to Color(0xFFF2F2F2),
    HourTag.Backlight to BacklightColor,
    HourTag.App to AppColor,
    HourTag.Notifications to NotificationColor,
    HourTag.Vibration to VibrationColor,
    HourTag.Calls to Color(0xFF7DD3FC),
    HourTag.Disconnected to OfflineColor,
    HourTag.Charging to Color(0xFFBDBDBD),
)

private val WatchfaceColors = listOf(
    Color(0xFF8B5CF6), Color(0xFFF59E0B), Color(0xFF38BDF8), Color(0xFFF472B6), Color(0xFF34D399),
)

private val ChartWindow = 7.days

// Every time chart starts after the same gutter, so one hour sits at the same x in all of them.
private val Gutter = 40.dp

/** The hour all charts point at, read and set by the scrub gesture. */
private class ScrubTarget(val get: () -> Instant?, val set: (Instant?) -> Unit)

/** One row of the hourly activity chart. */
private class Lane(val label: String, val unit: String, val color: Color, val value: (HourStats) -> Double)

private val Lanes = listOf(
    Lane("Display updates", "", DisplayColor) { it.displayUpdates.toDouble() },
    Lane("Notifications", "", NotificationColor) { it.notifications.toDouble() },
    Lane("CPU awake", "%", CpuColor) { it.cpuAwakePct ?: 0.0 },
    Lane("Backlight", "s", BacklightColor) { it.backlightS },
    Lane("Vibration", "s", VibrationColor) { it.vibrationS },
    Lane("App CPU", "%", AppColor) { it.appCpuPct ?: 0.0 },
    Lane("Offline", "min", OfflineColor) {
        val c = it.connectedS
        val e = it.expectedConnectedS
        if (c != null && e != null) ((e - c) / 60).coerceAtLeast(0.0) else 0.0
    },
)

@Composable
fun LocalBatteryScreen(topBarParams: TopBarParams) {
    val repository = koinInject<LocalBatteryRepository>()
    val records by remember { repository.records() }.collectAsState(null)
    val shareLauncher = koinInject<PlatformShareLauncher>()
    val libPebble = koinInject<LibPebble>()
    val watches by libPebble.watches.collectAsState()
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
        Message(stringResource(Res.string.battery_local_empty))
        return
    }
    val known = watches.filterIsInstance<KnownPebbleDevice>()
    // The model, as Core's page names it ("Pebble Time Steel"); the serial's end tells two alike apart.
    fun modelOf(s: String) = known.firstOrNull { it.serial == s }?.let {
        it.color?.uiDescription?.substringBefore(" - ") ?: it.watchType.watchType.modelDescription()
    }
    val models = serials.associateWith { modelOf(it) }
    fun nameOf(s: String): String {
        val model = models[s] ?: return s
        return if (models.values.count { it == model } > 1) "$model · ${s.takeLast(4)}" else model
    }
    val history = remember(all, serial) { BatteryHistory.from(all.filter { it.entity.serial == serial }) }
    val page = remember(history) { BatteryPage.from(history) }
    val platform = known.firstOrNull { it.serial == serial }?.watchType?.watchType ?: WatchType.BASALT

    Column(
        Modifier.fillMaxSize().background(Palette.Page).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            serials.forEach { s -> WatchChip(nameOf(s), s == serial) { selected = s } }
        }
        val last = page.hours.lastOrNull()
        if (last == null) {
            Message(stringResource(Res.string.battery_local_empty))
        } else {
            BatteryContent(page, last, platform)
        }
        if (history.unknownFormatCount > 0) {
            Text(
                stringResource(Res.string.battery_local_unknown_format, history.unknownFormatCount.toString()),
                fontSize = 12.sp,
                color = Palette.Muted,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BatteryContent(page: BatteryPage, last: HourStats, platform: WatchType) {
    val end = last.time
    val start = maxOf(end - ChartWindow, page.hours.first().time)
    val window = remember(page, start, end) { page.window(start, end) }
    val spans = remember(page, start, end) { page.watchfaces(start, end) }
    val totals = remember(page, start, end) { page.totals(start, end) }
    var tag by remember { mutableStateOf<HourTag?>(null) }
    // The hour the charts point at; follows the finger, starts at the newest record.
    var pickedTime by remember { mutableStateOf<Instant?>(null) }
    val picked = window.firstOrNull { it.time == pickedTime } ?: last
    val pick = remember { ScrubTarget({ pickedTime }, { pickedTime = it }) }

    val lookup = koinInject<WatchfaceImpactLookup>()
    val impacts = remember { mutableStateMapOf<String, WatchfaceImpact?>() }
    LaunchedEffect(spans, platform) {
        spans.mapNotNull { it.uuid }.distinct().forEach { uuid ->
            if (uuid !in impacts) impacts[uuid] = lookup.impact(uuid, platform)
        }
    }

    TealCard {
        Header(page, last)
        ChartTitle("Battery level", "drag across a chart to read any hour")
        BatteryChart(window, start, end, last.offset, tag, picked, pick)
        Legend(
            listOf(
                LevelGood to "50% and up",
                LevelLow to "20–50%",
                LevelCritical to "below 20%",
                Palette.Charging.copy(alpha = 0.5f) to "charging",
            ) + listOfNotNull(tag?.let { TagColors.getValue(it) to tagLabel(it) }),
        )
        if (spans.isNotEmpty()) WatchfaceStrip(spans, start, end, impacts, platform)
        HorizontalDivider(color = Palette.Track)
        HourlyDetails(window, tag) { tag = if (tag == it) null else it }
    }
    TealCard {
        ChartTitle("Activity by hour", "rows scale to their busy hours; white caps go past it")
        ActivityLanes(window, start, end, last.offset, picked, pick)
        HourInspector(picked)
    }
    if (window.any { it.voltage != null }) {
        TealCard {
            ChartTitle("Battery voltage", "the percentage is worked out from this")
            VoltageChart(window, start, end, last.offset, picked, pick)
            Text(
                "Voltage shows the real trend. The percentage can jump where the voltage curve is steep, " +
                    "and reads high right after charging.",
                fontSize = 13.sp,
                color = Palette.Muted,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
    }
    TealCard {
        Totals(totals, start, end)
        val tips = page.tips()
        if (tips.isNotEmpty()) {
            HorizontalDivider(color = Palette.Track)
            Tips(tips, last)
        }
    }
}

@Composable
private fun WatchChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Palette.Accent else Palette.Page)
            .border(1.5.dp, if (selected) Palette.Accent else Palette.Text, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TealCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Card)
            .border(1.dp, Palette.CardBorder, RoundedCornerShape(20.dp))
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun ChartTitle(title: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Text(title, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = Palette.Faint, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    FlowRow(
        Modifier.padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 8.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Header(page: BatteryPage, last: HourStats) {
    val time = last.time.toLocalDateTime(last.offset.asTimeZone())
    val day = page.drop(last.time - 24.hours, last.time)
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("${last.socPct.fmt(0)}%", color = Palette.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("${time.monthDay()} ${time.hm()}", color = Palette.Muted, fontSize = 14.sp)
            last.watchfaceName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Palette.Muted)) { append("Watchface: ") }
                        withStyle(SpanStyle(color = Palette.Text, fontWeight = FontWeight.Bold)) { append(it) }
                    },
                    fontSize = 15.sp,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            last.voltage?.let {
                Text("${it.fmt(2)} V", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (day.hours > 0) "−${day.dropPct.fmt(1)}% in the last 24 h" else "No discharge in the last 24 h",
                color = Palette.Muted, fontSize = 13.sp, textAlign = TextAlign.End,
            )
            if (day.hours > 0) Text("${day.hours.fmt(0)} h on battery", color = Palette.Muted, fontSize = 13.sp)
            if (last.charging) {
                Text("Charging", color = Palette.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private class ChartGeometry(val start: Instant, val end: Instant, val width: Float, val left: Float = 0f) {
    private val span = (end - start).inWholeSeconds.coerceAtLeast(1).toFloat()
    fun x(t: Instant) = left + ((t - start).inWholeSeconds / span * (width - left)).coerceIn(0f, width - left)
}

/**
 * Picks the hour under the finger as soon as it touches, and keeps following a sideways drag.
 * A drag that turns out vertical is left to the page, which scrolls, and the pick is undone.
 */
private fun Modifier.scrub(
    hours: List<HourStats>,
    start: Instant,
    end: Instant,
    left: Float,
    target: ScrubTarget,
) = pointerInput(hours, start, end, left, target) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val before = target.get()
        val g = ChartGeometry(start, end, size.width.toFloat(), left)
        fun pick(x: Float) = hours.minByOrNull { abs(g.x(it.time) - x) }?.let { target.set(it.time) }
        pick(down.position.x)
        var sideways: Boolean? = null
        var moved = Offset.Zero
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            moved += change.positionChange()
            if (sideways == null && moved.getDistance() > viewConfiguration.touchSlop) {
                sideways = abs(moved.x) >= abs(moved.y)
            }
            when (sideways) {
                true -> {
                    change.consume()
                    pick(change.position.x)
                }
                false -> {
                    target.set(before)
                    break
                }
                null -> {}
            }
        }
    }
}

// Dashed line at each midnight; labels gets the day that starts there and the line's x.
private fun DrawScope.dayLines(
    g: ChartGeometry,
    offset: UtcOffset,
    top: Float,
    bottom: Float,
    labels: (LocalDate, Float) -> Unit,
) {
    val tz = offset.asTimeZone()
    var day = g.start.toLocalDateTime(tz).date.plus(DatePeriod(days = 1))
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
    while (true) {
        val midnight = day.atStartOfDayIn(tz)
        if (midnight > g.end) break
        val dx = g.x(midnight)
        drawLine(Palette.Grid, Offset(dx, top), Offset(dx, bottom), 1.5.dp.toPx(), pathEffect = dash)
        labels(day, dx)
        day = day.plus(DatePeriod(days = 1))
    }
}

private fun DrawScope.cursor(x: Float, top: Float, bottom: Float) {
    drawLine(Palette.Text, Offset(x, top), Offset(x, bottom), 1.5.dp.toPx())
}

// A two-line label boxed next to the cursor, kept inside the chart.
private fun DrawScope.tooltip(tm: TextMeasurer, x: Float, top: Float, lines: List<String>) {
    val style = TextStyle(fontSize = 12.sp, color = Palette.Text, fontWeight = FontWeight.SemiBold)
    val measured = lines.map { tm.measure(it, style) }
    val pad = 6.dp.toPx()
    val w = measured.maxOf { it.size.width } + pad * 2
    val h = measured.sumOf { it.size.height } + pad * 2
    val left = if (x + 10.dp.toPx() + w <= size.width) x + 10.dp.toPx() else x - 10.dp.toPx() - w
    drawRoundRect(Palette.Tooltip, Offset(left.coerceAtLeast(0f), top), Size(w, h.toFloat()), CornerRadius(6.dp.toPx()))
    var y = top + pad
    measured.forEach {
        drawText(it, topLeft = Offset(left.coerceAtLeast(0f) + pad, y))
        y += it.size.height
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate) = when (day) {
    today -> "Today"
    today.minus(DatePeriod(days = 1)) -> "Yesterday"
    else -> day.monthDay()
}

@Composable
private fun BatteryChart(
    hours: List<HourStats>,
    start: Instant,
    end: Instant,
    offset: UtcOffset,
    tag: HourTag?,
    picked: HourStats,
    onPick: ScrubTarget,
) {
    val tm = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = Palette.Faint)
    val dayStyle = TextStyle(fontSize = 13.sp, color = Palette.Muted)
    val today = Clock.System.now().toLocalDateTime(offset.asTimeZone()).date
    val axisW = with(androidx.compose.ui.platform.LocalDensity.current) { Gutter.toPx() }
    Canvas(Modifier.fillMaxWidth().height(260.dp).scrub(hours, start, end, axisW, onPick)) {
        val labelH = 24.dp.toPx()
        val chartH = size.height - labelH
        val g = ChartGeometry(start, end, size.width, axisW)
        fun y(v: Double) = chartH - (v / 100).toFloat().coerceIn(0f, 1f) * (chartH - 8.dp.toPx())
        drawRect(Palette.ChartBack, Offset(axisW, 0f), Size(size.width - axisW, chartH))
        for (level in listOf(0, 25, 50, 75, 100)) {
            val yy = y(level.toDouble())
            drawLine(Palette.Grid, Offset(axisW, yy), Offset(size.width, yy), 1.dp.toPx())
            val m = tm.measure("$level%", axisStyle)
            drawText(m, topLeft = Offset(axisW - m.size.width - 4.dp.toPx(), yy - m.size.height / 2f))
        }
        hours.zipWithNext().forEach { (a, b) ->
            if (b.charging) {
                drawRect(Palette.Charging, Offset(g.x(a.time), 0f), Size(g.x(b.time) - g.x(a.time), chartH))
            }
        }
        // Filled area and line, broken where records are missing, coloured by the level of each step.
        for (run in runs(hours)) {
            run.zipWithNext().forEach { (a, b) ->
                val pa = Offset(g.x(a.time), y(a.socPct))
                val pb = Offset(g.x(b.time), y(b.socPct))
                val color = levelColor((a.socPct + b.socPct) / 2)
                val fill = Path().apply {
                    moveTo(pa.x, chartH); lineTo(pa.x, pa.y); lineTo(pb.x, pb.y); lineTo(pb.x, chartH); close()
                }
                drawPath(fill, Color.White.copy(alpha = 0.07f))
                drawLine(color, pa, pb, 3.dp.toPx(), cap = StrokeCap.Round)
            }
            if (run.size == 1) drawCircle(levelColor(run[0].socPct), 3.dp.toPx(), Offset(g.x(run[0].time), y(run[0].socPct)))
        }
        if (tag != null) {
            val color = TagColors.getValue(tag)
            hours.forEachIndexed { i, h ->
                if (!h.has(tag)) return@forEachIndexed
                val x1 = g.x(h.time)
                val x0 = hours.getOrNull(i - 1)?.time?.takeIf { h.time - it <= BatteryPage.MAX_STEP }
                    ?.let { g.x(it) } ?: (x1 - 3.dp.toPx())
                val w = (x1 - x0).coerceAtLeast(3.dp.toPx())
                drawRect(color.copy(alpha = 0.3f), Offset(x0, 0f), Size(w, chartH))
                drawRect(color, Offset(x0, 0f), Size(w, 4.dp.toPx()))
            }
        }
        dayLines(g, offset, 0f, chartH) { day, dx ->
            val m = tm.measure(dayLabel(day, today), dayStyle)
            val left = (dx - m.size.width / 2f).coerceIn(axisW, (size.width - m.size.width).coerceAtLeast(axisW))
            drawText(m, topLeft = Offset(left, chartH + 4.dp.toPx()))
        }
        val px = g.x(picked.time)
        cursor(px, 0f, chartH)
        drawCircle(Palette.Text, 5.dp.toPx(), Offset(px, y(picked.socPct)))
        val t = picked.time.toLocalDateTime(picked.offset.asTimeZone())
        tooltip(
            tm, px, 4.dp.toPx(),
            listOf(
                "${t.monthDay()} ${t.hm()}",
                "${picked.socPct.fmt(1)}%" + (picked.dropPct?.let { "  −${it.fmt(1)}" } ?: "") +
                    (if (picked.charging) "  charging" else ""),
            ),
        )
    }
}

private fun runs(hours: List<HourStats>): List<List<HourStats>> {
    val runs = mutableListOf<MutableList<HourStats>>()
    for (h in hours) {
        val run = runs.lastOrNull()
        if (run != null && h.time - run.last().time <= BatteryPage.MAX_STEP) run += h else runs += mutableListOf(h)
    }
    return runs
}

@Composable
private fun ActivityLanes(
    hours: List<HourStats>,
    start: Instant,
    end: Instant,
    offset: UtcOffset,
    picked: HourStats,
    onPick: ScrubTarget,
) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = Palette.Muted)
    val maxStyle = TextStyle(fontSize = 10.sp, color = Palette.Faint)
    // Each row scales to its 95th percentile hour, so one busy hour does not flatten the rest;
    // taller hours are clipped and capped in white.
    val scales = remember(hours) {
        Lanes.map { lane ->
            val values = hours.map(lane.value).filter { it > 0 }.sorted()
            if (values.isEmpty()) 0.0 to 0.0
            else values[((values.size - 1) * 0.95).toInt()] to values.last()
        }
    }
    val laneH = 34.dp
    val axisW = with(androidx.compose.ui.platform.LocalDensity.current) { Gutter.toPx() }
    Canvas(Modifier.fillMaxWidth().height(laneH * Lanes.size).scrub(hours, start, end, axisW, onPick)) {
        val g = ChartGeometry(start, end, size.width, axisW)
        val h = size.height / Lanes.size
        val barW = (g.x(start + 1.hours) - g.x(start)).coerceAtLeast(1.dp.toPx())
        Lanes.forEachIndexed { i, lane ->
            val top = i * h
            val bottom = top + h - 3.dp.toPx()
            val plotTop = top + 15.dp.toPx()
            drawRect(Palette.ChartBack, Offset(axisW, top), Size(size.width - axisW, h - 3.dp.toPx()))
            val (scale, max) = scales[i]
            if (scale > 0) {
                hours.forEach { hour ->
                    val v = lane.value(hour)
                    val bh = (v / scale).toFloat().coerceAtMost(1f) * (bottom - plotTop)
                    if (bh < 2.dp.toPx()) return@forEach
                    val x0 = (g.x(hour.time) - barW * 0.9f).coerceAtLeast(axisW)
                    val w = g.x(hour.time) - barW * 0.1f - x0
                    if (w <= 0) return@forEach
                    drawRect(lane.color, Offset(x0, bottom - bh), Size(w, bh))
                    if (v > scale) drawRect(Palette.Text, Offset(x0, bottom - bh), Size(w, 2.dp.toPx()))
                }
            }
            drawText(tm.measure(lane.label, labelStyle), topLeft = Offset(axisW + 4.dp.toPx(), top + 1.dp.toPx()))
            val unit = if (lane.unit.isEmpty()) "" else " ${lane.unit}"
            val m = tm.measure("max ${max.compact()}$unit", maxStyle)
            drawText(m, topLeft = Offset(size.width - m.size.width - 4.dp.toPx(), top + 2.dp.toPx()))
        }
        dayLines(g, offset, 0f, size.height) { _, _ -> }
        cursor(g.x(picked.time) - barW / 2, 0f, size.height)
    }
}

@Composable
private fun VoltageChart(
    hours: List<HourStats>,
    start: Instant,
    end: Instant,
    offset: UtcOffset,
    picked: HourStats,
    onPick: ScrubTarget,
) {
    val tm = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = Palette.Faint)
    val volts = hours.mapNotNull { it.voltage }
    val step = listOf(0.05, 0.1, 0.2, 0.25, 0.5).first { (volts.max() - volts.min()) / it <= 6 }
    val loStep = kotlin.math.floor(volts.min() / step).toLong()
    val hiStep = kotlin.math.ceil(volts.max() / step).toLong().coerceAtLeast(loStep + 1)
    val lo = loStep * step
    val hi = hiStep * step
    val axisW = with(androidx.compose.ui.platform.LocalDensity.current) { Gutter.toPx() }
    Canvas(Modifier.fillMaxWidth().height(170.dp).scrub(hours, start, end, axisW, onPick)) {
        val chartH = size.height - 6.dp.toPx()
        val g = ChartGeometry(start, end, size.width, axisW)
        fun y(v: Double) = chartH - ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * chartH
        drawRect(Palette.ChartBack, Offset(axisW, 0f), Size(size.width - axisW, chartH))
        for (k in loStep..hiStep) {
            val level = k * step
            val yy = y(level)
            drawLine(Palette.Grid, Offset(axisW, yy), Offset(size.width, yy), 1.dp.toPx())
            val m = tm.measure(level.fmt(2), axisStyle)
            val top = (yy - m.size.height / 2f).coerceIn(0f, chartH - m.size.height)
            drawText(m, topLeft = Offset(axisW - m.size.width - 4.dp.toPx(), top))
        }
        dayLines(g, offset, 0f, chartH) { _, _ -> }
        for (run in runs(hours.filter { it.voltage != null })) {
            val path = Path()
            run.forEachIndexed { i, h ->
                val p = Offset(g.x(h.time), y(h.voltage!!))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, Palette.Voltage, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        picked.voltage?.let { v ->
            val px = g.x(picked.time)
            cursor(px, 0f, chartH)
            drawCircle(Palette.Text, 4.dp.toPx(), Offset(px, y(v)))
            tooltip(tm, px, 4.dp.toPx(), listOf("${v.fmt(3)} V"))
        }
    }
}

@Composable
private fun HourInspector(h: HourStats) {
    val t = h.time.toLocalDateTime(h.offset.asTimeZone())
    var allFields by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Palette.ChartBack).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "${t.monthDay()} ${t.hm()} · ${h.socPct.fmt(1)}%" + (h.voltage?.let { " · ${it.fmt(3)} V" } ?: "") +
                (h.dropPct?.let { " · −${it.fmt(1)}%" } ?: "") + (if (h.charging) " · charging" else ""),
            color = Palette.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        )
        Spacer(Modifier.height(2.dp))
        val intensity = h.backlightIntensityPct?.takeIf { h.backlightS > 0 }?.let { " at ${it.fmt(0)}%" } ?: ""
        StatRow("Backlight", h.backlightS.duration() + intensity)
        StatRow("Display updates", h.displayUpdates.toString())
        StatRow("Notifications", h.notifications.toString())
        StatRow("Vibration", h.vibrationS.duration())
        if (h.calls > 0) StatRow("Calls", "${h.calls} · ${h.callS.duration()}")
        StatRow("Button presses", h.buttons.toString())
        h.cpuAwakePct?.let { StatRow("CPU awake", "${it.fmt(2)}%") }
        h.appCpuPct?.let { StatRow("App CPU", "${it.fmt(2)}%") }
        h.connectedS?.let { StatRow("Connected", it.duration()) }
        if (h.disconnects > 0) StatRow("Disconnections", h.disconnects.toString())
        h.watchfaceName?.takeIf { it.isNotBlank() }?.let { StatRow("Watchface", it) }
        Spacer(Modifier.height(4.dp))
        val fields = h.raw.numbers.size + h.raw.texts.size
        Text(
            (if (allFields) "▾ " else "▸ ") + "All $fields fields of this record",
            color = Palette.AccentText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { allFields = !allFields }.padding(vertical = 4.dp),
        )
        if (allFields) {
            val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            h.raw.texts.entries.sortedBy { it.key }.forEach { (k, v) -> FieldRow(k, v, Palette.Text, mono) }
            h.raw.numbers.entries.sortedBy { it.key }.forEach { (k, v) ->
                FieldRow(k, v.exact(), if (v == 0.0) Palette.Faint else Palette.Text, mono)
            }
        }
    }
}

@Composable
private fun FieldRow(name: String, value: String, color: Color, style: TextStyle) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, color = if (color == Palette.Faint) Palette.Faint else Palette.Muted, style = style, modifier = Modifier.weight(1f))
        Text(value, color = color, style = style)
    }
}

@Composable
private fun WatchfaceStrip(
    spans: List<WatchfaceSpan>,
    start: Instant,
    end: Instant,
    impacts: Map<String, WatchfaceImpact?>,
    platform: WatchType,
) {
    val names = spans.map { it.name }.distinct()
    fun color(name: String) = WatchfaceColors[names.indexOf(name) % WatchfaceColors.size]
    val axisW = with(androidx.compose.ui.platform.LocalDensity.current) { Gutter.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChartTitle("Watchface", "the face on screen each hour, on the same time axis")
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val g = ChartGeometry(start, end, size.width, axisW)
            val r = CornerRadius(10.dp.toPx())
            drawRoundRect(Palette.Track, Offset(axisW, 0f), Size(size.width - axisW, size.height), r)
            spans.forEach { s ->
                // A record covers the hour before it.
                val x0 = g.x(s.start - 1.hours)
                val x1 = g.x(s.end)
                drawRoundRect(color(s.name), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(6.dp.toPx()), size.height), r)
            }
        }
        names.forEach { name ->
            val uuid = spans.first { it.name == name }.uuid
            val impact = uuid?.let { impacts[it] }
            val hours = spans.filter { it.name == name }.sumOf { it.hours }
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color(name)))
                Spacer(Modifier.width(8.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Palette.Text, fontWeight = FontWeight.Bold)) { append(name) }
                        withStyle(SpanStyle(color = Palette.Muted)) {
                            append("  $hours h · ")
                            append(impact?.let { impactText(it, platform) } ?: "no battery data in the store")
                        }
                    },
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun impactText(i: WatchfaceImpact, platform: WatchType): String {
    val range = when (i.impact.level) {
        "negligible" -> "negligible impact (<5% shorter battery life)"
        "small" -> "small impact (5–15% shorter battery life)"
        "medium" -> "medium impact (15–30% shorter battery life)"
        "large" -> "large impact (30%+ shorter battery life)"
        else -> "${i.impact.level} impact"
    }
    return if (i.platform != platform.codename) {
        "$range, measured on ${batteryImpactWatchName(i.platform, short = true)}"
    } else {
        range
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourlyDetails(hours: List<HourStats>, selected: HourTag?, onTag: (HourTag) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Hourly details", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Each tag marks the hours whose record crossed a threshold. Tap a tag to highlight those hours " +
                "on the chart, tap again to clear.",
            color = Palette.Muted, fontSize = 14.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HourTag.entries.forEach { t ->
                val count = hours.count { it.has(t) }
                if (count > 0 || t == HourTag.Drop) TagChip(t, count, selected == t) { onTag(t) }
            }
        }
        selected?.let { Text(tagMeaning(it), color = Palette.Muted, fontSize = 13.sp) }
    }
}

@Composable
private fun TagChip(tag: HourTag, count: Int, selected: Boolean, onClick: () -> Unit) {
    val color = TagColors.getValue(tag)
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) color.copy(alpha = 0.25f) else Palette.Card)
            .border(1.dp, if (selected) color else Palette.ChipBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(tagLabel(tag), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), color = Palette.Muted, fontSize = 14.sp)
    }
}

private fun tagLabel(t: HourTag) = when (t) {
    HourTag.Drop -> "% drop"
    HourTag.Backlight -> "Heavy backlight"
    HourTag.App -> "Heavy app"
    HourTag.Notifications -> "Notifications"
    HourTag.Vibration -> "Vibration"
    HourTag.Calls -> "Calls"
    HourTag.Disconnected -> "Disconnected"
    HourTag.Charging -> "Charging"
}

private fun tagMeaning(t: HourTag) = when (t) {
    HourTag.Drop -> "Hours where the battery level fell by ${BatteryPage.HEAVY_DROP_PCT.fmt(0)}% or more."
    HourTag.Backlight -> "Hours with the backlight on for ${BatteryPage.HEAVY_BACKLIGHT_S.fmt(0)} s or more."
    HourTag.App -> "Hours where apps kept the CPU busy ${BatteryPage.HEAVY_APP_CPU_PCT.fmt(0)}% of the time or more."
    HourTag.Notifications -> "Hours with ${BatteryPage.MANY_NOTIFICATIONS} notifications or more."
    HourTag.Vibration -> "Hours with the motor running for ${BatteryPage.LONG_VIBRATION_S.fmt(0)} s or more."
    HourTag.Calls -> "Hours with an incoming call."
    HourTag.Disconnected ->
        "Hours with a disconnection, or ${(BatteryPage.DISCONNECTED_S / 60).fmt(0)} min or more without the phone."
    HourTag.Charging -> "Hours on the charger."
}

@Composable
private fun Totals(t: PeriodTotals, start: Instant, end: Instant) {
    val days = (end - start).inWholeHours / 24.0
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What the watch did", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Totals over the charts: ${days.fmt(1)} days, ${t.hours} hourly records.",
            color = Palette.Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(2.dp))
        StatRow("Backlight on", t.backlightS.duration())
        StatRow("Vibration", t.vibrationS.duration())
        StatRow("Notifications", t.notifications.toString())
        StatRow("Calls", if (t.calls > 0) "${t.calls} · ${t.callS.duration()}" else "0")
        StatRow("Button presses", t.buttons.toString())
        StatRow("Display updates", t.displayUpdates.toString())
        t.connectedPct?.let { StatRow("Connected to the phone", "${it.fmt(1)}% of the time") }
        StatRow("Disconnections", t.disconnects.toString())
        StatRow("Charges", t.charges.toString())
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Palette.Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Tips(tips: List<SettingTip>, last: HourStats) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        tips.forEach { tip ->
            val (title, body) = when (tip) {
                SettingTip.BacklightIntensity -> "Backlight intensity is above Medium" to
                    "It is set to ${last.settings.intensityPct?.fmt(0)}%. Medium or below uses less power " +
                    "each time the display lights up."
                SettingTip.BacklightTimeout -> "Backlight timeout is longer than 3 seconds" to
                    "It is set to ${last.settings.timeoutS?.fmt(0)} s. A shorter timeout keeps the light on " +
                    "for less time after each tap and notification."
                SettingTip.HealthTracking -> "Health tracking is on" to
                    "Health tracking keeps the motion sensor sampling all day."
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(body, color = Palette.Muted, fontSize = 14.sp)
            }
        }
        Text(
            "Change these settings to reduce power consumption",
            color = Palette.Muted, fontSize = 14.sp, fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.Text, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

private fun LocalDateTime.hm(): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private fun LocalDateTime.monthDay(): String = date.monthDay()

private fun LocalDate.monthDay(): String =
    "${month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} $day"

private fun Double.duration(): String {
    val s = roundToLong()
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

// The field list shows values as decoded: whole numbers plain, others to three decimals.
private fun Double.exact(): String =
    if (this == roundToLong().toDouble()) roundToLong().toString() else fmt(3).trimEnd('0').trimEnd('.')

// Whole numbers without decimals; others with one decimal, or two below 1.
private fun Double.compact(): String = when {
    this == roundToLong().toDouble() -> roundToLong().toString()
    abs(this) >= 100 -> fmt(0)
    abs(this) >= 1 -> fmt(1).trimEnd('0').trimEnd('.')
    else -> fmt(2).trimEnd('0').trimEnd('.')
}

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
