package ch.lessnoise.app.edition

/**
 * Names and texts of the four editions (BuildConfig.EDITION). Pure Kotlin in app/src/main, so
 * every flavour compiles the same texts and the JVM tests check them. Which edition a build is
 * comes from the Gradle flavour (app/build.gradle.kts):
 *
 * - [OFFLINE] «Offline-Version» (flavour `offline`, ch.lessnoise.app): no internet and (v0.5.1) no location permission;
 *   the default download, GitHub release asset stadtlaerm.apk.
 * - [KARTE] «Karten-Version» (flavour `public`, ch.lessnoise.app): internet only for the opt-in
 *   «Messwerte teilen», location (v0.5.1) only for its «Standort bestimmen»; file stadtlaerm-karte.apk.
 * - [LABOR] «Labor-Version» (flavour `labor`, ch.lessnoise.labor): never published, no internet.
 * - [LABOR_KARTE] «Labor-Version mit Karte» (flavour `laborKarte`, ch.lessnoise.labor): the Labor
 *   build plus the opt-in «Messwerte teilen» (audio is never sent); internal, never published.
 *
 * Offline and Karten-Version share the app id and the signing key: installing one over the other
 * switches the edition and keeps the measurements.
 */
object EditionInfo {
    const val OFFLINE = "offline"
    const val KARTE = "karte"
    const val LABOR = "labor"
    const val LABOR_KARTE = "labor-karte"

    /** «Offline-Version», «Karten-Version», «Labor-Version», «Labor-Version mit Karte». */
    fun label(edition: String): String = when (edition) {
        OFFLINE -> "Offline-Version"
        KARTE -> "Karten-Version"
        LABOR -> "Labor-Version"
        LABOR_KARTE -> "Labor-Version mit Karte"
        else -> edition
    }

    /** Line in Einstellungen → App-Version: which edition is installed. */
    fun summary(edition: String): String = when (edition) {
        OFFLINE -> "Offline-Version: ohne Internet- und ohne Standort-Berechtigung."
        KARTE -> "Karten-Version: mit «Messwerte teilen» für die Lärmkarte (aus, bis du es einschaltest)."
        LABOR -> "Labor-Version: kann Audio aufzeichnen, wird nicht veröffentlicht."
        LABOR_KARTE ->
            "Labor-Version mit Karte: kann Audio aufzeichnen und hat «Messwerte teilen» (aus, bis du es einschaltest; " +
                "Audio wird nie gesendet). Wird nicht veröffentlicht."
        else -> edition
    }

    /**
     * How to switch to the other published edition; null for Labor (it is a separate app), one
     * short sentence for the Labor-Version mit Karte.
     */
    fun switchHint(edition: String): String? = when (edition) {
        OFFLINE ->
            "Wer Messwerte für die Lärmkarte teilen möchte, installiert die Karten-Version von stadtlaerm.ch über diese App. Die Messungen bleiben erhalten."
        KARTE ->
            "Zur Offline-Version wechselst du, indem du sie von stadtlaerm.ch über diese App installierst. Die Messungen bleiben erhalten, " +
                "«Messwerte teilen» wird ausgeschaltet. Willst du deine Daten auf dem Server sofort löschen, tu das vorher unter «Messwerte teilen»: " +
                "In der Offline-Version geht das nicht. Sonst löscht der Server sie von selbst, wenn 60 Tage lang keine Messwerte mehr ankommen."
        LABOR_KARTE ->
            "Interne Testversion. Die Labor-Version ohne Internet kann über diese App installiert werden: Messungen und Aufnahmen bleiben erhalten, " +
                "«Messwerte teilen» wird ausgeschaltet."
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
