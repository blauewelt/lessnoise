package ch.lessnoise.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.container
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.upload.Connectivity
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportSnapshot
import ch.lessnoise.app.upload.SiteSettings
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.app.upload.Uploader
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// «Teilen» (tab of the Karten editions since 0.6.1; server/DESIGN.md §2, §9). Off by default.
// Since 0.6.3 the consent is the one question «Auf die Lärmkarte?» at the first «Starten»
// (LocationFlows.kt); the switch here has no gate (switching on before ever answering shows the
// same question). The place comes from the location fix right after «Starten», moved on the phone
// by the location noise («Standort verschieben», ShareTexts.kt). Everything network goes through
// upload/UploadClient.kt.

/** Short German labels of the placement values (server/DESIGN.md §4.2), completing «Das Telefon liegt …». */
fun placementLabel(p: String): String = when (p) {
    SiteSettings.PLACEMENT_OPEN_WINDOW -> "am offenen Fenster"
    SiteSettings.PLACEMENT_BALCONY -> "auf dem Balkon"
    SiteSettings.PLACEMENT_BEHIND_GLASS -> "hinter Glas"
    else -> "anderswo"
}

/** The status line's input from the stored upload state and the live state. */
fun shareStatusInput(s: UploadSnapshot, live: LiveState, locate: LocateState, online: Boolean) = ShareStatusInput(
    decision = s.decision, enabled = s.enabled, authFailed = s.authFailed, lastError = s.lastError,
    lastSuccessAtMs = s.lastSuccessAtMs, ackUpToMs = maxOf(s.minutesSentUpToMs, s.eventsSentUpToMs),
    running = live.running, measurementStartMs = live.measurementStartMs, hasPoint = live.sharedPoint != null,
    locate = locate, online = online,
)

/** The status line of sharing for the current state (Karten editions; [flow] from [LocalShareFlow]). */
@Composable
fun rememberShareStatus(flow: ShareFlow): ShareStatus {
    val context = LocalContext.current
    val c = context.container
    val s by c.upload.store.state.collectAsStateWithLifecycle()
    val live by c.live.collectAsStateWithLifecycle()
    val online by remember { Connectivity.online(context) }.collectAsStateWithLifecycle(true)
    return shareStatus(shareStatusInput(s, live, flow.locate, online))
}

/**
 * The one status line of sharing (0.6.3): on «Messen» and at the top of «Teilen». Tapping it does
 * what [ShareStatus.tap] says; «Karte ansehen» opens the map in the browser.
 */
@Composable
fun ShareStatusLine(status: ShareStatus, onTap: (ShareTap) -> Unit) {
    Column {
        val color = if (status.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        val tappable = status.tap != ShareTap.NONE
        Text(
            status.text, style = MaterialTheme.typography.bodyMedium, color = color,
            textDecoration = if (tappable) TextDecoration.Underline else null,
            modifier = if (tappable) Modifier.clickable { onTap(status.tap) }.padding(vertical = 4.dp) else Modifier.padding(vertical = 4.dp),
        )
        if (status.mapLink) MoreLink(Links.MAP, ShareStatusTexts.MAP_LINK)
    }
}

@Composable
fun ShareScreen(modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val flow = LocalShareFlow.current ?: return
    val module = c.upload
    val s by module.store.state.collectAsStateWithLifecycle()
    val pending by remember { module.pendingMinutes }.collectAsStateWithLifecycle(0)
    val reports by module.reports.state.collectAsStateWithLifecycle()
    val status = rememberShareStatus(flow)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmNewId by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    ShareContent(
        s = s, status = status, pending = pending, reports = reports, busy = busy, result = result, modifier = modifier,
        onStatusTap = flow::onStatusTap,
        onSharing = flow::setSharing,
        onWifiOnly = { v -> module.store.update { it.copy(wifiOnly = v) }; module.applySchedule() },
        onSigma = { v -> module.store.update { it.copy(sigmaM = v) } },
        onSite = { site -> module.store.update { it.copy(site = site) }; module.uploadSoon() },
        onDelete = { confirmDelete = true },
        onNewId = { confirmNewId = true },
    )

    fun runDelete(newIdentity: Boolean) {
        busy = "Wird gelöscht …"
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (newIdentity) module.uploader.newIdentity() else module.uploader.deleteServerData()
            }
            module.applySchedule()
            busy = null
            result = when (r) {
                Uploader.DeleteOutcome.Deleted -> if (newIdentity) "Neu gestartet." else "Vom Server gelöscht. Teilen ist aus."
                Uploader.DeleteOutcome.UnknownToServer -> "Auf dem Server liegt nichts mehr von diesem Telefon."
                is Uploader.DeleteOutcome.Failed -> "Nicht gelöscht. Bitte mit Internet nochmals versuchen."
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Daten auf dem Server löschen?") },
            text = { Text("Alles, was dieses Telefon gesendet hat, wird sofort gelöscht. Teilen wird ausgeschaltet. Auf dem Telefon bleibt alles.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; runDelete(newIdentity = false) }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmNewId) {
        AlertDialog(
            onDismissRequest = { confirmNewId = false },
            title = { Text("Neu starten?") },
            text = { Text("Deine alten Daten auf dem Server werden gelöscht; danach sendest du neu.") },
            confirmButton = { TextButton(onClick = { confirmNewId = false; runDelete(newIdentity = true) }) { Text("Neu starten") } },
            dismissButton = { TextButton(onClick = { confirmNewId = false }) { Text("Abbrechen") } },
        )
    }
}

/**
 * «Teilen» without the app container (also rendered on the JVM): the status line and the switch
 * at the top, «Weitere Angaben» (Nur über WLAN, Noch zu senden, Standort verschieben), then the
 * full explanation and the placement card, each collapsed, the reports, and deleting.
 */
@Composable
fun ShareContent(
    s: UploadSnapshot,
    status: ShareStatus,
    pending: Int,
    reports: ReportSnapshot,
    busy: String?,
    result: String?,
    onStatusTap: (ShareTap) -> Unit,
    onSharing: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onSigma: (Int) -> Unit,
    onSite: (SiteSettings) -> Unit,
    onDelete: () -> Unit,
    onNewId: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    var showPlacement by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "Auf die Lärmkarte", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        Text(SHARE_INTRO, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))

        // 1. The switch and the status line.
        SectionCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Auf die Karte senden", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = s.enabled, onCheckedChange = onSharing)
            }
            ShareStatusLine(status, onStatusTap)
            if (s.refusedTotal > 0) {
                Text("${s.refusedTotal} Werte waren zu alt und wurden nicht übernommen.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { showMore = !showMore }) { Text(if (showMore) "Weitere Angaben ▾" else "Weitere Angaben ▸") }
            if (showMore) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nur über WLAN", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = s.wifiOnly, onCheckedChange = onWifiOnly)
                }
                if (!s.wifiOnly) Text("Weniger als 0,1 MB pro Tag.", style = MaterialTheme.typography.bodySmall)
                StatRow("Noch zu senden", "$pending Minuten")
                SigmaSetting(s.sigmaM, onSigma)
            }
        }

        // 2. What is sent, what never, where, how to delete (server/DESIGN.md §2 rule 3), one tap away.
        TextButton(onClick = { showExplanation = !showExplanation }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (showExplanation) "$SHARE_DETAILS_TOGGLE ▾" else "$SHARE_DETAILS_TOGGLE ▸")
        }
        if (showExplanation) {
            SectionCard { ShareDetails(s.sigmaM) }
        }

        // 3. How the phone lies (optional).
        TextButton(onClick = { showPlacement = !showPlacement }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (showPlacement) "$PLACEMENT_TOGGLE ▾" else "$PLACEMENT_TOGGLE ▸")
        }
        if (showPlacement) PlacementCard(s.site, onSite)

        ReportsCard(reports)

        // 4. Server data.
        SectionCard("Deine Daten auf dem Server") {
            if (!s.registered) {
                Text("Von diesem Telefon liegen keine Daten auf dem Server.", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onDelete,
                enabled = s.registered && busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Vom Server löschen") }
            // «Neu starten» (a new random id): only when the server no longer knows this phone.
            if (s.authFailed) {
                Text(Uploader.AUTH_FAILED_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onNewId, enabled = busy == null) { Text("Neu starten") }
            }
            busy?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The full explanation (unchanged in substance since 0.6.2), in «Teilen» and behind «Mehr dazu» of the question. */
@Composable
fun ShareDetails(sigmaM: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailBlock("Was gesendet wird", SHARE_SENT_BULLETS)
        DetailBlock("Standort", shareLocationBullets(sigmaM))
        DetailBlock("Nie gesendet", SHARE_NEVER_BULLETS)
        DetailBlock("Wohin", SHARE_WHERE_BULLETS)
        DetailBlock("Löschen", SHARE_DELETE_BULLETS)
        MoreLink(Links.PRIVACY)
    }
}

@Composable
private fun DetailBlock(title: String, lines: List<String>) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Bullets(*lines.toTypedArray())
}

@Composable
private fun Bullets(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * «Wo liegt das Telefon? (freiwillig)»: optional details about how the phone stands, remembered for the next
 * measurements. The place itself is the shifted point determined at the start of each measurement
 * (LocationFlows.kt). Stockwerk and Notiz are under «Weitere Angaben».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacementCard(current: SiteSettings, onSave: (SiteSettings) -> Unit) {
    var placement by rememberSaveable(current) { mutableStateOf(current.placement) }
    var floorText by rememberSaveable(current) { mutableStateOf(current.floor?.toString() ?: "") }
    // 0 = Strassenseite, 1 = Hofseite, 2 = weiss nicht
    var facing by rememberSaveable(current) {
        mutableStateOf(when (current.streetFacing) { true -> 0; false -> 1; null -> 2 })
    }
    var note by rememberSaveable(current) { mutableStateOf(current.note) }
    var saved by remember { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(current.floor != null || current.note.isNotBlank()) }
    val floor = floorText.trim().toIntOrNull()
    val floorOk = floorText.isBlank() || (floor != null && floor in -3..100)

    SectionCard {
        Text("Das Telefon liegt", style = MaterialTheme.typography.bodyMedium)
        val places = SiteSettings.PLACEMENTS
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            places.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = placement == p, onClick = { placement = p; saved = false },
                    shape = SegmentedButtonDefaults.itemShape(i, places.size),
                ) { Text(placementLabel(p), maxLines = 2, style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text("Richtung", style = MaterialTheme.typography.bodyMedium)
        val sides = listOf("Strassenseite", "Hofseite", "weiss nicht")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sides.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = facing == i, onClick = { facing = i; saved = false },
                    shape = SegmentedButtonDefaults.itemShape(i, sides.size),
                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
            }
        }
        TextButton(onClick = { more = !more }) { Text(if (more) "Weitere Angaben ▾" else "Weitere Angaben ▸") }
        if (more) {
            OutlinedTextField(
                value = floorText, onValueChange = { floorText = it.filter { ch -> ch.isDigit() || ch == '-' }.take(3); saved = false },
                label = { Text("Stockwerk (freiwillig)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !floorOk, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(SiteSettings.NOTE_MAX); saved = false },
                label = { Text("Notiz (freiwillig, nie öffentlich)") },
                supportingText = { Text("${note.length}/${SiteSettings.NOTE_MAX}") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
        ) { Text("Speichern") }
        if (saved) Text("Gespeichert.", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * «Standort verschieben»: σ of the location noise (server/DESIGN.md §2 rule 2), 25 / 50 (default) /
 * 100 / 200 m, under «Weitere Angaben» of the switch card. No «exact» choice: an exact place is sent
 * only with «Laut! Melden». Applies from the next «Starten».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SigmaSetting(current: Int, onChange: (Int) -> Unit) {
    Text(SIGMA_LABEL, style = MaterialTheme.typography.bodyMedium)
    val choices = LocationNoise.SIGMAS_M
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { i, v ->
            SegmentedButton(
                selected = current == v, onClick = { onChange(v) },
                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
            ) { Text(sigmaChoice(v), style = MaterialTheme.typography.labelSmall) }
        }
    }
    Text(SIGMA_EXPLANATION, style = MaterialTheme.typography.bodySmall)
}

/** «Meldungen»: waiting and sent reports (sent ones without coordinates: category and time). */
@Composable
private fun ReportsCard(r: ReportSnapshot) {
    if (r.pending.isEmpty() && r.sent.isEmpty()) return
    SectionCard("Meldungen") {
        if (r.pending.isNotEmpty()) StatRow("Noch zu senden", r.pending.size.toString())
        StatRow("Gesendet", r.sent.size.toString())
        r.sent.takeLast(5).reversed().forEach { s ->
            Text(
                "${Fmt.dateTime(s.epochMs)} · ${ReportCategory.fromId(s.category)?.label ?: s.category}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
