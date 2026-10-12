package ch.lessnoise.app.ui

import ch.lessnoise.dsp.geo.LocationNoise

// Texts of «Messwerte teilen» about the location (app 0.6.0), as plain Kotlin so that the JVM tests
// check them (LocationPolicyTest). Shown only where «Messwerte teilen» exists (the Karten editions),
// which are the editions with the location permission. server/DESIGN.md §2 rule 2 is the contract.

/** The bullet about location in «Was nie gesendet wird». */
const val LOCATION_NEVER_SENT =
    "Dein genauer Standort, ausser bei einer Meldung; die App liest ihn nie im Hintergrund."

/** «Was gesendet wird». */
val SHARE_SENT_BULLETS: List<String> = listOf(
    "Die Minutenwerte und Lärmspitzen deiner geteilten Messungen, jeweils mit dem Ort, an dem du die Messung gestartet hast – " +
        "vorher auf dem Telefon zufällig verschoben (siehe «Standort»).",
    "Laute Momente einer geteilten Messung erscheinen so auf der Karte.",
    "Meldungen («Laut! Melden»): Zeit, genauer Standort und Kategorie.",
    "Telefonmodell, Audioquelle, Kalibrierung und deine Angaben zur Aufstellung.",
    "Eine zufällige Kennung dieses Telefons, nie öffentlich.",
)

/** «Standort», for the chosen σ of «Standort verschieben». */
fun shareLocationBullets(sigmaM: Int): List<String> = listOf(
    "Beim Start einer Messung bestimmt das Telefon einmal den Standort und verschiebt ihn sofort zufällig um etwa $sigmaM m. " +
        "Gesendet und behalten wird nur der verschobene Punkt, nie der genaue Standort.",
    "Am selben Ort wird immer gleich verschoben. So lässt sich der genaue Ort auch aus vielen Messungen nicht herausrechnen.",
    "Wer die Einstellung kennt, weiss trotzdem: Meist (etwa 86 von 100 Mal) liegt der echte Ort näher als ${2 * sigmaM} m " +
        "beim gesendeten Punkt. Die Karte zeigt die Messung im 100-m-Feld dieses Punkts; misst sonst niemand in der Gegend, " +
        "liegt dein Feld also nahe bei dir.",
    "Bei einer Meldung wird der genaue Standort gesendet und danach auf dem Telefon gelöscht.",
)

/** «Standort verschieben: um etwa 50 m (Standard)». */
fun sigmaLabel(sigmaM: Int): String =
    "Standort verschieben: um etwa $sigmaM m" + if (sigmaM == LocationNoise.DEFAULT_SIGMA_M) " (Standard)" else ""

/** The one-line explanation under the setting. */
const val SIGMA_EXPLANATION =
    "Der gesendete Punkt wird zufällig um etwa diese Strecke verschoben, damit niemand daraus die genaue Adresse ablesen kann. " +
        "Ein grösserer Wert verbirgt sie besser, macht die Karte aber ungenauer."

/** Shown under the setting: it applies from the next measurement on. */
const val SIGMA_APPLIES =
    "Gilt ab der nächsten Messung; laufende und frühere Messungen behalten ihren Punkt."

/** «Koordinaten einfügen»: what was understood, and that the point is moved before it is sent. */
fun pastedText(how: String, sigmaM: Int): String =
    "Erkannt ($how). Gesendet wird ein um etwa $sigmaM m verschobener Punkt."

/** On the measuring screen while a shared measurement runs. */
fun sharedMeasurementLine(sigmaM: Int): String =
    "Wird für die Karte geteilt (Standort um etwa $sigmaM m verschoben)."
