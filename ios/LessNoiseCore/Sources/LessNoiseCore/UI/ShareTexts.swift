import Foundation

// The German texts of «Teilen», the consent, the status line and the map banner (ui/ShareTexts.kt),
// the same strings as the Android app. Single source: the Kotlin constants. The golden fixture
// Tests/Fixtures/texts.json is written from them by the Kotlin generator, and TextsGoldenTests
// fails on any difference, so a text changed on Android cannot silently drift here.
// server/DESIGN.md §2 rule 2 is the contract for the location texts, rule 3 for the consent.

public enum ShareTexts {
    /** One line under the title of «Teilen». */
    public static let SHARE_INTRO = "Deine Messwerte erscheinen auf der Lärmkarte – als Wert pro 100-m-Quadrat."

    /** The bullet about location in «Nie gesendet». */
    public static let LOCATION_NEVER_SENT = "Dein genauer Standort (ausser bei einer Meldung); nie im Hintergrund gelesen."

    /** «Was gesendet wird». */
    public static let SHARE_SENT_BULLETS: [String] = [
        "Lärmwerte pro Minute und Lärmspitzen, mit Geräuschart (z. B. «Töff & Poser»).",
        "Ein zufällig verschobener Punkt statt deines Standorts, und wo das Telefon liegt.",
        "Meldungen: Zeit, genauer Standort, Art des Lärms.",
        "Telefonmodell, Audioquelle, Kalibrierung und eine zufällige Kennung.",
    ]

    /** «Standort», for the chosen σ of «Standort verschieben». */
    public static func shareLocationBullets(_ sigmaM: Int) -> [String] {
        [
            "Nach dem Starten liest das Telefon einmal den Standort, solange die App offen ist, und verschiebt ihn sofort zufällig um etwa \(sigmaM) m; " +
                "nur dieser Punkt wird gespeichert und gesendet.",
            "Am selben Ort immer gleich verschoben – auch viele Messungen verraten den genauen Ort nicht.",
            "Meist (86 von 100 Mal) liegt dein echter Ort trotzdem näher als \(2 * sigmaM) m beim gesendeten Punkt.",
            "Bei einer Meldung der genaue Standort; danach wird er auf dem Telefon gelöscht.",
        ]
    }

    /** «Wohin». */
    public static let SHARE_WHERE_BULLETS: [String] = [
        "An unseren Server in der Schweiz, an keinen anderen.",
        "Öffentlich sind nur Werte pro 100-m-Quadrat und Meldungen, nie die Kennung, der Punkt oder die Notiz.",
        "Misst sonst niemand in der Nähe, sind es deine Werte, in einem Quadrat nahe bei dir.",
        "Schaltest du Teilen während einer Messung ein, kommt diese ganze Messung auf die Karte, frühere nie.",
    ]

    /** «Löschen». */
    public static let SHARE_DELETE_BULLETS: [String] = [
        "Ausschalten stoppt das Senden; «Vom Server löschen» entfernt alles, auch Meldungen.",
        "Nach 60 Tagen ohne neue Werte löscht der Server alles.",
    ]

    /** «Wann»: the cadence of 0.6.3 (server/DESIGN.md §4.6). */
    public static let SHARE_WHEN_BULLETS: [String] = [
        "Während einer Messung jede Lärmspitze nach etwa 30 Sekunden und die Minutenwerte alle 2 Minuten, nach dem Stoppen sofort, sonst stündlich.",
        "Über WLAN oder mobile Daten, höchstens etwa 3 MB pro Tag.",
    ]

    // ---- «Auf die Lärmkarte?»: the one question at the first «Starten» ---------------------------

    public static let CONSENT_TITLE = "Auf die Lärmkarte?"

    public static func consentLines(_ sigmaM: Int) -> [String] {
        [
            "Gesendet: Lärmwerte pro Minute und Lärmspitzen, mit einem um etwa \(sigmaM) m verschobenen Punkt statt deines Standorts – an unseren Server in der Schweiz.",
            "Nie gesendet: Audio, Name, dein genauer Standort (ausser bei «Laut! Melden»).",
            "Ausschalten und löschen jederzeit unter «Teilen»; nach 60 Tagen ohne neue Werte löscht der Server alles.",
        ]
    }

    public static let CONSENT_YES = "Ja, auf die Karte"
    public static let CONSENT_NO = "Nur auf dem Telefon"
    public static let CONSENT_MORE = "Mehr dazu"

    // ---- The status line on «Messen» and «Teilen» -------------------------------------------------

    public static let STATUS_OFF = "Nicht auf der Karte – tippen zum Teilen"
    public static let STATUS_LOCATING = "Standort wird bestimmt …"
    public static let STATUS_NO_POINT = "Kein Standort – tippen"
    public static let STATUS_FIRST_VALUES = "Wird geteilt – erste Werte in etwa einer Minute"
    public static let STATUS_ON_IDLE = "Teilen ist an – deine nächste Messung kommt auf die Karte"
    public static let STATUS_NO_INTERNET = "Kein Internet – wird nachgeholt"
    public static let STATUS_SERVER_BUSY = "Nicht gesendet: Server nicht erreichbar – wird nachgeholt"
    public static let STATUS_REJECTED = "Nicht gesendet: vom Server abgelehnt"
    public static let STATUS_AUTH = "Unterbrochen – tippen für «Neu starten»"

    /** «Auf der Karte · zuletzt gesendet 14:05». */
    public static func statusSent(_ hm: String) -> String { "Auf der Karte · zuletzt gesendet \(hm)" }

    // ---- The banner above the map in the tab «Karte» -----------------------------------------------

    public static let BANNER_NOT_SHARING = "Du teilst nicht – tippen zum Teilen"
    public static let BANNER_NO_POINT = "Noch kein Standort – Werte erscheinen danach"
    public static let BANNER_FIRST_VALUES = "Erste Werte werden gesendet – auf der Karte in etwa einer Minute"

    /** «Gesendet 14:05 – die Karte aktualisiert sich laufend». */
    public static func bannerSent(_ hm: String) -> String { "Gesendet \(hm) – die Karte aktualisiert sich laufend" }

    /** The setting under «Teilen → Weitere Angaben». */
    public static let SIGMA_LABEL = "Standort verschieben"

    /** One choice of the setting: «50 m». */
    public static func sigmaChoice(_ sigmaM: Int) -> String { "\(sigmaM) m" }

    public static let SIGMA_EXPLANATION =
        "Mehr verbirgt deine genaue Adresse besser, macht die Karte aber ungenauer; gilt ab der nächsten Messung."

    /** «Koordinaten einfügen»: understood, and the point is moved before it is sent. */
    public static func pastedText(_ sigmaM: Int) -> String { "Gefunden; gesendet wird ein um etwa \(sigmaM) m verschobener Punkt." }
}
