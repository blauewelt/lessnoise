package ch.lessnoise.app.ui

/**
 * The texts of «Messen». 0.8.4: no mode switch any more (0.8.3's «Am Fenster» | «Unterwegs» and its
 * stored choice are gone): a running measurement means the phone lies at a window; no running
 * measurement means the person holds the phone and is on the road, so idle the first button is
 * «Laut! Melden» (editions with the map), then the start button. Labor·Karte keeps the road
 * recording as a third button ([UNTERWEGS_BUTTON]). Pure, so that MeasureTextsTest checks it on the JVM.
 */

/** The button «Laut! Melden» (idle, editions with the map: the primary button on top). */
const val REPORT_BUTTON = "Laut! Melden"

/**
 * The window measurement: the primary button without reports (Offline, Labor), a tonal one under
 * «Laut! Melden» with them. 0.8.8: «Langzeitmessung starten» (until 0.8.7 «Messung starten»): the
 * owner — the button is about the long measurement at the window.
 */
const val START_BUTTON = "Langzeitmessung starten"
const val STARTING_BUTTON = "Startet …"
const val STOP_BUTTON = "Stoppen"

/** 0.8.8: the line under the start button, in every edition (until 0.8.7 «Zum Messen: Telefon ans offene Fenster, am Ladegerät.»). */
const val START_HINT = "Für Stunden am offenen Fenster, am Ladegerät."

/** 0.8.8: the line under «Laut! Melden» (editions with the map), the same helper style as the others. */
const val REPORT_HINT = "Unterwegs: lauten Verkehr sofort melden."

/** Labor·Karte only: the third button, today's «Unterwegs» measurement (EXTRA_UNTERWEGS) with the Labor recorder. */
const val UNTERWEGS_BUTTON = "Unterwegs aufnehmen"
const val UNTERWEGS_LABOR_HINT = "Labor: nimmt Audio auf, teilt nichts."

/** The expandable «Mehr dazu» under the buttons (collapsed by default). */
const val MORE_LABEL = "Mehr dazu"

/** The link inside «Mehr dazu» to the guide on the website (Links.MEASURE). */
const val GUIDE_LINK = "Anleitung auf wenigerlaerm.ch"

/** The first paragraph inside «Mehr dazu» (0.6.1's placement hint). */
const val PLACEMENT_HINT = "Leg das Telefon ans offene Fenster. Für lange Messungen: ans Ladegerät."

/** The one line under «Stoppen»: «Läuft seit 18:30 · Bildschirm darf aus sein.» */
fun runningLine(hm: String): String = "Läuft seit $hm · Bildschirm darf aus sein."


/** 0.8.7: the app's name under the logo on the idle «Messen» screen (every edition; the Labor banner says «Labor»). */
const val APP_NAME = "Weniger Lärm"

/**
 * 0.8.7: the line under the name, editions with «Laut! Melden»: what each of the two buttons is for.
 * 0.9.0 (owner): «Lange messen» to match the button «Langzeitmessung starten» (0.8.7–0.8.8 «… Messen.»).
 */
const val TAGLINE_REPORTS = "Kurz laut? Melden. Oft laut? Lange messen."

/** 0.8.7: the same line where the start button is the only button (Offline, Labor). */
const val TAGLINE_PLAIN = "Miss den Lärm vor deinem Fenster."

/** The line under the logo; [reports]: «Laut! Melden» is a button (editions with the map). */
fun idleTagline(reports: Boolean): String = if (reports) TAGLINE_REPORTS else TAGLINE_PLAIN
