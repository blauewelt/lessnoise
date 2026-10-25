package ch.lessnoise.app.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.Color
import ch.lessnoise.app.icons.AppIcons
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.container
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportSnapshot
import ch.lessnoise.app.upload.SentReport
import ch.lessnoise.app.upload.SiteSettings
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.app.upload.Uploader
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// «Teilen» (tab of the Karten editions since 0.6.1; server/DESIGN.md §2, §9). Since 0.6.3: the
// status line and the switch on top, no gate (the one question «Auf die Lärmkarte?» comes from
// ShareFlow.kt when the switch is turned on for the first time). Since 0.8.8 in two sections, the
// window measurements and the reports on the road (ShareContent). The place comes from the location fix of each measurement, moved on the
// phone by the location noise («Standort verschieben», ShareTexts.kt). Everything network of the
// upload goes through upload/UploadClient.kt.

/** Short German labels of the placement values (server/DESIGN.md §4.2), completing «Das Telefon liegt …». */
fun placementLabel(p: String): String = when (p) {
    SiteSettings.PLACEMENT_OPEN_WINDOW -> "am offenen Fenster"
    SiteSettings.PLACEMENT_BALCONY -> "auf dem Balkon"
    SiteSettings.PLACEMENT_BEHIND_GLASS -> "hinter Glas"
    else -> "anderswo"
}

@Composable
fun ShareScreen(modifier: Modifier = Modifier, onOpen: (Screen) -> Unit = {}) {
    val c = LocalContext.current.container
    val module = c.upload
    val share = LocalShareController.current
    val s by module.store.state.collectAsStateWithLifecycle()
    val pending by remember { module.pendingMinutes }.collectAsStateWithLifecycle(0)
    val reports by module.reports.state.collectAsStateWithLifecycle()
    val status = rememberShareStatus()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmNewId by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    ShareContent(
        s = s, status = status, pending = pending, reports = reports, busy = busy, result = result, modifier = modifier,
        onSwitch = { on ->
            when {
                share == null -> Unit
                on -> share.switchOn(ask = false)
                else -> share.switchOff()
            }
        },
        onStatus = { a -> handleShareAction(a, share, onOpen) },
        onWifiOnly = { v -> module.store.update { it.copy(wifiOnly = v) }; module.applySchedule() },
        onSigma = { v -> module.store.update { it.copy(sigmaM = v) } },
        onSaveSite = { site -> module.store.update { it.copy(site = site) }; module.uploadSoon() },
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
            confirmButton = { DestructiveTextButton("Löschen") { confirmDelete = false; runDelete(newIdentity = false) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmNewId) {
        AlertDialog(
            onDismissRequest = { confirmNewId = false },
            title = { Text("Neu starten?") },
            text = { Text("Deine alten Daten auf dem Server werden gelöscht; danach sendest du neu.") },
            confirmButton = { DestructiveTextButton("Neu starten") { confirmNewId = false; runDelete(newIdentity = true) } },
            dismissButton = { TextButton(onClick = { confirmNewId = false }) { Text("Abbrechen") } },
        )
    }
}

/**
 * «Teilen» without the app container (also rendered on the JVM, ShareScreenshots, PolishScreenshots).
 * 0.8.8 (the owner: most choices are about the long measurement at the window): four sections —
 * 1. «Messungen am Fenster»: the switch, the status chip, then always visible «Nur über WLAN»,
 *    «Standort verschieben», «Noch zu senden» and the fold-out «Wo liegt das Telefon? (freiwillig)»;
 *    with sharing off those rows are drawn disabled ([WindowSettings]).
 * 2. «Meldungen unterwegs»: what «Laut! Melden» sent, and that it uses the same switch.
 * 3. «Was gesendet wird» (both kinds), a fold-out.
 * 4. «Deine Daten auf dem Server».
 * Until 0.8.7: title «Auf die Lärmkarte», one card with «Weitere Angaben» folded, «Meldungen» further down.
 */
@Composable
fun ShareContent(
    s: UploadSnapshot,
    status: ShareStatus?,
    pending: Int,
    reports: ReportSnapshot,
    busy: String?,
    result: String?,
    onSwitch: (Boolean) -> Unit,
    onStatus: (ShareAction) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onSigma: (Int) -> Unit,
    onSaveSite: (SiteSettings) -> Unit,
    onDelete: () -> Unit,
    onNewId: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    var showPlacement by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ScreenTitle(SHARE_TITLE)
        HelperText(SHARE_INTRO, Modifier.padding(horizontal = SCREEN_GUTTER))
        Spacer(Modifier.height(8.dp))

        // 1. The window measurements: the switch and the status (server/DESIGN.md §2 rule 3: no gate
        // since 0.6.3; the first switch-on asks «Auf die Lärmkarte?», which says what is sent, never,
        // where and how to delete), then its settings, always visible since 0.8.8.
        SectionCard(rows = true) {
            CardHeader(AppIcons.Window, WINDOW_SECTION_TITLE, WINDOW_SECTION_SUBTITLE)
            SwitchRow("Auf die Karte senden", s.enabled, onSwitch, icon = AppIcons.Map)
            CardBlock {
                status?.let { StatusChip(it, onStatus) }
                if (s.refusedTotal > 0) {
                    Text("${s.refusedTotal} Werte waren zu alt und wurden nicht übernommen.", style = MaterialTheme.typography.bodySmall)
                }
                // While the token is refused, the current explanation of «Neu starten».
                if (s.enabled && s.authFailed) {
                    Text(Uploader.AUTH_FAILED_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            WindowSettings(enabled = s.enabled) {
                SwitchRow(
                    "Nur über WLAN", s.wifiOnly, onWifiOnly, icon = AppIcons.Wifi, enabled = s.enabled,
                    supporting = if (!s.wifiOnly) "Höchstens etwa 3 MB pro Tag." else null,
                )
                CardBlock {
                    SigmaSetting(s.sigmaM, onSigma, enabled = s.enabled)
                    StatRow(PENDING_LABEL, pendingMinutesText(pending))
                }
                FoldRow(
                    "Wo liegt das Telefon? (freiwillig)", showPlacement && s.enabled, { showPlacement = !showPlacement },
                    leading = AppIcons.Tune, enabled = s.enabled,
                )
                if (showPlacement && s.enabled) PlacementCard(s.site, onSaveSite)
            }
        }

        // 2. The reports on the road («Laut! Melden»): exact place, sent only by the tap «Senden».
        ReportsCard(reports)

        // 3. What is sent, what never, where, when, how to delete — for both kinds; folded away, unchanged in substance.
        SectionCard(rows = true) {
            FoldRow("Was gesendet wird", showExplanation, { showExplanation = !showExplanation }, leading = Icons.Outlined.Info)
            if (showExplanation) CardBlock { ShareExplanation(s.sigmaM) }
        }

        // 4. Server data. 0.8.6: the destructive action outlined in the error colour (the dialog is the guard).
        SectionCard("Deine Daten auf dem Server") {
            if (!s.registered) {
                Text("Von diesem Telefon liegen keine Daten auf dem Server.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DestructiveButton("Vom Server löschen", onDelete, enabled = s.registered && busy == null)
            // «Neu starten» (a new random id): only when the server no longer knows this phone.
            if (s.authFailed) {
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

/**
 * 0.8.8: the rows under the switch of «Messungen am Fenster». With sharing off they stay visible but
 * are drawn disabled (Material's 38 % for disabled content) and do not react, so it is plain that
 * they only matter with sharing on, and what switching on involves can still be read.
 */
@Composable
private fun WindowSettings(enabled: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)) { content() }
}

/** Material's alpha of disabled content. */
const val DISABLED_ALPHA = 0.38f

/**
 * «Wo liegt das Telefon? (freiwillig)»: optional details about how the phone stands, remembered for the next
 * measurements. The place itself is the shifted point determined at the start of each measurement
 * (LocationFlows.kt). Stockwerk and Notiz are under its own «Weitere Angaben» (a FoldButton).
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

    CardBlock {
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
        FoldButton("Weitere Angaben", more, { more = !more })
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
 * 100 / 200 m, in «Messungen am Fenster» (0.6.2–0.8.7 under «Weitere Angaben»). No «exact» choice: an exact place is sent
 * only with «Laut! Melden». Applies from the next «Starten».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SigmaSetting(current: Int, onChange: (Int) -> Unit, enabled: Boolean = true) {
    Text(SIGMA_LABEL, style = MaterialTheme.typography.bodyMedium)
    val choices = LocationNoise.SIGMAS_M
    // 0.8.8: disabled while sharing is off; the row is faded as a whole (WindowSettings), so the
    // buttons keep their normal colours instead of fading twice.
    val base = SegmentedButtonDefaults.colors()
    val colors = SegmentedButtonDefaults.colors(
        disabledActiveContainerColor = base.activeContainerColor, disabledActiveContentColor = base.activeContentColor,
        disabledActiveBorderColor = base.activeBorderColor, disabledInactiveContainerColor = base.inactiveContainerColor,
        disabledInactiveContentColor = base.inactiveContentColor, disabledInactiveBorderColor = base.inactiveBorderColor,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { i, v ->
            SegmentedButton(
                selected = current == v, onClick = { onChange(v) }, enabled = enabled, colors = colors,
                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
            ) { Text(sigmaChoice(v), style = MaterialTheme.typography.labelSmall) }
        }
    }
    Text(SIGMA_EXPLANATION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * «12.10. 16:05 · Töff»; 0.8.5: with the report's peak level in whole dB «… · Töff · 81 dB»; with a
 * licence plate (0.7.6–0.8.4, on the phone only) «… · Töff · ZH 123».
 */
internal fun sentReportLine(time: String, s: SentReport): String =
    "$time · ${ReportCategory.fromId(s.category)?.label ?: s.category}" +
        (s.lafMaxDb?.takeIf { it.isFinite() }?.let { " · ${Math.round(it)} dB" } ?: "") +
        (s.plate?.let { " · $it" } ?: "")

/**
 * 0.8.5: a sent report's row shows its photo (if any) as a 40 dp thumbnail before the line; a tap
 * opens it full-screen with «Löschen» for the photo alone (ReportPhotos.kt). Photos stay on the phone.
 */
@Composable
private fun ReportsCard(r: ReportSnapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<SentReport?>(null) }
    // 0.8.8: «Meldungen unterwegs», always shown (it explains the second kind of data); the sent reports
    // as list rows as in 0.8.6 (category icon, the line, the photo at the end).
    SectionCard(rows = true) {
        CardHeader(AppIcons.Campaign, REPORTS_SECTION_TITLE, REPORTS_SECTION_SUBTITLE)
        CardBlock {
            if (r.pending.isNotEmpty()) StatRow(PENDING_LABEL, r.pending.size.toString())
            if (r.sent.isEmpty() && r.pending.isEmpty()) HelperText(REPORTS_NONE) else StatRow("Gesendet", r.sent.size.toString())
        }
        r.sent.takeLast(5).reversed().forEach { s ->
            val photo = ReportPhotos.file(context, s.photo)
            val cat = ReportCategory.fromId(s.category)
            ListItem(
                headlineContent = { Text(sentReportLine(Fmt.dateTime(s.epochMs), s), style = MaterialTheme.typography.bodyMedium) },
                leadingContent = cat?.let { c -> { Icon(reportCategoryIcon(c), contentDescription = null) } },
                trailingContent = photo?.let { f -> { ReportPhotoThumbnail(f, SENT_THUMB_DP.dp, onClick = { viewing = s }) } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        // One switch for both (Uploader.enable / UploadSnapshot.enabled).
        CardBlock { Text(REPORTS_SAME_SWITCH, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    val v = viewing
    val file = v?.let { ReportPhotos.file(context, it.photo) }
    if (v != null && file != null) {
        ReportPhotoViewer(
            file,
            onDelete = {
                viewing = null
                val name = v.photo ?: return@ReportPhotoViewer
                scope.launch(Dispatchers.IO) { ReportPhotos.deleteSentPhoto(context, context.container.upload.reports, v.epochMs, name) }
            },
            onDismiss = { viewing = null },
        )
    }
}

/** 0.8.5: the photo thumbnail in a «Meldungen» row, dp. */
const val SENT_THUMB_DP = 40
