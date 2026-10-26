package ch.lessnoise.app.edition

import java.util.Locale
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.audio.AudioTap
import ch.lessnoise.app.audio.NoAudioTap
import ch.lessnoise.app.container
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.labor.ClipLibrary
import ch.lessnoise.app.labor.ClipPlayerSheet
import ch.lessnoise.app.labor.ClipRateSampler
import ch.lessnoise.app.labor.ClipsListDialog
import ch.lessnoise.app.labor.ExportEstimate
import ch.lessnoise.app.labor.ExportJobPanel
import ch.lessnoise.app.labor.ExportJobState
import ch.lessnoise.app.labor.ExportRequest
import ch.lessnoise.app.labor.ExportScope
import ch.lessnoise.app.labor.ExportScopeControls
import ch.lessnoise.app.labor.ExportScopes
import ch.lessnoise.app.labor.ExportSource
import ch.lessnoise.app.labor.ExportTarget
import ch.lessnoise.app.labor.IncrementalExport
import ch.lessnoise.app.labor.ManifestWindow
import ch.lessnoise.app.labor.LaborExportStore
import ch.lessnoise.app.labor.LaborExportJobs
import ch.lessnoise.app.labor.LaborExportService
import ch.lessnoise.app.labor.LaborZipFiles
import ch.lessnoise.app.labor.LaborZipShare
import ch.lessnoise.app.labor.Labor
import ch.lessnoise.app.labor.ManifestFile
import ch.lessnoise.app.labor.ManifestLines
import ch.lessnoise.app.labor.LaborDirs
import ch.lessnoise.app.labor.LaborPlayer
import ch.lessnoise.app.labor.LaborRecorder
import ch.lessnoise.app.labor.RecorderStatus
import ch.lessnoise.app.labor.StorageBudget
import ch.lessnoise.app.labor.StoragePolicy
import ch.lessnoise.app.labor.toastClipNotFound
import ch.lessnoise.app.ui.SectionCard
import ch.lessnoise.app.ui.StatRow
import ch.lessnoise.app.ui.SwissLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

// The LABOR edition (app/src/labor/): can record audio for debugging the event detector and the
// classifier. Never published on the website. See PRIVACY.md → «Labor-Build».
// Compiled into two flavours: `labor` (no internet) and `laborKarte` (internal: the same plus the
// opt-in «Messwerte teilen», BuildConfig.UPLOAD_AVAILABLE = true). The texts below branch on that.

private val LaborRed = Color(0xFFB3261E)

/** Labor: the recorder if «Audio während der Messung aufzeichnen» is on, else nothing. */
/**
 * Local diagnostics of the Labor builds (0.6.7). Labor·Karte: each change of the motion detector
 * («Unterwegs erkannt», src/karte/.../motion/) as one `motion` line in manifest.jsonl, so the owner can
 * see later why it did or did not switch. The Labor build without the map has no detector and never
 * calls it. Written on the caller's thread (the motion thread), a few lines per measurement.
 */
object EditionDiagnostics {
    fun motionTransition(context: Context, atMs: Long, to: String, spanStartMs: Long, features: Map<String, Double>) {
        try {
            val line = ManifestLines.motion(
                Labor.currentSession ?: "none", atMs, java.time.ZoneId.systemDefault(), to, spanStartMs, features,
            )
            ManifestFile.append(Labor.dirs(context.applicationContext).manifest, line)
            Labor.filesVersion.update { it + 1 }
        } catch (e: Exception) {
            Log.w("Labor", "motion line: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 0.6.9, Labor·Karte: one `motion_minute` line per minute of a «Messen» run — median and max of the
     * per-second motion value, the distance from the run's first position rounded to 50 m (null without
     * a fix; never a coordinate) and the state. Stays in manifest.jsonl on the phone.
     */
    fun motionMinute(
        context: Context, minuteStartMs: Long, medianMs2: Double?, maxMs2: Double?, seconds: Int,
        distanceM: Int?, state: String, motion: Boolean, position: Boolean,
    ) {
        try {
            val line = ManifestLines.motionMinute(
                Labor.currentSession ?: "none", minuteStartMs, java.time.ZoneId.systemDefault(),
                medianMs2, maxMs2, seconds, distanceM, state, motion, position,
            )
            ManifestFile.append(Labor.dirs(context.applicationContext).manifest, line)
        } catch (e: Exception) {
            Log.w("Labor", "motion_minute line: ${e.javaClass.simpleName}")
        }
    }

    /**
     * 0.7.6, Labor·Karte (the only Labor build with «Laut! Melden»): at «Senden» one `report` line in
     * manifest.jsonl — time, category, what was taken with it (0.9.4.3 `media` «video»/«photo»/null; 0.8.5–0.9.4.2
     * whether a photo was taken; until 0.8.4 the plate),
     * direction; no coordinates. Plain values only: the
     * Labor code does not see the upload package (UploadSourceIsolationTest).
     */
    fun report(
        context: Context, reportMs: Long, category: String, media: String?,
        bearingDeg: Int?, north: String?, accuracy: String?,
    ) {
        try {
            val line = ManifestLines.report(
                Labor.currentSession ?: "none", reportMs, java.time.ZoneId.systemDefault(), category, media, bearingDeg, north, accuracy,
            )
            ManifestFile.append(Labor.dirs(context.applicationContext).manifest, line)
            Labor.filesVersion.update { it + 1 }
        } catch (e: Exception) {
            Log.w("Labor", "report line: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * The event head (multi-label classifier on event features, dsp `EventHead`, weights bundled in
 * the dsp module). Labor only, logging only: its per-class probabilities and decision go into the
 * manifest's `event` and `clip` lines; the category shown and stored stays YAMNet's.
 */
object EditionEventHead {
    @Volatile private var cached: ch.lessnoise.dsp.classify.EventHead? = null

    fun create(): ch.lessnoise.dsp.classify.EventHeadClassifier? = cached ?: try {
        ch.lessnoise.dsp.classify.EventHead.loadDefault().also { cached = it }
    } catch (e: Exception) {
        Log.w("Labor", "event head not loaded: ${e.javaClass.simpleName}")
        null
    }
}

object AudioTapProvider {
    fun create(context: Context): AudioTap {
        Labor.init(context)
        val s = Labor.settings.value
        Labor.status.value = RecorderStatus()
        // Recording on: the recorder (with neither clips nor continuous it writes only the
        // classifier log and the manifest, no audio).
        if (!s.recordAudio) return NoAudioTap
        return try {
            LaborRecorder(context, s)
        } catch (e: Exception) {
            Log.w("Labor", "recorder not started: ${e.javaClass.simpleName}")
            NoAudioTap
        }
    }
}

/**
 * Labor: the event clips for the chart (v0.3.4). [clipRefs] maps the start time of each event that
 * has a clip to its clip reference (the manifest is indexed once and cached until it changes).
 */
object EditionClips {
    /** Changes when clips are written or deleted (the chart data is then re-evaluated). */
    val version: Flow<Long> = Labor.filesVersion

    /** Event start (epoch ms) → clip reference, for the events of a chart window. Blocking (file I/O). */
    fun clipRefs(context: Context, events: List<EventEntity>): Map<Long, String> {
        if (events.isEmpty()) return emptyMap()
        val index = ClipLibrary.index(context)
        if (index.size == 0) return emptyMap()
        val out = HashMap<Long, String>()
        for (e in events) index.clipRefFor(e.id, e.startEpochMs)?.let { out[e.startEpochMs] = it }
        return out
    }

    /** Opens the clip player on a clip; «‹» / «›» step through [playlist]. */
    val player: ((clipRef: String, playlist: List<String>) -> Unit)? = { ref, playlist -> LaborPlayer.open(ref, playlist) }
}

object EditionUi {
    /**
     * First line of Einstellungen › «Datenschutz»: what this edition does with audio. The public
     * text («Es wird nie Audio gespeichert oder gesendet.») would be false here. The internet line
     * that follows it comes from the shared card (BuildConfig.UPLOAD_AVAILABLE).
     */
    val ABOUT_AUDIO: String =
        "Weniger Lärm Labor: Audio kann – nur wenn du es einschaltest – als Clips und Stundendateien auf diesem Telefon " +
            "gespeichert werden, dazu ein Protokoll der Erkennung. " +
            if (BuildConfig.UPLOAD_AVAILABLE)
                "Nichts davon wird gesendet: «Teilen» sendet nur Kennwerte, nie Audio, Clips oder Protokolle."
            else
                "Nichts davon wird gesendet: diese Version hat keine Internet-Berechtigung."

    /** The microphone line on «Messen»: the public «nie Audio» promise would be false here. */
    const val MIC_LINE = "Die App braucht das Mikrofon. Labor: Audio wird nur gespeichert, wenn du es einschaltest."

    /** Text of the red banner: the Labor edition with the map says that it can share. */
    val BANNER_TEXT: String =
        if (BuildConfig.UPLOAD_AVAILABLE) "WENIGER LÄRM LABOR · KARTE – kann Audio aufzeichnen"
        else "WENIGER LÄRM LABOR – kann Audio aufzeichnen"

    /**
     * Hosted once at the app root: the clip player sheet. It is closed (and its MediaPlayer
     * released) whenever a measurement starts or stops.
     */
    @Composable
    fun Overlay() {
        val context = LocalContext.current
        val request by LaborPlayer.request.collectAsStateWithLifecycle()
        val live by context.container.live.collectAsStateWithLifecycle()
        val running = live.running
        var lastRunning by remember { mutableStateOf(running) }
        LaunchedEffect(running) {
            if (running != lastRunning) {
                lastRunning = running
                LaborPlayer.close()
            }
        }
        request?.let { r -> ClipPlayerSheet(r, onDismiss = { LaborPlayer.close() }) }
    }

    /**
     * 0.7.5: why «Messen» must not start, or null. Audio recording is on and its budget is used up:
     * the owner switches recording off, raises «Maximaler Speicher» or exports and deletes first.
     * Blocking (walks the audio folder); called on the tap.
     */
    fun startRefusal(context: Context): String? {
        Labor.init(context)
        val s = Labor.settings.value
        val audio = StoragePolicy.recordsAudio(s)
        if (!audio) return null
        val used = Labor.dirs(context.applicationContext).usedBytes()
        return if (StoragePolicy.level(used, s.maxBytes, audio) == StoragePolicy.Level.BLOCK) StoragePolicy.message(used, s.maxBytes, audio) else null
    }

    /**
     * 0.7.5: the red line «Speicher fast voll …» / «Speicher voll …» (StoragePolicy), on «Messen» and in
     * the Labor settings, while audio recording is on and the budget is nearly or fully used up.
     */
    @Composable
    fun StorageNotice() {
        val context = LocalContext.current
        remember { Labor.init(context) }
        val s by Labor.settings.collectAsStateWithLifecycle()
        val version by Labor.filesVersion.collectAsStateWithLifecycle()
        val live by context.container.live.collectAsStateWithLifecycle()
        val dirs = remember { Labor.dirs(context) }
        val audio = StoragePolicy.recordsAudio(s)
        val running = live.running
        val used by produceState<Long?>(null, version, running, audio) {
            if (!audio) { value = null; return@produceState }
            while (true) {
                value = withContext(Dispatchers.IO) { dirs.usedBytes() }
                if (!running) break
                delay(30_000)
            }
        }
        val u = used
        val text = if (u == null) null else StoragePolicy.message(u, s.maxBytes, audio)
        if (text != null) Text(text, color = LaborRed, style = MaterialTheme.typography.bodySmall)
    }

    /** The permanent red banner above every screen. */
    @Composable
    fun Banner() {
        val context = LocalContext.current
        remember { Labor.init(context) }
        val st by Labor.status.collectAsStateWithLifecycle()
        val live by context.container.live.collectAsStateWithLifecycle()
        Column(
            Modifier.fillMaxWidth().background(LaborRed)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                BANNER_TEXT, color = Color.White,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            )
            if (st.recording && live.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(6.dp))
                    Text("Aufnahme läuft", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (st.storageFull) {
                Text(
                    "Speicher voll: Aufnahme gestoppt; Messung und Protokoll laufen weiter.",
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    /** Einstellungen → «Labor». */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsSection() {
        val context = LocalContext.current
        remember { Labor.init(context) }
        val s by Labor.settings.collectAsStateWithLifecycle()
        val st by Labor.status.collectAsStateWithLifecycle()
        val live by context.container.live.collectAsStateWithLifecycle()
        val version by Labor.filesVersion.collectAsStateWithLifecycle()
        val dirs = remember { Labor.dirs(context) }
        val scope = rememberCoroutineScope()
        var confirmOn by remember { mutableStateOf(false) }
        var exportInfo by remember { mutableStateOf(false) }
        // 0.9.4.1: both exports run in LaborExportService (foreground, type dataSync); this is its live state.
        val job by LaborExportJobs.state.collectAsStateWithLifecycle()
        val exporting = job is ExportJobState.Running
        var lowSpace by remember { mutableStateOf<String?>(null) }
        var zipPlan by remember { mutableStateOf<ZipPlan?>(null) }
        // 0.9.4: the «Export» row and «Ohne Stundendateien» (Labor SharedPreferences), «Exportierte Aufnahmen löschen».
        var exportScope by remember { mutableStateOf(LaborExportStore.scope(context)) }
        var withoutHours by remember { mutableStateOf(LaborExportStore.withoutHourFiles(context)) }
        var deletePlan by remember { mutableStateOf<List<ExportSource>?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
        var showClips by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val recordingNow = st.recording && live.running

        // «In Ordner exportieren» (branch labor-export): copies what is not yet in the picked folder. The hour
        // file being written is left out (Labor.openContinuousFile); the manifest is always re-copied.
        // 0.9.4: within the chosen scope («Letzte 24 h»: with the filtered manifest), still only what is new or changed.
        // 0.9.4.1: runs in LaborExportService (LaborExportService.runFolder), not in this screen's scope.
        fun runExport(tree: Uri) {
            LaborExportService.start(context, ExportRequest.Folder(tree.toString(), exportScope, withoutHours))?.let { message = it }
        }
        fun shareZip(zip: File) {
            if (!zip.isFile) { message = "ZIP nicht mehr vorhanden."; return }
            try {
                LaborZipShare.open(context, zip)
            } catch (e: Exception) {
                message = "Teilen nicht möglich: ${e.javaClass.simpleName}"
                return
            }
            // Complete AND shared: only now its files count as exported (ZipJob.onShared).
            scope.launch(Dispatchers.IO) { try { LaborZipShare.recordShared(context, zip) } catch (e: Exception) { Log.w("Labor", "ledger: ${e.javaClass.simpleName}") } }
        }
        // The ZIP finished while this section is on screen: open the share sheet once, as before 0.9.4.1.
        LaunchedEffect(job) {
            val j = job
            if (j is ExportJobState.Done && j.zip != null && LaborExportJobs.consumeAutoShare(j, System.currentTimeMillis())) shareZip(j.zip)
        }
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (LaborExportJobs.busy) { message = LaborExportJobs.BUSY; return@rememberLauncherForActivityResult }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                LaborExportStore.changeTree(context, uri)
                runExport(uri)
            } catch (e: Exception) {
                message = "Ordner nicht verwendbar: ${e.javaClass.simpleName}"
            }
        }
        fun exportOrPick() {
            if (LaborExportJobs.busy) { message = LaborExportJobs.BUSY; return }
            val tree = LaborExportStore.treeUri(context)
            if (tree != null && LaborExportStore.hasPermission(context, tree)) runExport(tree)
            else {
                if (tree != null) LaborExportStore.setTreeUri(context, null)
                try {
                    pickFolder.launch(null)
                } catch (e: Exception) {
                    message = "Kein Ordner-Dialog verfügbar: ${e.javaClass.simpleName}"
                }
            }
        }

        val stats by produceState<LaborDirs.Stats?>(null, version, recordingNow) {
            while (true) {
                value = withContext(Dispatchers.IO) { dirs.stats() }
                if (!recordingNow) break
                delay(10_000)
            }
        }

        val estimate by produceState<ExportEstimateUi?>(null, exportScope, withoutHours, version) {
            value = withContext(Dispatchers.IO) {
                try { estimateFor(context, dirs, exportScope, withoutHours) } catch (e: Exception) { null }
            }
        }

        SectionCard("Labor") {
            LaborSwitch("Audio während der Messung aufzeichnen", s.recordAudio) { v ->
                if (v) confirmOn = true else Labor.update(context) { it.copy(recordAudio = false) }
            }
            LaborSwitch("Ereignis-Clips (±5 s, WAV 16 kHz)", s.clips, enabled = s.recordAudio) { v ->
                Labor.update(context) { it.copy(clips = v) }
            }
            LaborSwitch("Durchgehend (AAC 64 kbit/s, Stundendateien)", s.continuous, enabled = s.recordAudio) { v ->
                Labor.update(context) { it.copy(continuous = v) }
            }
            Text("Clip-Rate", style = MaterialTheme.typography.bodyMedium)
            val rates = ClipRateSampler.CHOICES
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                rates.forEachIndexed { i, n ->
                    SegmentedButton(
                        selected = s.clipEvery == n,
                        onClick = { Labor.update(context) { it.copy(clipEvery = n) } },
                        shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                        enabled = s.recordAudio && s.clips,
                    ) { Text(if (n == 1) "jedes" else "jedes $n.", maxLines = 1) }
                }
            }
            Text("Maximaler Speicher", style = MaterialTheme.typography.bodyMedium)
            val caps = StorageBudget.CHOICES_GB
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                caps.forEachIndexed { i, gb ->
                    SegmentedButton(
                        selected = s.maxGb == gb,
                        onClick = { Labor.update(context) { it.copy(maxGb = gb) } },
                        shape = SegmentedButtonDefaults.itemShape(i, caps.size),
                        enabled = s.recordAudio,
                    ) { Text(gbLabel(gb), maxLines = 1) }
                }
            }
            Text(
                "Pro 8 h Messung: durchgehend etwa 230 MB (ca. 30 MB pro Stunde), dazu die Clips mit je etwa 0.4 MB " +
                    "(höchstens 2 MB) und das Klassifikator-Protokoll (jede Sekunde die erkannten Geräusche, ca. 1 MB pro Stunde). " +
                    "Ist der Speicher voll, stoppt nur die Aufnahme; Messung und Protokoll laufen weiter. " +
                    "Änderungen gelten ab dem nächsten Start der Messung; Ausschalten stoppt eine laufende Aufnahme sofort.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val sts = stats
            StatRow("Belegt", if (sts == null) "…" else "${mb(sts.usedBytes)} von ${gbLabel(s.maxGb)}")
            StorageNotice()
            StatRow("Clips", sts?.clipCount?.toString() ?: "…")
            StatRow("Stundendateien", sts?.hourFiles?.toString() ?: "…")
            StatRow("Klassifikator-Protokolle", sts?.classifierFiles?.toString() ?: "…")
            // 0.6.6: which input delivers the audio (the app measures only with the built-in microphone).
            if (live.running) StatRow("Mikrofon", live.inputDevice ?: "unbekannt")
            // 0.6.7, Labor·Karte: the motion detector's state (its changes are in manifest.jsonl, type «motion»).
            if (live.running && BuildConfig.FLAVOR == "laborKarte" && !live.unterwegs) StatRow("Bewegung", if (live.moving) "unterwegs erkannt" else "ruhig")
            if (st.storageFull) {
                Text("Speicher voll: Die Aufnahme wurde gestoppt; Messung und Protokoll laufen weiter.", color = LaborRed, style = MaterialTheme.typography.bodySmall)
            }
            if (recordingNow && st.droppedThisSession > 0) {
                Text("Verworfen (Schreiben zu langsam oder Speicher voll): ${st.droppedThisSession}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { showClips = true }, enabled = (sts?.clipCount ?: 0) > 0) { Text("Clips anhören") }
            ExportScopeControls(
                scope = exportScope,
                onScope = { exportScope = it; LaborExportStore.setScope(context, it) },
                withoutHourFiles = withoutHours,
                onWithoutHourFiles = { withoutHours = it; LaborExportStore.setWithoutHourFiles(context, it) },
                estimate = estimate?.line,
                folderNote = estimate?.folderNote,
                enabled = !exporting,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 0.9.4.1: both stay tappable; while an export runs a second one is refused with a short message.
                OutlinedButton(onClick = { exportOrPick() }) { Text("In Ordner exportieren") }
                OutlinedButton(
                    onClick = {
                        if (LaborExportJobs.busy) { message = LaborExportJobs.BUSY; return@OutlinedButton }
                        val sc = exportScope
                        val noHours = withoutHours
                        scope.launch {
                            val p = withContext(Dispatchers.IO) {
                                try {
                                    val plan = planZip(context, dirs, sc, noHours)
                                    plan to LaborZipFiles.spaceRefusal(context, plan.files)
                                } catch (e: Exception) { null }
                            }
                            when {
                                p == null -> message = "ZIP nicht möglich (Dateien nicht lesbar)."
                                p.second != null -> lowSpace = p.second
                                else -> zipPlan = p.first
                            }
                        }
                    },
                ) { Text("Als ZIP teilen") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (LaborExportJobs.busy) { message = LaborExportJobs.BUSY; return@TextButton }
                    try { pickFolder.launch(null) } catch (e: Exception) { message = "Kein Ordner-Dialog verfügbar: ${e.javaClass.simpleName}" }
                }) { Text("Ordner wählen") }
                TextButton(onClick = { exportInfo = true }) { Text("Wo sind die Dateien?") }
            }
            ExportJobPanel(
                state = job,
                onCancel = { LaborExportService.cancel() },
                onShareZip = { (job as? ExportJobState.Done)?.zip?.let(::shareZip) },
                onFolderExport = { exportOrPick() },
            )
            OutlinedButton(enabled = !recordingNow && !exporting, onClick = {
                scope.launch {
                    val p = withContext(Dispatchers.IO) {
                        ExportScopes.exportedForDeletion(IncrementalExport.sources(dirs, Labor.openContinuousFile), LaborExportStore.loadLedger(context))
                    }
                    if (p.isEmpty()) message = "Nichts zu löschen: Keine Aufnahme ist schon exportiert (oder sie hat sich seither verändert)."
                    else deletePlan = p
                }
            }) { Text("Exportierte Aufnahmen löschen") }
            OutlinedButton(enabled = !recordingNow && !exporting, onClick = { confirmDelete = true }) { Text("Alle Aufnahmen löschen") }
            if (recordingNow) Text("Löschen ist während einer Aufnahme nicht möglich.", style = MaterialTheme.typography.bodySmall)
            else if (exporting) Text("Löschen ist während eines Exports nicht möglich.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        if (showClips) ClipsListDialog(onDismiss = { showClips = false })

        if (confirmOn) {
            AlertDialog(
                onDismissRequest = { confirmOn = false },
                text = {
                    Text(
                        "Weniger Lärm Labor speichert dann Roh-Audio auf diesem Telefon, auch Stimmen in der Nähe. " +
                            "Die Dateien bleiben auf dem Telefon, bis du sie löschst."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmOn = false; Labor.update(context) { it.copy(recordAudio = true) } }) { Text("Aufzeichnen") }
                },
                dismissButton = { TextButton(onClick = { confirmOn = false }) { Text("Abbrechen") } },
            )
        }

        if (exportInfo) {
            val rel = "Android/data/${context.packageName}/files/audio"
            val tree = LaborExportStore.treeUri(context)
            AlertDialog(
                onDismissRequest = { exportInfo = false },
                title = { Text("Aufnahme-Ordner") },
                text = {
                    Text(
                        "Die Aufnahmen liegen im internen Speicher unter $rel. Dateimanager auf dem Telefon dürfen Android/data " +
                            "ab Android 11 nicht mehr öffnen; am Computer (USB, Dateiübertragung) ist der Ordner sichtbar.\n\n" +
                            "«In Ordner exportieren» kopiert alles, was noch fehlt, in einen Ordner deiner Wahl – Downloads, ein USB-Stick " +
                            "oder die Google-Drive-App (sie erscheint im Ordner-Dialog als Ziel). Die Unterordner clips, classifier und " +
                            "continuous bleiben erhalten; das Manifest wird jedes Mal neu kopiert, die laufende Stundendatei erst nach dem " +
                            "Stopp der Messung. Ein neuer Ordner lässt sich mit «Ordner wählen» setzen.\n\n" +
                            "«Export» gilt für beide Knöpfe: «Alles», «Seit letztem Export» (was noch in keinem Ordner und keinem ZIP war) " +
                            "oder «Letzte 24 h» (Clips, Protokolle und Stundendateien dieser 24 h und ein Manifest nur mit ihren Zeilen; " +
                            "das Manifest auf dem Telefon bleibt vollständig, im Export-Ordner ersetzt es bis zum nächsten «Alles» ein " +
                            "früher kopiertes ganzes). «Ohne Stundendateien» lässt die grossen continuous-Dateien weg. " +
                            "«Exportierte Aufnahmen löschen» löscht nur, was schon in einem Ordner oder ZIP ist." +
                            if (tree != null) "\n\nAktueller Export-Ordner: ${LaborExportStore.treeName(context, tree)}" else "\n\nNoch kein Export-Ordner gewählt."
                    )
                },
                confirmButton = { TextButton(onClick = { exportInfo = false }) { Text("Schliessen") } },
            )
        }

        zipPlan?.let { plan ->
            val total = plan.files.sumOf { it.size }
            val count = { folder: String -> plan.files.count { it.relativePath.startsWith("$folder/") } }
            val zipName = ExportScopes.zipName(plan.scope, plan.nowMs, ZoneId.systemDefault())
            AlertDialog(
                onDismissRequest = { zipPlan = null },
                title = { Text("Als ZIP teilen") },
                text = {
                    Text(
                        buildString {
                            append(ExportScopes.estimateLine(plan.scope, plan.withoutHourFiles, ExportEstimate.of(plan.files), plan.neverExported)).append(".")
                            append("\n\nDarin: ${count("clips")} Clips, das Manifest")
                            if (plan.scope == ExportScope.LAST_24H) append(" (nur die Zeilen der letzten 24 h; auf dem Telefon bleibt es vollständig)")
                            append(", ${count("classifier")} Klassifikator-Protokolle")
                            if (count("continuous") > 0) append(" und ${count("continuous")} Stundendateien")
                            append(".")
                            if (plan.files.size <= 1) append("\n\nAusser dem Manifest ist nichts in dieser Auswahl.")
                            if (plan.leftOutHours.isNotEmpty()) {
                                append("\n\nOhne die ${plan.leftOutHours.size} Stundendateien (${mb(plan.leftOutHours.sumOf { it.size })}, zu gross für ein ZIP); «In Ordner exportieren» kopiert sie.")
                            }
                            if (total > 100_000_000L) append("\n\nDas ZIP ist gross; nicht jede App kann es versenden.")
                            if (recordingNow && !plan.withoutHourFiles) append("\n\nDie laufende Stundendatei ist erst nach dem Stopp der Messung lesbar.")
                            append("\n\n$zipName. Das ZIP entsteht im Hintergrund, mit Fortschritt hier und in einer Mitteilung (auch wenn du die App verlässt); ")
                            append("danach öffnet sich das Teilen-Menü (oder über «Export fertig»). Erst dann gelten diese Dateien als exportiert ")
                            append("(«Seit letztem Export», «Exportierte Aufnahmen löschen»); ein abgebrochenes ZIP zählt nicht.")
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        zipPlan = null
                        LaborExportService.start(context, ExportRequest.Zip(plan.files, zipName, plan.scope.label))?.let { message = it }
                    }) { Text("ZIP erstellen") }
                },
                dismissButton = { TextButton(onClick = { zipPlan = null }) { Text("Abbrechen") } },
            )
        }

        lowSpace?.let { text ->
            AlertDialog(
                onDismissRequest = { lowSpace = null },
                title = { Text("Als ZIP teilen") },
                text = { Text("$text.\n\n«In Ordner exportieren» kopiert die Dateien direkt in einen Ordner deiner Wahl und braucht keine Kopie auf dem Telefon.") },
                confirmButton = { TextButton(onClick = { lowSpace = null; exportOrPick() }) { Text("In Ordner exportieren") } },
                dismissButton = { TextButton(onClick = { lowSpace = null }) { Text("Abbrechen") } },
            )
        }

        deletePlan?.let { files ->
            val bytes = files.sumOf { it.size }
            val count = { folder: String -> files.count { it.relativePath.startsWith("$folder/") } }
            AlertDialog(
                onDismissRequest = { deletePlan = null },
                title = { Text("Exportierte Aufnahmen löschen?") },
                text = {
                    Text(
                        "${ExportScopes.files(files.size)}, ${ExportScopes.size(bytes)} (${count("clips")} Clips, " +
                            "${count("classifier")} Klassifikator-Protokolle, ${count("continuous")} Stundendateien): nur, was schon in " +
                            "einen Ordner exportiert oder in ein ZIP gepackt wurde und sich seither nicht verändert hat. " +
                            "Das Manifest und alles noch nicht Exportierte bleiben. Ob ein geteiltes ZIP angekommen ist, weiss die App nicht."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deletePlan = null
                        LaborPlayer.close()
                        scope.launch {
                            val done = withContext(Dispatchers.IO) {
                                val ledger = LaborExportStore.loadLedger(context)
                                val d = ExportScopes.deleteExported(dirs.root, files, ledger)
                                LaborExportStore.saveLedger(context, ledger)
                                Labor.refreshClipIds(dirs)
                                if (dirs.usedBytes() < s.maxBytes) Labor.status.value = Labor.status.value.copy(storageFull = false)
                                d
                            }
                            Labor.filesVersion.value = Labor.filesVersion.value + 1
                            message = "Gelöscht: ${ExportScopes.files(done.files)}, ${ExportScopes.size(done.bytes)} (schon exportiert). " +
                                "Nicht Exportiertes bleibt." + if (done.files < files.size) " ${files.size - done.files} übersprungen (verändert)." else ""
                        }
                    }) { Text("Löschen") }
                },
                dismissButton = { TextButton(onClick = { deletePlan = null }) { Text("Abbrechen") } },
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Alle Aufnahmen löschen?") },
                text = { Text("Alle Clips, Stundendateien, Klassifikator-Protokolle und das Manifest werden gelöscht. Die Messwerte bleiben.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        LaborPlayer.close()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                dirs.deleteAll()
                                LaborExportStore.clearLedger(context)
                                LaborZipFiles.clear(context)
                                Labor.refreshClipIds(dirs)
                            }
                            Labor.status.value = Labor.status.value.copy(storageFull = false)
                            Labor.filesVersion.value = Labor.filesVersion.value + 1
                            message = "Alle Aufnahmen gelöscht."
                        }
                    }) { Text("Löschen") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
            )
        }

        LaunchedEffect(message) {
            if (message != null) { delay(8_000); message = null }
        }
    }

    /** A small red record dot after events that have a clip; tapping it plays the clip. */
    @Composable
    fun EventMarker(eventId: Long) {
        val ids by Labor.clipEventIds.collectAsStateWithLifecycle()
        if (eventId in ids) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            Box(
                Modifier.padding(start = 2.dp).size(28.dp).clip(CircleShape)
                    .clickable(onClickLabel = "Clip abspielen") {
                        scope.launch {
                            val index = withContext(Dispatchers.IO) { ClipLibrary.index(context) }
                            val e = index.byEventId(eventId)
                            if (e == null) toastClipNotFound(context)
                            else LaborPlayer.open(e.ref, index.entries.map { it.ref })
                        }
                    }
                    .semantics { contentDescription = "Clip vorhanden, abspielen" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(LaborRed))
            }
        }
    }
}

@Composable
private fun LaborSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun gbLabel(gb: Double): String =
    if (gb < 1) String.format(Locale.GERMANY, "%.1f GB", gb) else String.format(SwissLocale, "%.0f GB", gb)

private fun mb(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.GERMANY, "%.2f GB", bytes / 1e9)
    else -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1e6)
}

/** 0.9.4: what «Als ZIP teilen» packs, decided when the button is pressed. */
private data class ZipPlan(
    val scope: ExportScope,
    val withoutHourFiles: Boolean,
    /** In ZIP order; for «Letzte 24 h» the manifest is the filtered copy. */
    val files: List<ExportSource>,
    /** Hour files of the scope that are too big together for a ZIP (≥ LaborDirs.ZIP_CONTINUOUS_LIMIT). */
    val leftOutHours: List<ExportSource>,
    val neverExported: Boolean,
    val nowMs: Long,
)

/** 0.9.4: the size line under «Export» and, if it differs, what is new for the export folder. */
private data class ExportEstimateUi(val line: String, val folderNote: String?)

private fun planZip(context: Context, dirs: LaborDirs, scope: ExportScope, withoutHours: Boolean): ZipPlan {
    val ledger = LaborExportStore.loadLedger(context)
    val sources = IncrementalExport.sources(dirs, Labor.openContinuousFile)
    val now = System.currentTimeMillis()
    val selected = ExportScopes.select(sources, ExportTarget.ZIP, scope, withoutHours, ledger, now, ZoneId.systemDefault())
    val (files, leftOut) = if (withoutHours) selected to emptyList() else ExportScopes.fitZip(selected)
    val withManifest = ExportScopes.withScopedManifest(scope, files, dirs.manifest, File(context.cacheDir, "labor_zip_manifest"), now)
    return ZipPlan(scope, withoutHours, withManifest, leftOut, ExportScopes.neverExported(ledger), now)
}

private fun estimateFor(context: Context, dirs: LaborDirs, scope: ExportScope, withoutHours: Boolean): ExportEstimateUi {
    val ledger = LaborExportStore.loadLedger(context)
    val sources = IncrementalExport.sources(dirs, Labor.openContinuousFile)
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    // «Letzte 24 h»: the filtered manifest's size (one pass over the manifest), not the whole file's.
    val manifestBytes = if (scope == ExportScope.LAST_24H) ManifestWindow.bytes(dirs.manifest, now - ExportScopes.WINDOW_MS, now) else null
    fun est(list: List<ExportSource>): ExportEstimate {
        val e = ExportEstimate.of(list)
        val m = list.firstOrNull { it.relativePath == IncrementalExport.MANIFEST }
        return if (m != null && manifestBytes != null) e.copy(bytes = e.bytes - m.size + manifestBytes) else e
    }
    val zip = ExportScopes.select(sources, ExportTarget.ZIP, scope, withoutHours, ledger, now, zone)
    val folder = ExportScopes.select(sources, ExportTarget.FOLDER, scope, withoutHours, ledger, now, zone)
    val line = ExportScopes.estimateLine(scope, withoutHours, est(zip), ExportScopes.neverExported(ledger))
    val note = if (folder.size != zip.size) {
        val f = est(folder)
        "Davon neu für den Export-Ordner: ${ExportScopes.files(f.files)}, ${ExportScopes.size(f.bytes)}."
    } else null
    return ExportEstimateUi(line, note)
}
