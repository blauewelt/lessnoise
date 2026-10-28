package ch.lessnoise.app.ui

/**
 * The texts of «Messen». 0.8.4: no mode switch any more (0.8.3's «Am Fenster» | «Unterwegs» and its
 * stored choice are gone). 0.9.4.4 (owner: «the main UI flow is to report someone»): in the editions with
 * the map «Laut! Melden» is the big main surface in both states — idle above the start button, and while
 * a measurement runs under the level card (until 0.9.4.3 the running screen had none: «running = phone at
 * the window»). Labor·Karte keeps the road recording as a further button ([UNTERWEGS_BUTTON]). Pure, so
 * that MeasureTextsTest checks it on the JVM.
 */

/** The big button «Laut! Melden» (editions with the map; 0.9.4.4 in both states, BigReportButton). */
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

/**
 * The line under «Laut! Melden» (editions with the map), the same helper style as the others. 0.9.4.4: also
 * while a measurement runs, so without «Unterwegs:» (0.8.8–0.9.4.3 «Unterwegs: lauten Verkehr sofort melden.»).
 */
const val REPORT_HINT = "Lauten Verkehr sofort melden."

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

/**
 * 0.9.2: «Grundrauschen» with a short parenthetical where it is a row label (running «Messen», the
 * measurement cards): the steady level under the Lärmspitzen (per minute the L90), which a first-time
 * user cannot guess from the word alone. «ohne Spitzen» rather than «leisester Pegel»: the L90 is not
 * the quietest moment, it is the level left when the peaks are taken away.
 */
const val HUM_LABEL = "Grundrauschen (ohne Spitzen)"

/** 0.9.2: «Lärmspitzen pro Stunde» before any measured time (until 0.9.1 «–»). */
const val NONE_YET = "noch keine"

/** 0.9.2: the snackbar after a «no» to Android's microphone prompt at «Langzeitmessung starten» (the service's own words). */
const val MIC_DENIED = "Ohne Mikrofon kann die App nicht messen."
