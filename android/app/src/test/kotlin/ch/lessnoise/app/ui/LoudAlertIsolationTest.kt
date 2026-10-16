package ch.lessnoise.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.6.3: «Unterwegs» and the «Sehr laut» notification exist only in the editions with the map
 * (src/karte, flavours public and laborKarte). Runs in every flavour: in offline and labor
 * [LoudAlerts] is the stand-in of src/nomap. The dex of the debug APKs is checked as well
 * (WORKLOG; android/README.md release checklist).
 */
class LoudAlertIsolationTest {
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/karte").isDirectory }

    @Test
    fun onlyTheEditionsWithTheMapHaveIt() {
        assertEquals(MapTab.AVAILABLE, LoudAlerts.AVAILABLE)
    }

    @Test
    fun theSharedCodeOnlyCallsTheFacade() {
        val main = File(app, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders = main.filter { f ->
            val t = f.readText()
            t.contains("ch.lessnoise.app.alert") || t.contains("LoudAlertRule") || t.contains("LoudAlertNotifier") ||
                t.contains("\"loud_alert\"") || t.contains("IMPORTANCE_HIGH")
        }.map { it.name }
        assertEquals(emptyList(), offenders)
        // The stand-in does nothing: no channel, no notification, no permission prompt (0.6.4: no «Unterwegs» button either).
        val stub = File(app, "src/nomap/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
        assertTrue(stub.contains("const val AVAILABLE = false"))
        assertTrue(stub.contains("fun rememberUnterwegsPermission(): () -> Unit = {}"))
        for (bad in listOf("Notification", "notify", "onCheckedChange", "SharedPreferences", "alert.")) assertFalse(stub.contains(bad), bad)
        // No new permission: POST_NOTIFICATIONS was already declared for the measurement's notification.
        assertTrue(File(app, "src/main/AndroidManifest.xml").readText().contains("android.permission.POST_NOTIFICATIONS"))
        for (m in listOf("src/public/AndroidManifest.xml", "src/laborKarte/AndroidManifest.xml", "src/offline/AndroidManifest.xml", "src/labor/AndroidManifest.xml")) {
            val t = File(app, m).takeIf { it.isFile }?.readText() ?: continue
            assertFalse(t.contains("loud_alert") || t.contains("REPORT_LOUD"), m)
        }
    }
}
