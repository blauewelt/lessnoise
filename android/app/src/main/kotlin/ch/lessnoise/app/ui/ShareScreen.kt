package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.container
import ch.lessnoise.app.location.LocationFetcher
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.app.upload.SiteSettings
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.app.upload.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// «Einstellungen → Messwerte teilen» (server/DESIGN.md §2, §9). Off by default; switching on needs
// the explanation (once) and a hectare. Everything network goes through upload/UploadClient.kt.

/** Short German labels of the placement values (server/DESIGN.md §4.2). */
fun placementLabel(p: String): String = when (p) {
    SiteSettings.PLACEMENT_OPEN_WINDOW -> "Offenes Fenster"
    SiteSettings.PLACEMENT_BALCONY -> "Balkon"
    SiteSettings.PLACEMENT_BEHIND_GLASS -> "Hinter Glas"
    else -> "Anderes"
}

/**
 * The bullet about location in «Was nie gesendet wird» (v0.5.1). Shown only where «Messwerte
 * teilen» exists (Karten-Version, Labor-Version mit Karte), which are the editions with the
 * location permission.
 */
const val LOCATION_NEVER_SENT =
    "Dein genauer Standort. Die App fragt nach der Standort-Berechtigung, liest den Standort aber nur, wenn du unten bei " +
        "«Aufstellung» auf «Standort bestimmen» tippst, und dann ein einziges Mal – nie im Hintergrund. Daraus berechnet das " +
        "Telefon selbst die Hektare; nur diese wird gespeichert und gesendet, die Koordinaten weder gespeichert noch gesendet. " +
        "Die Offline-Version hat keine Standort-Berechtigung."

/** One line for the settings overview. */
fun shareSummary(s: UploadSnapshot): String = when {
    s.enabled && s.authFailed ->
        "Ein – der Server kennt diese Kennung nicht mehr, wohl weil 60 Tage lang nichts ankam (dann löscht er alle Daten). " +
            "«Neue Kennung» wählen, um neu zu beginnen."
    s.enabled -> "Ein" + (s.site?.let { " · Hektare ${it.cell}" } ?: "") +
        (s.lastSuccessAtMs?.let { " · zuletzt gesendet ${Fmt.dateTime(it)}" } ?: "")
    else -> "Aus – es wird nichts gesendet"
}

/** The card in Einstellungen that opens the screen. */
@Composable
fun ShareSettingsCard(onOpen: () -> Unit) {
    val c = LocalContext.current.container
    val s by c.upload.store.state.collectAsStateWithLifecycle()
    SectionCard("Messwerte teilen") {
        Text(
            "Freiwillig: deine Messwerte (nie Audio) für die gemeinsame Lärmkarte an den Server des Projekts in der Schweiz senden.",
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
        SectionCard("Was gesendet wird") {
            Bullets(
                "Deine Minutenwerte: Pegel (LAeq, LAFmax, LAFmin, L1, L10, L50, L90), gültige Messzeit, Anzahl Ereignisse und die Anteile der Geräuschkategorien.",
                "Deine Ereignisse: Beginn, Dauer, Spitzenpegel, SEL, Hintergrundpegel und die Kategorie (z. B. «Töff & Poser»).",
                "Die Hektare (100 × 100 m), in der das Telefon steht, und deine Angaben zur Aufstellung (Fenster, Balkon, Stockwerk, Strassenseite, Notiz).",
                "Telefonmodell, Audioquelle und Kalibrierung, damit die Werte vergleichbar sind.",
                "Eine zufällige Kennung dieses Telefons. Sie hat nichts mit dir oder dem Telefon zu tun und wird nie öffentlich gezeigt.",
            )
        }
        SectionCard("Was nie gesendet wird") {
            Bullets(
                "Audio. Die App speichert gar keines.",
                LOCATION_NEVER_SENT,
                "Name, Telefonnummer, E-Mail oder ein Konto – es gibt keine Anmeldung.",
                "Die einzelnen Klassen des Klassifikators (etwa «Speech» oder «Motorcycle»), nur die Kategorie.",
            )
        }
        SectionCard("Wohin") {
            Bullets(
                "An den Server des Projekts in der Schweiz: ${module.client.host}. Die App verbindet sich mit keinem anderen Server.",
                "Öffentlich sind nur Werte pro Hektare auf der Lärmkarte (stadtlaerm.ch/karte.html) – nie einzelne Messungen, die Kennung oder deine Notiz. Das Telefonmodell erscheint nur in einer Zählung über alle beteiligten Geräte.",
                "Beim ersten Einschalten werden die Messungen der letzten 7 Tage mitgesendet, danach nach jeder vollen Stunde und nach jeder Messung das Neue. Was du misst, während das Teilen aus ist, wird nie gesendet.",
            )
        }
        SectionCard("Löschen") {
            Bullets(
                "Ausschalten stoppt das Senden sofort.",
                "«Meine Daten auf dem Server löschen» (unten) entfernt alles von diesem Telefon sofort vom Server; die Karte wird beim nächsten Durchlauf (alle 10 Minuten) ohne deine Werte neu berechnet.",
                "Kommen 60 Tage lang keine Messwerte an – etwa weil du das Teilen ausgeschaltet, zur Offline-Version gewechselt oder die App deinstalliert hast –, löscht der Server alle Daten dieses Telefons von selbst.",
                "Deine Daten auf dem Telefon bleiben in jedem Fall, bis du sie unter «Daten» löschst.",
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
                if (!s.enabled && s.site == null) {
                    Text("Zuerst oben die Hektare festlegen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
                    else "Gesendet wird auch über mobile Daten (weniger als 0,1 MB pro Nacht).",
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
                ) { Text("Meine Daten auf dem Server löschen") }
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
                    "Alle Minutenwerte, Ereignisse und die Aufstellung, die dieses Telefon gesendet hat, werden sofort vom Server gelöscht. " +
                        "Das Teilen wird ausgeschaltet. Deine Daten auf dem Telefon bleiben.",
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
 * The placement step. Since v0.5.1 the phone determines the place itself: «Standort bestimmen» asks
 * for the location permission (Karten-Version and Labor-Version mit Karte only), gets ONE fix and
 * turns it into the hectare at once ([LocationFetcher], [LocationPolicy]); only the hectare and the
 * accuracy are shown, only the hectare is stored (with «Aufstellung speichern») and sent. Pasting
 * coordinates stays as a secondary way («Oder Koordinaten einfügen», collapsed); the typed text is not
 * stored either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacementCard(current: SiteSettings?, onSave: (SiteSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var coords by rememberSaveable { mutableStateOf("") }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var placement by rememberSaveable(current) { mutableStateOf(current?.placement ?: SiteSettings.PLACEMENT_OPEN_WINDOW) }
    var floorText by rememberSaveable(current) { mutableStateOf(current?.floor?.toString() ?: "") }
    // 0 = Strassenseite, 1 = Hofseite, 2 = keine Angabe
    var facing by rememberSaveable(current) {
        mutableStateOf(when (current?.streetFacing) { true -> 0; false -> 1; null -> 2 })
    }
    var note by rememberSaveable(current) { mutableStateOf(current?.note ?: "") }
    var saved by remember { mutableStateOf(false) }
    // «Standort bestimmen»: the running request and its outcome (hectare + accuracy, never coordinates).
    var locateJob by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<LocationPolicy.Outcome?>(null) }

    fun startLocate() {
        locateJob?.cancel()
        outcome = null
        saved = false
        locateJob = scope.launch {
            val o = LocationFetcher.locate(context)
            outcome = o
            if (o is LocationPolicy.Outcome.Hectare) coords = "" // the newest choice wins
            locateJob = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when (LocationFetcher.permission(context)) {
            LocationPolicy.Permission.PRECISE -> startLocate()
            LocationPolicy.Permission.APPROXIMATE_ONLY -> outcome = LocationPolicy.Outcome.ApproximateOnly
            LocationPolicy.Permission.DENIED -> outcome = LocationPolicy.Outcome.PermissionDenied
        }
    }

    val parsed = if (coords.isBlank()) null else CellInput.parse(coords)
    val pastedCell = (parsed as? CellInput.Parsed.Ok)?.cell
    val located = outcome as? LocationPolicy.Outcome.Hectare
    val cell = pastedCell ?: located?.cell ?: current?.cell
    val floor = floorText.trim().toIntOrNull()
    val floorOk = floorText.isBlank() || (floor != null && floor in -3..100)

    SectionCard("Aufstellung") {
        Text(
            "Wo steht das Telefon? Geh mit dem Telefon an den Messort, etwa ans Fenster, und tippe auf «Standort bestimmen». " +
                "Das Telefon rechnet daraus die Hektare (100 × 100 m); nur diese wird gespeichert und gesendet.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (BuildConfig.UPLOAD_AVAILABLE) {
            Button(
                enabled = locateJob == null,
                onClick = {
                    if (LocationFetcher.hasPreciseLocation(context)) startLocate()
                    else permissionLauncher.launch(LocationFetcher.PERMISSIONS)
                },
            ) { Text("Standort bestimmen") }
            if (!LocationFetcher.hasPreciseLocation(context)) {
                Text(LocationPolicy.RATIONALE_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (locateJob != null) {
                Text(LocationPolicy.PROGRESS_TEXT, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { locateJob?.cancel(); locateJob = null }) { Text("Abbrechen") }
            }
            outcome?.let { o ->
                when (o) {
                    is LocationPolicy.Outcome.Hectare -> if (pastedCell == null) {
                        Text(LocationPolicy.message(o), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    else -> {
                        Text(LocationPolicy.message(o), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (o) {
                                LocationPolicy.Outcome.ApproximateOnly, LocationPolicy.Outcome.PermissionDenied ->
                                    OutlinedButton(onClick = { openAppSettings(context) }) { Text("App-Einstellungen") }
                                LocationPolicy.Outcome.LocationOff ->
                                    OutlinedButton(onClick = { openLocationSettings(context) }) { Text("Standort-Einstellungen") }
                                else -> OutlinedButton(onClick = { startLocate() }, enabled = locateJob == null) { Text("Nochmals versuchen") }
                            }
                            if (!showPaste) TextButton(onClick = { showPaste = true }) { Text("Koordinaten einfügen") }
                        }
                    }
                }
            }
        }

        TextButton(onClick = { showPaste = !showPaste }) {
            Text(if (showPaste) "Oder Koordinaten einfügen ▾" else "Oder Koordinaten einfügen ▸")
        }
        if (showPaste) {
            Text(
                "Füge die Koordinaten ein, z. B. aus einer Karten-App (Punkt lange drücken, Koordinaten kopieren) " +
                    "oder von map.geo.admin.ch. Die App rechnet daraus die Hektare; nur diese wird gespeichert und gesendet.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = coords, onValueChange = { coords = it; saved = false },
                label = { Text("Koordinaten (z. B. 47.3769, 8.5417 oder 2'683'304, 1'247'925)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            parsed is CellInput.Parsed.Error -> Text(parsed.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            pastedCell == null && located != null ->
                Text("Mit «Aufstellung speichern» übernehmen.", style = MaterialTheme.typography.bodySmall)
            cell != null -> Text("Hektare: $cell – dieses Hektar wird gezeigt", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            else -> Text("Hektare: noch keine", style = MaterialTheme.typography.bodySmall)
        }

        Text("Aufstellung des Telefons", style = MaterialTheme.typography.bodyMedium)
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
            label = { Text("Stockwerk (0 = Erdgeschoss, leer = keine Angabe)") },
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
            label = { Text("Notiz (freiwillig, wird nie veröffentlicht)") },
            supportingText = { Text("${note.length}/${SiteSettings.NOTE_MAX}") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = cell != null && floorOk,
            onClick = {
                onSave(
                    SiteSettings(
                        cell = cell!!, placement = placement, floor = floor,
                        streetFacing = when (facing) { 0 -> true; 1 -> false; else -> null },
                        note = note.trim(),
                    )
                )
                coords = ""
                outcome = null
                saved = true
            },
        ) { Text("Aufstellung speichern") }
        if (saved) Text("Gespeichert.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {}
}

private fun openLocationSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {}
}
