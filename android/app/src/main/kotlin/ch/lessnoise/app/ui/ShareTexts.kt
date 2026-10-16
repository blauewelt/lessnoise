package ch.lessnoise.app.ui

// Texts of «Teilen» (app 0.6.2; consent and status line 0.6.3), as plain Kotlin so that the JVM
// tests check them (LocationPolicyTest, ShareTextsTest). Shown only where «Teilen» exists (the
// Online-Version), which is the edition with the location permission. server/DESIGN.md §2 rule 2 is
// the contract for the location texts, rule 3 for the consent.

/** One line under the title of «Teilen». */
const val SHARE_INTRO = "Deine Messwerte erscheinen auf der Lärmkarte – als Wert pro 100-m-Quadrat."

/** The bullet about location in «Nie gesendet». */
const val LOCATION_NEVER_SENT = "Dein genauer Standort (ausser bei einer Meldung); nie im Hintergrund gelesen."

/** «Was gesendet wird». */
val SHARE_SENT_BULLETS: List<String> = listOf(
    "Lärmwerte pro Minute und Lärmspitzen von Motoren und Maschinen (z. B. «Töff & Poser»); Stimmen und Musik nie.",
    "Ein zufällig verschobener Punkt statt deines Standorts, und wo das Telefon liegt.",
    "Meldungen: Zeit, genauer Standort, Art des Lärms.",
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

// ---- The status line on «Messen» and «Teilen» (0.6.3, ShareStatus.kt) -----------------------------

const val STATUS_OFF = "Nicht auf der Karte – tippen zum Teilen"
const val STATUS_LOCATING = "Standort wird bestimmt …"
const val STATUS_NO_POINT = "Kein Standort – tippen"
const val STATUS_FIRST_VALUES = "Wird geteilt – erste Werte in etwa einer Minute"
const val STATUS_ON_IDLE = "Teilen ist an – deine nächste Messung kommt auf die Karte"
const val STATUS_NO_INTERNET = "Kein Internet – wird nachgeholt"
const val STATUS_SERVER_BUSY = "Nicht gesendet: Server nicht erreichbar – wird nachgeholt"
const val STATUS_REJECTED = "Nicht gesendet: vom Server abgelehnt"
const val STATUS_AUTH = "Unterbrochen – tippen für «Neu starten»"
/** 0.6.4: the running measurement was started as «Unterwegs». */
const val STATUS_UNTERWEGS = "Unterwegs – diese Messung kommt nicht auf die Karte"

/** «Auf der Karte · zuletzt gesendet 14:05». */
fun statusSent(hm: String): String = "Auf der Karte · zuletzt gesendet $hm"

// ---- The banner above the map in the tab «Karte» (0.6.3 map tab fix, ShareStatus.kt mapBanner) ----

const val BANNER_NOT_SHARING = "Du teilst nicht – tippen zum Teilen"
const val BANNER_NO_POINT = "Noch kein Standort – Werte erscheinen danach"
const val BANNER_FIRST_VALUES = "Erste Werte werden gesendet – auf der Karte in etwa einer Minute"
/** 0.6.4: the running measurement was started as «Unterwegs». */
const val BANNER_UNTERWEGS = "Unterwegs – diese Messung kommt nicht auf die Karte"

/** «Gesendet 14:05 – die Karte aktualisiert sich laufend». */
fun bannerSent(hm: String): String = "Gesendet $hm – die Karte aktualisiert sich laufend"

/** The setting under «Teilen → Weitere Angaben». */
const val SIGMA_LABEL = "Standort verschieben"

/** One choice of the setting: «50 m». */
fun sigmaChoice(sigmaM: Int): String = "$sigmaM m"

/** The one-line explanation under the setting. */
const val SIGMA_EXPLANATION =
    "Mehr verbirgt deine genaue Adresse besser, macht die Karte aber ungenauer; gilt ab der nächsten Messung."

/** «Koordinaten einfügen»: understood, and the point is moved before it is sent. */
fun pastedText(sigmaM: Int): String = "Gefunden; gesendet wird ein um etwa $sigmaM m verschobener Punkt."

