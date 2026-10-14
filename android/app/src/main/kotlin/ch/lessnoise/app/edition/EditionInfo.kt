package ch.lessnoise.app.edition

/**
 * Names and texts of the four editions (BuildConfig.EDITION). Since 0.6.0 the app is «Weniger Lärm»
 * (renamed in 0.6.0); since 0.6.2 its two published editions are the «Offline-Version» and the
 * «Online-Version» (the launcher label of both is «Weniger Lärm»); the internal flavour names stay.
 * Pure Kotlin in app/src/main, so every flavour compiles the same texts and the JVM tests check them. Which edition a build is
 * comes from the Gradle flavour (app/build.gradle.kts):
 *
 * - [KARTE] «Online-Version» (flavour `public`, ch.lessnoise.app): internet only for the opt-in
 *   «Teilen» and «Laut! Melden», location only for one fix at the start of a shared measurement
 *   and for a report; the default download since 0.6.2, listed first on the website.
 * - [OFFLINE] «Offline-Version» (flavour `offline`, ch.lessnoise.app): no internet and no location
 *   permission; the alternative for even more privacy (no map, no reports).
 * - [LABOR] «Weniger Lärm Labor» (flavour `labor`, ch.lessnoise.labor): never published, no internet.
 * - [LABOR_KARTE] «Weniger Lärm Labor · Karte» (flavour `laborKarte`, ch.lessnoise.labor): the Labor
 *   build plus the opt-in «Messwerte teilen» (audio is never sent); internal, never published.
 *
 * Offline- and Online-Version share the app id and the signing key: installing one over the other
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

    /** Line in Einstellungen › «Über die App»: which edition is installed. */
    fun summary(edition: String): String = when (edition) {
        OFFLINE -> "Offline-Version: ohne Internet und ohne Standort."
        KARTE -> "Online-Version: gesendet wird nur, wenn du «Teilen» einschaltest."
        LABOR -> "Weniger Lärm Labor: kann Audio aufzeichnen, wird nicht veröffentlicht."
        LABOR_KARTE ->
            "Weniger Lärm Labor · Karte: kann Audio aufzeichnen und hat «Teilen» (Audio wird nie gesendet). Wird nicht veröffentlicht."
        else -> edition
    }

    /**
     * How to switch to the other published edition (install it over this one; the measurements
     * stay); null for Labor (a separate app). For the Online-Version it keeps the one thing that
     * cannot be done later: delete the server data first.
     */
    fun switchHint(edition: String): String? = when (edition) {
        OFFLINE -> "Für die Lärmkarte: die Online-Version von wenigerlaerm.ch installieren. Deine Messungen bleiben."
        KARTE ->
            "Noch mehr Privatsphäre: die Offline-Version ohne Internet von wenigerlaerm.ch installieren. Deine Messungen bleiben. " +
                "Daten auf dem Server vorher unter «Teilen» löschen, sonst löscht sie der Server nach 60 Tagen."
        LABOR_KARTE ->
            "Interne Testversion. «Weniger Lärm Labor» ohne Internet kann über diese App installiert werden; Messungen und Aufnahmen bleiben."
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
