package ch.lessnoise.app.ui

// Texts of «Teilen» (app 0.6.2; consent and status line 0.6.3), as plain Kotlin so that the JVM
// tests check them (LocationPolicyTest, ShareTextsTest). Shown only where «Teilen» exists (the
// Online-Version), which is the edition with the location permission. server/DESIGN.md §2 rule 2 is
// the contract for the location texts, rule 3 for the consent.

/** 0.8.8: the title of «Teilen» (0.6.3–0.8.7 «Auf die Lärmkarte»; the tab was always «Teilen»). */
const val SHARE_TITLE = "Teilen"

/** One line under the title of «Teilen» (0.8.8; the two sections say what each kind of data is). */
const val SHARE_INTRO = "Was von dir auf die Lärmkarte kommt."

// ---- 0.8.8: the two sections of «Teilen» ----------------------------------------------------------
// Since 0.8.4 a running measurement means the phone lies at the window (long, shared as levels per
// 100-m square with the moved point); no measurement means on the road, where only «Laut! Melden»
// sends (exact place, by an explicit tap). Both go through ONE switch (UploadSnapshot.enabled).

/** Section 1: the long measurements at the window. */
const val WINDOW_SECTION_TITLE = "Messungen am Fenster"
const val WINDOW_SECTION_SUBTITLE = "Für lange Messungen: Pegel pro 100-m-Quadrat, Ort leicht verschoben."

/** Section 2: the reports on the road. */
const val REPORTS_SECTION_TITLE = "Meldungen unterwegs"
const val REPORTS_SECTION_SUBTITLE = "Mit «Laut! Melden»: genauer Ort, nur wenn du sendest."

/** Section 2, no report yet. */
const val REPORTS_NONE = "Noch keine Meldung gesendet."

/**
 * Section 2: reports and window measurements share the one switch «Auf die Karte senden» — the report
 * consent (REPORT_CONSENT_TEXT) calls Uploader.enable(), the same flag; Uploader.run sends nothing at
 * all while it is off.
 */
const val REPORTS_SAME_SWITCH = "Eine Meldung schaltet «Auf die Karte senden» ein – das gilt dann auch für Messungen am Fenster."

/**
 * 0.9.2: the row of what waits for the upload, in both sections (until 0.9.1 «Noch zu senden», which read
 * like a to-do: «Noch zu senden 0 Minuten»).
 */
const val PENDING_LABEL = "Noch nicht gesendet"

/** Section 1, [PENDING_LABEL]: «12 Minuten». */
fun pendingMinutesText(n: Int): String = if (n == 1) "1 Minute" else "$n Minuten"

/** The bullet about location in «Nie gesendet». */
const val LOCATION_NEVER_SENT = "Dein genauer Standort (ausser bei einer Meldung); nie im Hintergrund gelesen."

/** «Was gesendet wird». */
val SHARE_SENT_BULLETS: List<String> = listOf(
    "Lärmwerte pro Minute und Lärmspitzen von Motoren und Maschinen (z. B. «Lautes Fahrzeug»); Stimmen und Musik nie.",
    "Ein zufällig verschobener Punkt statt deines Standorts, und wo das Telefon liegt.",
    "Meldungen: Zeit, genauer Standort, Fahrtrichtung, Pegel, Art des Lärms.",
    "Telefonmodell, Audioquelle, Kalibrierung und eine zufällige Kennung.",
)

/**
 * «Standort», for the chosen σ of «Standort verschieben»: read once right after «Starten» and moved at once;
 * the residual risk (2σ ≈ 86 %) stated as server/DESIGN.md §2 rule 2 asks; exact only for a report.
 */
fun shareLocationBullets(sigmaM: Int): List<String> = listOf(
    "Nach dem Starten liest das Telefon einmal den Standort, solange die App offen ist, und verschiebt ihn sofort zufällig um etwa $sigmaM m; " +
        "nur dieser Punkt wird gespeichert und gesendet.",
    "Am selben Ort immer gleich verschoben – auch viele Messungen verraten den genauen Ort nicht.",
    "Meist (86 von 100 Mal) liegt dein echter Ort trotzdem näher als ${2 * sigmaM} m beim gesendeten Punkt.",
    "Bei einer Meldung der genaue Standort; danach wird er auf dem Telefon gelöscht.",
)

/** «Wohin», incl. the risk server/DESIGN.md §2 rule 2 asks the app to state. */
val SHARE_WHERE_BULLETS: List<String> = listOf(
    "An unseren Server in der Schweiz, an keinen anderen.",
    "Öffentlich sind nur Werte pro 100-m-Quadrat und Meldungen, nie die Kennung, der Punkt oder die Notiz.",
    "Misst sonst niemand in der Nähe, sind es deine Werte, in einem Quadrat nahe bei dir.",
    "Schaltest du Teilen während einer Messung ein, kommt diese ganze Messung auf die Karte, frühere nie.",
)

/** «Löschen». */
val SHARE_DELETE_BULLETS: List<String> = listOf(
    "Ausschalten stoppt das Senden; «Vom Server löschen» entfernt alles, auch Meldungen.",
    "Nach 60 Tagen ohne neue Werte löscht der Server alles.",
)

/** «Wann»: the cadence of 0.6.3 (server/DESIGN.md §4.6). */
val SHARE_WHEN_BULLETS: List<String> = listOf(
    "Während einer Messung jede Lärmspitze nach etwa 30 Sekunden und die Minutenwerte alle 2 Minuten, nach dem Stoppen sofort, sonst stündlich.",
    "Über WLAN oder mobile Daten, höchstens etwa 3 MB pro Tag.",
)

// ---- «Auf die Lärmkarte?» (0.6.3): the one question at the first «Starten» -----------------------

/** Title of the consent dialog. */
const val CONSENT_TITLE = "Auf die Lärmkarte?"

/**
 * The three lines of the consent (server/DESIGN.md §2 rule 3: what is sent, what never, where, how
 * to delete; rule 2: the point is shifted). The full explanation is one tap away («Mehr dazu»).
 */
fun consentLines(sigmaM: Int): List<String> = listOf(
    "Gesendet: Lärmwerte pro Minute und Lärmspitzen von Motoren und Maschinen, mit einem um etwa $sigmaM m verschobenen Punkt statt deines Standorts – an unseren Server in der Schweiz.",
    "Nie gesendet: Audio, Name, dein genauer Standort (ausser bei «Laut! Melden»).",
    "Ausschalten und löschen jederzeit unter «Teilen»; nach 60 Tagen ohne neue Werte löscht der Server alles.",
)

const val CONSENT_YES = "Ja, auf die Karte"
const val CONSENT_NO = "Nur auf dem Telefon"
const val CONSENT_MORE = "Mehr dazu"

// ---- The status chip on «Messen» and «Teilen» (0.6.3 status line, ShareStatus.kt) ----------------
// 0.8.3: shorter, «·» instead of «–», no «tippen …» where the chip's «›» says it is tappable; the
// icon (StatusIcon) carries the rest (cloud_done / cloud_off / location_off …).

/** Sharing off (a tap opens «Teilen»). */
const val STATUS_OFF = "Nicht auf der Karte"

/** The one fix is running; the same words as in «Laut! Melden» ([REPORT_LOCATING]). */
const val STATUS_LOCATING = REPORT_LOCATING
/** 0.9.2: «… für die Karte» (until 0.9.1 «Kein Standort», which did not say for what). */
const val STATUS_NO_POINT = "Kein Standort für die Karte"
const val STATUS_FIRST_VALUES = "Wird geteilt · erste Werte in etwa 1 Minute"
const val STATUS_ON_IDLE = "Teilen an · nächste Messung kommt auf die Karte"
const val STATUS_NO_INTERNET = "Kein Internet · wird nachgeholt"
const val STATUS_SERVER_BUSY = "Server nicht erreichbar · wird nachgeholt"
const val STATUS_REJECTED = "Vom Server abgelehnt"
const val STATUS_AUTH = "Unterbrochen · tippen für «Neu starten»"
/** 0.6.4: the running measurement was started as «Unterwegs». */
const val STATUS_UNTERWEGS = "Unterwegs · nichts auf die Karte"

/**
 * 0.6.7 («Unterwegs erkannt», editions with the map): a measurement started «Am Fenster» while the
 * phone is being carried. The mode switch shows «Unterwegs erkannt» at the same time (0.8.3).
 */
const val STATUS_MOVING = "Unterwegs erkannt · nichts auf die Karte"

/** «Auf der Karte · 14:05» (the last acknowledged upload). */
fun statusSent(hm: String): String = "Auf der Karte · $hm"

// ---- The banner above the map in the tab «Karte» (0.6.3 map tab fix, ShareStatus.kt mapBanner) ----
// Since 0.8.3 drawn as the same chip as on «Messen».

const val BANNER_NOT_SHARING = "Du teilst nicht · tippen zum Teilen"
const val BANNER_NO_POINT = "Noch kein Standort · Werte erscheinen danach"
const val BANNER_FIRST_VALUES = "Erste Werte werden gesendet · in etwa 1 Minute auf der Karte"
/** 0.6.4: the running measurement was started as «Unterwegs». */
const val BANNER_UNTERWEGS = STATUS_UNTERWEGS

/** «Gesendet 14:05 · die Karte aktualisiert sich laufend». */
fun bannerSent(hm: String): String = "Gesendet $hm · die Karte aktualisiert sich laufend"

/** The setting in «Teilen», section «Messungen am Fenster» (0.6.2–0.8.7 under «Weitere Angaben»). */
const val SIGMA_LABEL = "Standort verschieben"

/** One choice of the setting: «50 m». */
fun sigmaChoice(sigmaM: Int): String = "$sigmaM m"

/** The one-line explanation under the setting. */
const val SIGMA_EXPLANATION =
    "Mehr verbirgt deine genaue Adresse besser, macht die Karte aber ungenauer; gilt ab der nächsten Messung."

/** «Koordinaten einfügen»: understood, and the point is moved before it is sent. */
fun pastedText(sigmaM: Int): String = "Gefunden; gesendet wird ein um etwa $sigmaM m verschobener Punkt."

