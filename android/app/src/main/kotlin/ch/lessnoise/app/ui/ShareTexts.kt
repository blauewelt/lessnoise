package ch.lessnoise.app.ui

// Texts of «Teilen» (app 0.6.2) and of the question «Auf die Lärmkarte?» (0.6.3), as plain Kotlin
// so that the JVM tests check them (LocationPolicyTest, ShareTextsTest). Shown only where «Teilen»
// exists (the Online-Version), which is the edition with the location permission.
// server/DESIGN.md §2 rules 2 and 3 are the contract for these texts.

/** One line under the title of «Teilen». */
const val SHARE_INTRO = "Deine Messwerte erscheinen auf der Lärmkarte – als Wert pro 100-m-Quadrat."

/** The bullet about location in «Nie gesendet». */
const val LOCATION_NEVER_SENT = "Dein genauer Standort (ausser bei einer Meldung); nie im Hintergrund gelesen."

/** «Nie gesendet» of the full explanation. */
val SHARE_NEVER_BULLETS: List<String> = listOf("Audio, Name, Telefonnummer oder E-Mail.", LOCATION_NEVER_SENT)

// ---- «Auf die Lärmkarte?» (0.6.3): the one question, at the first «Starten» --------------------
// server/DESIGN.md §2 rule 3: it names what is sent (with the shift, rule 2), what never, where,
// and how to switch off and delete; the full explanation is one tap away («Mehr dazu»).

const val CONSENT_TITLE = "Auf die Lärmkarte?"

/** The three lines of the question, for the chosen σ of «Standort verschieben». */
fun consentLines(sigmaM: Int): List<String> = listOf(
    "Gesendet: Lärmwerte pro Minute und Lärmspitzen, mit einem um etwa $sigmaM m verschobenen Punkt " +
        "statt deines Standorts – an unseren Server in der Schweiz.",
    "Nie gesendet: Audio, Name, dein genauer Standort (ausser bei «Laut! Melden»).",
    "Ausschalten und löschen jederzeit unter «Teilen»; nach 60 Tagen ohne neue Werte löscht der Server alles.",
)

const val CONSENT_YES = "Ja, auf die Karte"
const val CONSENT_NO = "Nur auf dem Telefon"
const val CONSENT_MORE = "Mehr dazu"

/** «Was gesendet wird». */
val SHARE_SENT_BULLETS: List<String> = listOf(
    "Lärmwerte pro Minute und Lärmspitzen, mit Geräuschart (z. B. «Töff & Poser»).",
    "Ein zufällig verschobener Punkt statt deines Standorts, und wo das Telefon liegt.",
    "Meldungen: Zeit, genauer Standort, Art des Lärms.",
    "Telefonmodell, Audioquelle, Kalibrierung und eine zufällige Kennung.",
)

/**
 * «Standort», for the chosen σ of «Standort verschieben»: read once right after «Starten» (the
 * measurement already runs), only while the app is open, and moved at once;
 * the residual risk (2σ ≈ 86 %) stated as server/DESIGN.md §2 rule 2 asks; exact only for a report.
 */
fun shareLocationBullets(sigmaM: Int): List<String> = listOf(
    "Gleich nach «Starten» liest das Telefon einmal den Standort, solange die App offen ist, und verschiebt ihn " +
        "sofort zufällig um etwa $sigmaM m; nur dieser Punkt wird gespeichert und gesendet.",
    "Am selben Ort immer gleich verschoben – auch viele Messungen verraten den genauen Ort nicht.",
    "Meist (86 von 100 Mal) liegt dein echter Ort trotzdem näher als ${2 * sigmaM} m beim gesendeten Punkt.",
    "Bei einer Meldung der genaue Standort; danach wird er auf dem Telefon gelöscht.",
)

/** «Wohin», incl. the risk server/DESIGN.md §2 rule 2 asks the app to state. */
val SHARE_WHERE_BULLETS: List<String> = listOf(
    "An unseren Server in der Schweiz, an keinen anderen.",
    "Öffentlich sind nur Werte pro 100-m-Quadrat und Meldungen, nie die Kennung, der Punkt oder die Notiz.",
    "Misst sonst niemand in der Nähe, sind es deine Werte, in einem Quadrat nahe bei dir.",
    "Messungen ohne Teilen werden nie gesendet; schaltest du es während einer Messung ein, zählt diese ganze Messung.",
)

/** «Löschen». */
val SHARE_DELETE_BULLETS: List<String> = listOf(
    "Ausschalten stoppt das Senden; «Vom Server löschen» entfernt alles, auch Meldungen.",
    "Nach 60 Tagen ohne neue Werte löscht der Server alles.",
)

/** The setting under «Teilen → Weitere Angaben». */
const val SIGMA_LABEL = "Standort verschieben"

/** One choice of the setting: «50 m». */
fun sigmaChoice(sigmaM: Int): String = "$sigmaM m"

/** The one-line explanation under the setting. */
const val SIGMA_EXPLANATION =
    "Mehr verbirgt deine genaue Adresse besser, macht die Karte aber ungenauer; gilt ab der nächsten Messung."

/** «Koordinaten einfügen»: understood, and the point is moved before it is sent. */
fun pastedText(sigmaM: Int): String = "Gefunden; gesendet wird ein um etwa $sigmaM m verschobener Punkt."

/** «Teilen»: the collapsed explanation and the collapsed placement card (0.6.3). */
const val SHARE_DETAILS_TOGGLE = "Was gesendet wird"
const val PLACEMENT_TOGGLE = "Wo liegt das Telefon? (freiwillig)"
