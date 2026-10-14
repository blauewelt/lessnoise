package ch.lessnoise.app.ui

// Texts of «Teilen» (app 0.6.2), as plain Kotlin so that the JVM tests check them
// (LocationPolicyTest). Shown only where «Teilen» exists (the Online-Version), which is the edition
// with the location permission. server/DESIGN.md §2 rule 2 is the contract for the location texts.

/** One line under the title of «Teilen». */
const val SHARE_INTRO = "Deine Messwerte erscheinen auf der Lärmkarte – als Wert pro 100-m-Quadrat."

/** The bullet about location in «Nie gesendet». */
const val LOCATION_NEVER_SENT = "Dein genauer Standort (ausser bei einer Meldung); nie im Hintergrund gelesen."

/** «Was gesendet wird». */
val SHARE_SENT_BULLETS: List<String> = listOf(
    "Lärmwerte pro Minute und Lärmspitzen, mit Geräuschart (z. B. «Töff & Poser»).",
    "Ein zufällig verschobener Punkt statt deines Standorts, und wo das Telefon liegt.",
    "Meldungen: Zeit, genauer Standort, Art des Lärms.",
    "Telefonmodell, Audioquelle, Kalibrierung und eine zufällige Kennung.",
)

/**
 * «Standort», for the chosen σ of «Standort verschieben»: read once at «Starten» and moved at once;
 * the residual risk (2σ ≈ 86 %) stated as server/DESIGN.md §2 rule 2 asks; exact only for a report.
 */
fun shareLocationBullets(sigmaM: Int): List<String> = listOf(
    "Beim Starten liest das Telefon einmal den Standort und verschiebt ihn sofort zufällig um etwa $sigmaM m; " +
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
    "Was du bei ausgeschaltetem Teilen misst, wird nie gesendet.",
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

/** On «Messen» while a shared measurement runs. */
fun sharedMeasurementLine(sigmaM: Int): String = "Wird auf die Lärmkarte geteilt (Standort um etwa $sigmaM m verschoben)."
