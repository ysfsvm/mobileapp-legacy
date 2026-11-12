package coredevices.pebble.ui

import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.BoolWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.RgbColorWatchPref
import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.test.Test
import kotlin.test.assertEquals

class HardwarePrefsTest {
    private val all = listOf(
        WatchPreference(BoolWatchPref.Backlight, true),
        WatchPreference(EnumWatchPref.DynamicBacklightMode, null),
        WatchPreference(RgbColorWatchPref.BacklightColor, null),
        WatchPreference(RgbColorWatchPref.BacklightColorNight, null),
        WatchPreference(BoolWatchPref.BacklightColorDayNightEnabled, null),
        WatchPreference(NumberWatchPref.BacklightColorSunriseMinute, null),
        WatchPreference(NumberWatchPref.BacklightColorSunsetMinute, null),
        WatchPreference(EnumWatchPref.BacklightTouch, null),
        WatchPreference(BoolWatchPref.QuietTimeTouchBacklight, null),
        WatchPreference(BoolWatchPref.AlternativeNotificationStyle, null),
        WatchPreference(EnumWatchPref.TimelineQuickViewUnsupportedFaces, null),
    )

    private fun shown(type: WatchType?) = all.hideUnsupportedHardwarePrefs(type).map { it.pref }

    @Test
    fun unknownWatchShowsEverything() {
        assertEquals(all.map { it.pref }, shown(null))
    }

    @Test
    fun pebbleTimeHasNoColourBacklightOrTouch() {
        assertEquals(
            listOf(BoolWatchPref.Backlight, EnumWatchPref.DynamicBacklightMode),
            shown(WatchType.BASALT),
        )
        assertEquals(shown(WatchType.BASALT), shown(WatchType.CHALK))
    }

    @Test
    fun pebbleTime2KeepsColourAndTouch() {
        assertEquals(all.map { it.pref } - BoolWatchPref.AlternativeNotificationStyle, shown(WatchType.EMERY))
    }

    @Test
    fun round2HasTouchButNoColourBacklight() {
        assertEquals(
            listOf(
                BoolWatchPref.Backlight,
                EnumWatchPref.DynamicBacklightMode,
                EnumWatchPref.BacklightTouch,
                BoolWatchPref.QuietTimeTouchBacklight,
            ),
            shown(WatchType.GABBRO),
        )
    }

    @Test
    fun blackAndWhiteWatchesKeepTheAlternativeStyle() {
        assertEquals(
            listOf(BoolWatchPref.Backlight, EnumWatchPref.DynamicBacklightMode, BoolWatchPref.AlternativeNotificationStyle),
            shown(WatchType.FLINT),
        )
    }
}
