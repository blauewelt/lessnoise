package ch.lessnoise.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.container
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.SiteSettings
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.app.upload.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// «Einstellungen → Messwerte teilen» (server/DESIGN.md §2, §9). Off by default; switching on needs
// the explanation (once; or the short consent of «Laut! Melden»). The cell comes from the location
// fix at the start of each measurement. Everything network goes through upload/UploadClient.kt.

/** Short German labels of the placement values (server/DESIGN.md §4.2). */
fun placementLabel(p: String): String = when (p) {
    SiteSettings.PLACEMENT_OPEN_WINDOW -> "Offenes Fenster"
    SiteSettings.PLACEMENT_BALCONY -> "Balkon"
    SiteSettings.PLACEMENT_BEHIND_GLASS -> "Hinter Glas"
    else -> "Anderes"
}

/**
 * The bullet about location in «Was nie gesendet wird». Shown only where «Messwerte teilen» exists
 * (the Karten editions), which are the editions with the location permission.
 */
const val LOCATION_NEVER_SENT =
    "Dein genauer Standort, ausser bei einer Meldung; die App liest ihn nie im Hintergrund."

/** «Was gesendet wird» (app 0.6.0). */
val SHARE_SENT_BULLETS: List<String> = listOf(
    "Die Minutenwerte und Lärmspitzen deiner geteilten Messungen, jeweils mit der 100-m-Zelle, in der du die Messung gestartet hast.",
    "Laute Momente einer geteilten Messung erscheinen so auf der Karte.",
    "Meldungen («Laut! Melden»): Zeit, genauer Standort und Kategorie.",
    "Telefonmodell, Audioquelle, Kalibrierung und deine Angaben zur Aufstellung.",
    "Eine zufällige Kennung dieses Telefons, nie öffentlich.",
)

/** «Standort» (app 0.6.0). */
val SHARE_LOCATION_BULLETS: List<String> = listOf(
    "Beim Start einer Messung bestimmt das Telefon einmal den Standort; behalten und gesendet wird nur die 100-m-Zelle.",
    "Bei einer Meldung wird der genaue Standort gesendet und danach auf dem Telefon gelöscht.",
)

/** One line for the settings overview. */
fun shareSummary(s: UploadSnapshot): String = when {
    s.enabled && s.authFailed ->
        "Ein – der Server kennt diese Kennung nicht mehr, wohl weil 60 Tage lang nichts ankam (dann löscht er alle Daten). " +
            "«Neue Kennung» wählen, um neu zu beginnen."
    s.enabled -> "Ein" + (s.lastSuccessAtMs?.let { " · zuletzt gesendet ${Fmt.dateTime(it)}" } ?: "")
    else -> "Aus – es wird nichts gesendet"
}

/** The card in Einstellungen that opens the screen. */
@Composable
fun ShareSettingsCard(onOpen: () -> Unit) {
    val c = LocalContext.current.container
    val s by c.upload.store.state.collectAsStateWithLifecycle()
    SectionCard("Messwerte teilen") {
        Text(
            "Freiwillig: Messwerte und Meldungen (nie Audio) für die Lärmkarte senden.",
            style = MaterialTheme.typography.bodySmall,
        )
        StatRow("Status", if (s.enabled) "Ein" else "Aus")
        Text(shareSummary(s), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpen) { Text(if (s.enabled) "Einstellungen zum Teilen" else "Mehr erfahren & einrichten") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val c = LocalContext.current.container
    val module = c.upload
    val s by module.store.state.collectAsStateWithLifecycle()
    val pending by remember { module.pendingMinutes }.collectAsStateWithLifecycle(0)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmNewId by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Einstellungen") }
        }
        Text(
            "Messwerte teilen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )

        // 1. What is sent, what never, where, how to delete.
        SectionCard("Was gesendet wird") { Bullets(*SHARE_SENT_BULLETS.toTypedArray()) }
        SectionCard("Standort") { Bullets(*SHARE_LOCATION_BULLETS.toTypedArray()) }
        SectionCard("Was nie gesendet wird") {
            Bullets(
                "Audio. Die App speichert gar keines.",
                LOCATION_NEVER_SENT,
                "Name, Telefonnummer, E-Mail oder ein Konto.",
            )
        }
        SectionCard("Wohin") {
            Bullets(
                "An ${module.client.host}, den Server des Projekts in der Schweiz, an keinen anderen.",
                "Öffentlich sind nur Werte pro Zelle auf der Lärmkarte (wenigerlaerm.ch/karte.html), nie die Kennung oder deine Notiz.",
                "Was du misst, während das Teilen aus ist, wird nie gesendet.",
            )
        }
        SectionCard("Löschen") {
            Bullets(
                "Ausschalten stoppt das Senden sofort.",
                "«Daten auf dem Server löschen» (unten) entfernt alles von diesem Telefon, auch die Meldungen.",
                "Kommen 60 Tage lang keine Werte an, löscht der Server alles von selbst.",
            )
            if (!s.explanationSeen) {
                Button(onClick = { module.store.update { it.copy(explanationSeen = true) } }) { Text("Gelesen, weiter") }
            }
        }

        if (s.explanationSeen) {
            PlacementCard(s.site) { site ->
                module.store.update { it.copy(site = site) }
                module.uploadSoon()
            }
            ReportsCard()

            // 3. The switches and the status.
            SectionCard("Teilen") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Messwerte teilen", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.enabled,
                        enabled = s.enabled || s.canEnable,
                        onCheckedChange = { on ->
                            if (on) {
                                if (module.uploader.enable()) { module.applySchedule(); module.uploadSoon(5) }
                            } else {
                                module.uploader.disable(); module.applySchedule()
                            }
                        },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nur über WLAN", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = s.wifiOnly, onCheckedChange = { v ->
                        module.store.update { it.copy(wifiOnly = v) }
                        module.applySchedule()
                    })
                }
                Text(
                    if (s.wifiOnly) "Gesendet wird nur über WLAN (genauer: über Verbindungen ohne Datenvolumen-Begrenzung)."
                    else "Gesendet wird auch über mobile Daten (weniger als 0,1 MB pro Tag).",
                    style = MaterialTheme.typography.bodySmall,
                )
                StatRow("Zuletzt gesendet", s.lastSuccessAtMs?.let { Fmt.dateTime(it) } ?: "noch nie")
                StatRow("Noch zu senden", "$pending Minuten")
                if (s.refusedTotal > 0) {
                    Text(
                        "${s.refusedTotal} Werte hat der Server als ungültig abgelehnt (z. B. älter als 7 Tage); sie werden nicht erneut gesendet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // While the token is refused, always the current explanation (an older stored text may differ).
                (if (s.authFailed) Uploader.AUTH_FAILED_TEXT else s.lastError)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            // 4. Server data.
            SectionCard("Deine Daten auf dem Server") {
                Text(
                    if (s.registered) "Dieses Telefon ist auf dem Server unter einer zufälligen Kennung eingetragen."
                    else "Von diesem Telefon liegen keine Daten auf dem Server.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { confirmDelete = true },
                    enabled = s.registered && busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Daten auf dem Server löschen") }
                OutlinedButton(onClick = { confirmNewId = true }, enabled = (s.registered || s.authFailed) && busy == null) {
                    Text("Neue Kennung")
                }
                Text(
                    "«Neue Kennung» löscht deine Daten auf dem Server und sendet künftig unter einer neuen Kennung; Messungen von vorher werden nicht erneut gesendet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                busy?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    fun runDelete(newIdentity: Boolean) {
        busy = if (newIdentity) "Neue Kennung: Daten auf dem Server werden gelöscht …" else "Daten auf dem Server werden gelöscht …"
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (newIdentity) module.uploader.newIdentity() else module.uploader.deleteServerData()
            }
            module.applySchedule()
            busy = null
            result = when (r) {
                Uploader.DeleteOutcome.Deleted ->
                    if (newIdentity) "Gelöscht. Ab dem nächsten Senden wird eine neue Kennung verwendet."
                    else "Gelöscht: Der Server hat bestätigt, dass alle Daten dieses Telefons entfernt sind. Das Teilen ist ausgeschaltet."
                Uploader.DeleteOutcome.UnknownToServer ->
                    "Der Server kennt diese Kennung nicht (mehr), zum Beispiel weil er nach 60 Tagen ohne Messwerte alles gelöscht hat; dort liegt nichts mehr, das zu ihr gehört. Die Kennung wurde auf dem Telefon vergessen."
                is Uploader.DeleteOutcome.Failed -> "Nicht gelöscht (${r.message}). Bitte mit Internetverbindung erneut versuchen."
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Daten auf dem Server löschen?") },
            text = {
                Text(
                    "Alles, was dieses Telefon gesendet hat, auch Meldungen, wird sofort vom Server gelöscht. " +
                        "Das Teilen wird ausgeschaltet; deine Messungen auf dem Telefon bleiben.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; runDelete(newIdentity = false) }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmNewId) {
        AlertDialog(
            onDismissRequest = { confirmNewId = false },
            title = { Text("Neue Kennung?") },
            text = { Text("Deine Daten auf dem Server werden gelöscht, und künftig wird unter einer neuen, zufälligen Kennung gesendet.") },
            confirmButton = { TextButton(onClick = { confirmNewId = false; runDelete(newIdentity = true) }) { Text("Neue Kennung") } },
            dismissButton = { TextButton(onClick = { confirmNewId = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Bullets(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * «Aufstellung»: optional details about how the phone stands, remembered for the next measurements.
 * The place itself is the cell determined at the start of each measurement (LocationFlows.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacementCard(current: SiteSettings, onSave: (SiteSettings) -> Unit) {
    var placement by rememberSaveable(current) { mutableStateOf(current.placement) }
    var floorText by rememberSaveable(current) { mutableStateOf(current.floor?.toString() ?: "") }
    // 0 = Strassenseite, 1 = Hofseite, 2 = keine Angabe
    var facing by rememberSaveable(current) {
        mutableStateOf(when (current.streetFacing) { true -> 0; false -> 1; null -> 2 })
    }
    var note by rememberSaveable(current) { mutableStateOf(current.note) }
    var saved by remember { mutableStateOf(false) }
    val floor = floorText.trim().toIntOrNull()
    val floorOk = floorText.isBlank() || (floor != null && floor in -3..100)

    SectionCard("Aufstellung (freiwillig)") {
        val places = SiteSettings.PLACEMENTS
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            places.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = placement == p, onClick = { placement = p; saved = false },
                    shape = SegmentedButtonDefaults.itemShape(i, places.size),
                ) { Text(placementLabel(p), maxLines = 2, style = MaterialTheme.typography.labelSmall) }
            }
        }
        OutlinedTextField(
            value = floorText, onValueChange = { floorText = it.filter { ch -> ch.isDigit() || ch == '-' }.take(3); saved = false },
            label = { Text("Stockwerk (0 = Erdgeschoss)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !floorOk, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Mikrofon zeigt zur", style = MaterialTheme.typography.bodyMedium)
        val sides = listOf("Strasse", "Hofseite", "keine Angabe")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sides.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = facing == i, onClick = { facing = i; saved = false },
                    shape = SegmentedButtonDefaults.itemShape(i, sides.size),
                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
            }
        }
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(SiteSettings.NOTE_MAX); saved = false },
            label = { Text("Notiz (wird nie veröffentlicht)") },
            supportingText = { Text("${note.length}/${SiteSettings.NOTE_MAX}") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = floorOk,
            onClick = {
                onSave(
                    SiteSettings(
                        placement = placement, floor = floor,
                        streetFacing = when (facing) { 0 -> true; 1 -> false; else -> null },
                        note = note.trim(),
                    )
                )
                saved = true
            },
        ) { Text("Aufstellung speichern") }
        if (saved) Text("Gespeichert.", style = MaterialTheme.typography.bodySmall)
    }
}

/** «Meldungen»: waiting and sent reports (sent ones without coordinates: category, time, cell). */
@Composable
private fun ReportsCard() {
    val c = LocalContext.current.container
    val r by c.upload.reports.state.collectAsStateWithLifecycle()
    if (r.pending.isEmpty() && r.sent.isEmpty()) return
    SectionCard("Meldungen") {
        if (r.pending.isNotEmpty()) StatRow("Noch zu senden", r.pending.size.toString())
        StatRow("Gesendet", r.sent.size.toString())
        r.sent.takeLast(5).reversed().forEach { s ->
            Text(
                "${Fmt.dateTime(s.epochMs)} · ${ReportCategory.fromId(s.category)?.label ?: s.category}" + (s.cell?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
