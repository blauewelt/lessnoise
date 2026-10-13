package ch.lessnoise.app.edition

/**
 * Names and texts of the four editions (BuildConfig.EDITION). Since 0.6.0 the app is «Weniger Lärm»
 * (renamed in 0.6.0); its two published editions are the «Offline-Version» and the «Online-Version»
 * (the launcher label of both is «Weniger Lärm»); the internal flavour names stay. Pure Kotlin in app/src/main, so
 * every flavour compiles the same texts and the JVM tests check them. Which edition a build is
 * comes from the Gradle flavour (app/build.gradle.kts):
 *
 * - [OFFLINE] «Offline-Version» (flavour `offline`, ch.lessnoise.app): no internet and no location
 *   permission; the default download.
 * - [KARTE] «Online-Version» (flavour `public`, ch.lessnoise.app): internet only for the opt-in
 *   «Messwerte teilen» and «Melden», location only for one fix at the start of a shared measurement
 *   and for a report.
 * - [LABOR] «Weniger Lärm Labor» (flavour `labor`, ch.lessnoise.labor): never published, no internet.
 * - [LABOR_KARTE] «Weniger Lärm Labor · Karte» (flavour `laborKarte`, ch.lessnoise.labor): the Labor
 *   build plus the opt-in «Messwerte teilen» (audio is never sent); internal, never published.
 *
 * Offline and Karte editions share the app id and the signing key: installing one over the other
 * switches the edition and keeps the measurements.
 */
object EditionInfo {
    const val OFFLINE = "offline"
    const val KARTE = "karte"
    const val LABOR = "labor"
    const val LABOR_KARTE = "labor-karte"

    /** The edition names users see: «Offline-Version», «Online-Version», «Weniger Lärm Labor», «Weniger Lärm Labor · Karte». */
    fun label(edition: String): String = when (edition) {
        OFFLINE -> "Offline-Version"
        KARTE -> "Online-Version"
        LABOR -> "Weniger Lärm Labor"
        LABOR_KARTE -> "Weniger Lärm Labor · Karte"
        else -> edition
    }

    /** Line in Einstellungen → App-Version: which edition is installed. */
    fun summary(edition: String): String = when (edition) {
        OFFLINE -> "Offline-Version: ohne Internet und ohne Standort."
        KARTE -> "Online-Version: mit «Messwerte teilen» und «Melden» für die Lärmkarte (aus, bis du es einschaltest)."
        LABOR -> "Weniger Lärm Labor: kann Audio aufzeichnen, wird nicht veröffentlicht."
        LABOR_KARTE ->
            "Weniger Lärm Labor · Karte: kann Audio aufzeichnen und hat «Messwerte teilen» (Audio wird nie gesendet). Wird nicht veröffentlicht."
        else -> edition
    }

    /**
     * How to switch to the other published edition; null for Labor (it is a separate app), one
     * short sentence for the Labor edition with the map.
     */
    fun switchHint(edition: String): String? = when (edition) {
        OFFLINE ->
            "Für die Lärmkarte die Online-Version von wenigerlaerm.ch über diese App installieren; die Messungen bleiben erhalten."
        KARTE ->
            "Zurück zur Offline-Version ohne Internet: von wenigerlaerm.ch über diese App installieren; die Messungen bleiben erhalten. " +
                "Deine Daten auf dem Server vorher unter «Messwerte teilen» löschen, sonst löscht sie der Server nach 60 Tagen ohne neue Werte."
        LABOR_KARTE ->
            "Interne Testversion. «Weniger Lärm Labor» ohne Internet kann über diese App installiert werden; Messungen und Aufnahmen bleiben erhalten."
        else -> null
    }

    /**
     * The edition sent to update.html as `&e=`: only the two published editions, which the page
     * accepts (`^(offline|karte)$`). Labor builds send none; the page would ignore their value
     * anyway and has no Labor download.
     */
    fun updateEdition(edition: String): String? = when (edition) {
        OFFLINE, KARTE -> edition
        else -> null
    }
}
