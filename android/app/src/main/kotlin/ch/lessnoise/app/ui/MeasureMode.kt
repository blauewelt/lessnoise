package ch.lessnoise.app.ui

import android.content.Context

/**
 * 0.8.3: the mode switch at the top of «Messen» (editions with the map only, where «Unterwegs»
 * exists: LoudAlerts.AVAILABLE). It replaces the two start buttons «Messen» | «Unterwegs» of 0.6.4:
 * one choice, remembered, and one button «Messung starten» that starts in that mode. Pure (texts and
 * which segment is shown), so that MeasureModeTest checks it on the JVM.
 */
enum class MeasureMode {
    /** «Am Fenster»: the measurement as «Messen» started it; shared if sharing is on. */
    WINDOW,

    /** «Unterwegs»: started with MeasurementService.EXTRA_UNTERWEGS; shares nothing (0.6.4). */
    ROAD,
}

const val MODE_WINDOW = "Am Fenster"
const val MODE_ROAD = "Unterwegs"

/** Added to «Unterwegs» while the motion detector says the phone is carried in an «Am Fenster» run. */
const val MODE_DETECTED = "erkannt"

/** Under the switch, only after a tap on it while a measurement runs. */
const val MODE_LOCKED = "Zum Wechseln zuerst stoppen"

/** The one primary button. */
const val START_BUTTON = "Messung starten"
const val STARTING_BUTTON = "Startet …"
const val STOP_BUTTON = "Stoppen"

/** The one line under «Messung starten», per mode (and, without a mode switch, the window line without its prefix). */
const val WINDOW_HINT = "Am Fenster: Telefon ans offene Fenster, am Ladegerät."
const val UNTERWEGS_HINT = "Unterwegs: Mitteilung bei sehr lautem Verkehr. Nichts kommt auf die Karte."
const val PLAIN_HINT = "Telefon ans offene Fenster, am Ladegerät."

/** The expandable «Mehr dazu» under the button (collapsed by default). */
const val MORE_LABEL = "Mehr dazu"

/** The link inside «Mehr dazu» to the guide on the website (Links.MEASURE). */
const val GUIDE_LINK = "Anleitung auf wenigerlaerm.ch"

/** The first paragraph inside «Mehr dazu» (0.6.1's placement hint). */
const val PLACEMENT_HINT = "Leg das Telefon ans offene Fenster. Für lange Messungen: ans Ladegerät."

/** The one line under «Stoppen»: «Läuft seit 18:30 · Bildschirm darf aus sein.» */
fun runningLine(hm: String): String = "Läuft seit $hm · Bildschirm darf aus sein."

/** The line under «Messung starten» while idle; [mode] null: no mode switch (Offline and Labor builds). */
fun idleHint(mode: MeasureMode?): String = when (mode) {
    null -> PLAIN_HINT
    MeasureMode.WINDOW -> WINDOW_HINT
    MeasureMode.ROAD -> UNTERWEGS_HINT
}

/** Which segment the switch shows as selected, and whether the app chose it by itself («erkannt»). */
data class ShownMode(val segment: MeasureMode, val detected: Boolean = false)

/**
 * The selected segment. Idle: the stored choice. While a measurement runs (or starts): the mode it
 * runs in ([unterwegs] = LiveState.unterwegs), and «Unterwegs erkannt» while the motion detector says
 * the phone is carried in an «Am Fenster» run ([moving] = LiveState.moving). The stored choice is never
 * changed by the detector; when the phone lies still again the switch shows «Am Fenster» again.
 */
fun shownMode(stored: MeasureMode, running: Boolean, unterwegs: Boolean, moving: Boolean): ShownMode = when {
    !running -> ShownMode(stored)
    unterwegs -> ShownMode(MeasureMode.ROAD)
    moving -> ShownMode(MeasureMode.ROAD, detected = true)
    else -> ShownMode(MeasureMode.WINDOW)
}

/** The label of a segment: «Unterwegs erkannt» when [shown] was chosen by the detector. */
fun modeLabel(mode: MeasureMode, shown: ShownMode): String = when (mode) {
    MeasureMode.WINDOW -> MODE_WINDOW
    MeasureMode.ROAD -> if (shown.segment == MeasureMode.ROAD && shown.detected) "$MODE_ROAD $MODE_DETECTED" else MODE_ROAD
}

/**
 * The remembered choice: a private preference file «measure», key «mode» («window» | «road»), default
 * «Am Fenster». On the phone only, never sent; like every preference file excluded from backup
 * (allowBackup="false", data_extraction_rules.xml).
 */
object MeasureModeStore {
    private const val FILE = "measure"
    private const val KEY = "mode"

    fun parse(value: String?): MeasureMode = if (value == "road") MeasureMode.ROAD else MeasureMode.WINDOW

    fun value(mode: MeasureMode): String = if (mode == MeasureMode.ROAD) "road" else "window"

    fun load(context: Context): MeasureMode =
        parse(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, mode: MeasureMode) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, value(mode)).apply()
    }
}
