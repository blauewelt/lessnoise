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
import ch.lessnoise.app.labor.ExportProgress
import ch.lessnoise.app.labor.IncrementalExport
import ch.lessnoise.app.labor.LaborExportStore
import ch.lessnoise.app.labor.SafExportSink
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
import ch.lessnoise.app.ui.shareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
        var exportBusy by remember { mutableStateOf(false) }
        var exportProgress by remember { mutableStateOf<ExportProgress?>(null) }
        var exportInfo by remember { mutableStateOf(false) }
        var exportDone by remember { mutableStateOf<String?>(null) }
        var zipPlan by remember { mutableStateOf<LaborDirs.Stats?>(null) }
        var zipBusy by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var showClips by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val recordingNow = st.recording && live.running

        // «In Ordner exportieren» (branch labor-export): copies what is not yet in the picked folder. The hour
        // file being written is left out (Labor.openContinuousFile); the manifest is always re-copied.
        fun runExport(tree: Uri) {
            exportBusy = true
            exportProgress = null
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val ledger = LaborExportStore.loadLedger(context)
                        val sources = IncrementalExport.sources(dirs, Labor.openContinuousFile)
                        val plan = IncrementalExport.plan(sources, ledger)
                        val sink = SafExportSink(context.contentResolver, tree, LaborExportStore.treeName(context, tree))
                        val r = IncrementalExport.run(
                            plan, ledger, sink,
                            onProgress = { exportProgress = it },
                            onFileDone = { LaborExportStore.saveLedger(context, ledger) },
                        )
                        LaborExportStore.saveLedger(context, ledger)
                        Result.success(r to (sources.size - plan.size))
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                exportBusy = false
                exportProgress = null
                outcome.fold(
                    onSuccess = { (r, skipped) ->
                        exportDone = buildString {
                            append("Exportiert: ${r.filesCopied} Dateien, ${mb(r.bytesCopied)} nach ${r.destination}")
                            if (skipped > 0) append(" ($skipped schon vorhanden)")
                            if (r.failures.isNotEmpty()) append("; ${r.failures.size} fehlgeschlagen: ${r.failures.first().first} (${r.failures.first().second})")
                            append(".")
                        }
                    },
                    onFailure = { e ->
                        message = if (e is SecurityException) {
                            LaborExportStore.setTreeUri(context, null)
                            "Zugriff auf den Export-Ordner verloren; bitte den Ordner neu wählen."
                        } else "Export nicht möglich: ${e.javaClass.simpleName}"
                    },
                )
            }
        }
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !exportBusy, onClick = { exportOrPick() }) { Text(if (exportBusy) "Export …" else "In Ordner exportieren") }
                OutlinedButton(
                    enabled = !zipBusy,
                    onClick = { scope.launch { zipPlan = withContext(Dispatchers.IO) { dirs.stats() } } },
                ) { Text(if (zipBusy) "ZIP …" else "Als ZIP teilen") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !exportBusy, onClick = {
                    try { pickFolder.launch(null) } catch (e: Exception) { message = "Kein Ordner-Dialog verfügbar: ${e.javaClass.simpleName}" }
                }) { Text("Ordner wählen") }
                TextButton(onClick = { exportInfo = true }) { Text("Wo sind die Dateien?") }
            }
            exportDone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            exportProgress?.let { p ->
                Text(
                    "Export: ${minOf(p.filesDone + 1, p.filesTotal)} von ${p.filesTotal} Dateien, ${mb(p.bytesDone)} von ${mb(p.bytesTotal)}" +
                        if (p.current.isNotEmpty()) " – ${p.current.substringAfterLast('/')}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(enabled = !recordingNow, onClick = { confirmDelete = true }) { Text("Alle Aufnahmen löschen") }
            if (recordingNow) Text("Löschen ist während einer Aufnahme nicht möglich.", style = MaterialTheme.typography.bodySmall)
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
                            "Stopp der Messung. Ein neuer Ordner lässt sich mit «Ordner wählen» setzen." +
                            if (tree != null) "\n\nAktueller Export-Ordner: ${LaborExportStore.treeName(context, tree)}" else "\n\nNoch kein Export-Ordner gewählt."
                    )
                },
                confirmButton = { TextButton(onClick = { exportInfo = false }) { Text("Schliessen") } },
            )
        }

        zipPlan?.let { plan ->
            val withContinuous = plan.continuousBytes in 1 until LaborDirs.ZIP_CONTINUOUS_LIMIT
            val total = plan.textAndClipBytes + if (withContinuous) plan.continuousBytes else 0
            AlertDialog(
                onDismissRequest = { zipPlan = null },
                title = { Text("Als ZIP teilen") },
                text = {
                    Text(
                        buildString {
                            append("${plan.clipCount} Clips, das Manifest und ${plan.classifierFiles} Klassifikator-Protokolle")
                            if (withContinuous) append(" sowie ${plan.hourFiles} Stundendateien")
                            append(", etwa ${mb(total)}.")
                            if (plan.continuousBytes >= LaborDirs.ZIP_CONTINUOUS_LIMIT) {
                                append("\n\nOhne die ${plan.hourFiles} Stundendateien (${mb(plan.continuousBytes)}, zu gross für ein ZIP); «In Ordner exportieren» kopiert sie.")
                            }
                            if (total > 100_000_000L) append("\n\nDas ZIP ist gross; nicht jede App kann es versenden.")
                            if (recordingNow) append("\n\nDie laufende Stundendatei ist erst nach dem Stopp der Messung lesbar.")
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        zipPlan = null
                        zipBusy = true
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) {
                                try {
                                    val dir = File(context.externalCacheDir ?: context.cacheDir, "labor_share")
                                    dir.listFiles()?.forEach { it.delete() }
                                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
                                    File(dir, "lessnoise-labor-$stamp.zip").also { dirs.zipTo(it, withContinuous) }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            zipBusy = false
                            if (zip == null) message = "ZIP konnte nicht erstellt werden (Speicher?)."
                            else try {
                                shareFiles(context, listOf(zip), "application/zip", "Weniger Lärm Labor – Aufnahmen")
                            } catch (e: Exception) {
                                message = "Teilen nicht möglich: ${e.javaClass.simpleName}"
                            }
                        }
                    }) { Text("ZIP erstellen") }
                },
                dismissButton = { TextButton(onClick = { zipPlan = null }) { Text("Abbrechen") } },
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
                                File(context.externalCacheDir ?: context.cacheDir, "labor_share").listFiles()?.forEach { it.delete() }
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
