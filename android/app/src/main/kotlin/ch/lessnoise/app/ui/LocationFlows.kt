package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lessnoise.app.container
import ch.lessnoise.app.location.LocationFetcher
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.service.MeasurementService
import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.app.upload.NoiseSecret
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportQueue
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

// Karten editions only (BuildConfig.UPLOAD_AVAILABLE): the question «Auf die Lärmkarte?», the
// location fix for a shared measurement, and «Laut! Melden». The Offline and Labor editions never
// show any of it (no location permission in their manifests).

/**
 * Sharing a measurement (app 0.6.3, server/DESIGN.md §2 rules 2–3), one object per screen tree
 * ([rememberShareFlow], provided as [LocalShareFlow] by AppRoot):
 *
 * - [start]: «Starten» (after the microphone permission). The first time, ONE question («Auf die
 *   Lärmkarte?»). With sharing on, the measurement starts at once without a point, and one precise
 *   location fix runs in parallel while the app is open; its point, moved at once by the location
 *   noise, is then attached to the running measurement, so ALL its minutes and events are shared.
 *   A failed fix shows no dialog: the status line says so and opens [showLocationOptions] on tap.
 * - [setSharing] / [askToShare]: the switch in «Teilen» and a tap on «Nicht auf der Karte»;
 *   switching on during a measurement shares that whole measurement (fix + attach as above).
 *
 * The location is read only while the app is in the foreground: leaving the app cancels a running
 * fix, and coming back retries it while the running measurement still has no point.
 */
@Stable
class ShareFlow {
    /** The fix for the running measurement (status line). */
    var locate by mutableStateOf(LocateState.IDLE)
        internal set
    internal var onStart: () -> Unit = {}
    internal var onSetSharing: (Boolean) -> Unit = {}
    internal var onAsk: () -> Unit = {}
    internal var onOptions: () -> Unit = {}
    /** Set by AppRoot: switch to the tab «Teilen». */
    var openShareTab: () -> Unit = {}

    /** «Starten», after the microphone permission was granted. */
    fun start() = onStart()

    /** The switch «Auf die Karte senden». */
    fun setSharing(on: Boolean) = onSetSharing(on)

    /** «Nicht auf der Karte – tippen zum Teilen»: the question, then on (without starting a measurement). */
    fun askToShare() = onAsk()

    /** «Kein Standort – tippen»: Nochmals, Standort einschalten, App-Einstellungen, Koordinaten einfügen. */
    fun showLocationOptions() = onOptions()

    /** What a tap on the status line does. */
    fun onStatusTap(tap: ShareTap) = when (tap) {
        ShareTap.ENABLE -> askToShare()
        ShareTap.LOCATION_OPTIONS -> showLocationOptions()
        ShareTap.OPEN_SHARE -> openShareTab()
        ShareTap.NONE -> Unit
    }
}

/** The sharing flow of the Karten editions; null in the Offline and Labor editions. */
val LocalShareFlow = staticCompositionLocalOf<ShareFlow?> { null }

private sealed interface ShareDialog {
    /** «Auf die Lärmkarte?»; [startAfter]: from «Starten» (the measurement starts after the answer). */
    data class Consent(val startAfter: Boolean) : ShareDialog
    data object Options : ShareDialog
    data object Paste : ShareDialog
}

@Composable
fun rememberShareFlow(): ShareFlow {
    val context = LocalContext.current
    val c = context.container
    val module = c.upload
    val store = module.store
    val scope = rememberCoroutineScope()
    val flow = remember { ShareFlow() }
    var dialog by remember { mutableStateOf<ShareDialog?>(null) }
    var lastOutcome by remember { mutableStateOf<LocationPolicy.Outcome?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    // The measurement the point is for: its start, or null = the one being started (after requestedAtMs).
    var target by remember { mutableStateOf<Long?>(null) }
    var requestedAtMs by remember { mutableLongStateOf(0L) }
    var foreground by remember { mutableStateOf(true) }

    /** The phone's noise secret (made on first use) and the current σ. */
    fun noise() = LocationPolicy.Noise(NoiseSecret.getOrCreate(store), store.state.value.sigmaM)

    /** Attaches [point] to the target measurement while it runs; false if it ended (no retroactive sharing). */
    suspend fun attach(point: LocationNoise.Point): Boolean {
        val since = requestedAtMs
        val start = target ?: withTimeoutOrNull(20_000) {
            c.live.first { it.running && it.measurementStartMs >= since - 2_000 }
        }?.measurementStartMs ?: return false
        if (!c.measurements.attachPoint(start, point)) return false
        c.live.update { if (it.running && it.measurementStartMs == start && it.sharedPoint == null) it.copy(sharedPoint = point) else it }
        return true
    }

    fun locateNow() {
        job?.cancel()
        // Never in the background: retried when the app comes back (ON_START below).
        if (!foreground) { flow.locate = LocateState.IDLE; return }
        flow.locate = LocateState.BUSY
        job = scope.launch {
            when (val o = LocationFetcher.locate(context, noise())) {
                is LocationPolicy.Outcome.Shifted -> {
                    attach(o.point)
                    lastOutcome = null
                    flow.locate = LocateState.IDLE
                }
                else -> { lastOutcome = o; flow.locate = LocateState.FAILED }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when (LocationFetcher.permission(context)) {
            LocationPolicy.Permission.PRECISE -> locateNow()
            LocationPolicy.Permission.APPROXIMATE_ONLY -> { lastOutcome = LocationPolicy.Outcome.ApproximateOnly; flow.locate = LocateState.FAILED }
            LocationPolicy.Permission.DENIED -> { lastOutcome = LocationPolicy.Outcome.PermissionDenied; flow.locate = LocateState.FAILED }
        }
    }

    /** The fix for the measurement started at [targetStart] (null: the one being started since [since]). */
    fun beginLocate(targetStart: Long?, since: Long = System.currentTimeMillis()) {
        target = targetStart
        requestedAtMs = since
        if (LocationFetcher.hasPreciseLocation(context)) {
            locateNow()
        } else {
            flow.locate = LocateState.BUSY
            permission.launch(LocationFetcher.PERMISSIONS)
        }
    }

    /** «Ja, auf die Karte» (or the switch): on; then start and/or locate. */
    fun yes(startAfter: Boolean) {
        val l = c.live.value
        module.uploader.enable(runningSinceMs = if (l.running) l.measurementStartMs else null)
        module.applySchedule()
        // Registers the phone soon; nothing else is sent before a measurement has its point.
        module.uploadSoon(5)
        if (startAfter) {
            val since = System.currentTimeMillis()
            MeasurementService.start(context)
            beginLocate(null, since)
        } else if (l.running && l.sharedPoint == null) {
            beginLocate(l.measurementStartMs)
        }
    }

    flow.onStart = {
        val s = store.state.value
        when {
            !s.enabled && s.decision == ShareDecision.UNDECIDED -> dialog = ShareDialog.Consent(startAfter = true)
            s.enabled -> {
                val since = System.currentTimeMillis()
                MeasurementService.start(context)
                beginLocate(null, since)
            }
            else -> MeasurementService.start(context)
        }
    }
    flow.onSetSharing = { on ->
        if (on) {
            if (store.state.value.decision == ShareDecision.UNDECIDED) dialog = ShareDialog.Consent(startAfter = false) else yes(false)
        } else {
            job?.cancel()
            flow.locate = LocateState.IDLE
            module.uploader.disable()
            module.applySchedule()
        }
    }
    flow.onAsk = { dialog = ShareDialog.Consent(startAfter = false) }
    flow.onOptions = { dialog = ShareDialog.Options }

    // Foreground only: leaving the app cancels a running fix; coming back retries it while the
    // running shared measurement has no point (also after the person allowed the location or
    // switched it on in the system settings).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    if (flow.locate == LocateState.BUSY && job?.isActive == true) {
                        job?.cancel()
                        flow.locate = LocateState.IDLE
                    }
                }
                Lifecycle.Event.ON_START -> {
                    foreground = true
                    val l = c.live.value
                    if (l.running && l.sharedPoint == null && store.state.value.enabled &&
                        flow.locate != LocateState.BUSY && LocationFetcher.hasPreciseLocation(context)
                    ) beginLocate(l.measurementStartMs)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    when (val d = dialog) {
        null -> Unit
        is ShareDialog.Consent -> ConsentDialog(
            sigmaM = store.state.value.sigmaM,
            onYes = { dialog = null; yes(d.startAfter) },
            onNo = {
                dialog = null
                store.update { it.copy(decision = ShareDecision.NO) }
                if (d.startAfter) MeasurementService.start(context)
            },
            // Back / outside: nothing starts, nothing is decided.
            onDismiss = { dialog = null },
        )
        ShareDialog.Options -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Kein Standort") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(lastOutcome?.let { LocationPolicy.message(it) } ?: NO_POINT_YET)
                    Button(onClick = {
                        dialog = null
                        val l = c.live.value
                        if (l.running) beginLocate(l.measurementStartMs)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Nochmals") }
                    lastOutcome?.let { SettingsButton(context, it) }
                    OutlinedButton(onClick = { dialog = ShareDialog.Paste }, modifier = Modifier.fillMaxWidth()) { Text("Koordinaten einfügen") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Schliessen") } },
        )
        ShareDialog.Paste -> {
            var text by remember { mutableStateOf("") }
            val parsed = if (text.isBlank()) null else CellInput.parse(text)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Koordinaten einfügen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Z. B. aus einer Karten-App: Punkt lange drücken, Koordinaten kopieren.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, singleLine = true,
                            label = { Text("Koordinaten, z. B. 47.3769, 8.5417") }, modifier = Modifier.fillMaxWidth(),
                        )
                        when (parsed) {
                            is CellInput.Parsed.Ok -> Text(pastedText(store.state.value.sigmaM), style = MaterialTheme.typography.bodySmall)
                            is CellInput.Parsed.Error -> Text(parsed.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            null -> Unit
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = parsed is CellInput.Parsed.Ok,
                        onClick = {
                            val ok = parsed as? CellInput.Parsed.Ok ?: return@TextButton
                            dialog = null
                            val l = c.live.value
                            if (!l.running) return@TextButton
                            val point = LocationPolicy.fromPasted(ok, noise()).point
                            job?.cancel()
                            target = l.measurementStartMs
                            job = scope.launch {
                                if (attach(point)) { lastOutcome = null; flow.locate = LocateState.IDLE }
                            }
                        },
                    ) { Text("Übernehmen") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
            )
        }
    }
    return flow
}

/** In the options dialog when no fix was tried yet for the running measurement. */
const val NO_POINT_YET = "Diese Messung hat noch keinen Standort."

/** «Auf die Lärmkarte?» as a dialog (its content: [ConsentCard]). */
@Composable
fun ConsentDialog(sigmaM: Int, onYes: () -> Unit, onNo: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        ConsentCard(sigmaM = sigmaM, onYes = onYes, onNo = onNo)
    }
}

/**
 * The question «Auf die Lärmkarte?» (server/DESIGN.md §2 rule 3): three short lines — what is
 * sent (with the shifted point, rule 2), what never, where, and how to switch off and delete —
 * and the full explanation of «Teilen» one tap away («Mehr dazu»). Also rendered on the JVM.
 */
@Composable
fun ConsentCard(sigmaM: Int, onYes: () -> Unit, onNo: () -> Unit, initiallyMore: Boolean = false) {
    var more by rememberSaveable { mutableStateOf(initiallyMore) }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(CONSENT_TITLE, style = MaterialTheme.typography.headlineSmall)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                consentLines(sigmaM).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { more = !more }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (more) "$CONSENT_MORE ▾" else "$CONSENT_MORE ▸")
                }
                if (more) ShareDetails(sigmaM)
            }
            Button(onClick = onYes, modifier = Modifier.fillMaxWidth()) { Text(CONSENT_YES) }
            OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth()) { Text(CONSENT_NO) }
        }
    }
}

/** Steps of «Laut! Melden». */
private sealed interface ReportStep {
    data object Consent : ReportStep
    data object Category : ReportStep
    data class Locating(val category: ReportCategory) : ReportStep
    data class Failed(val category: ReportCategory, val outcome: LocationPolicy.Outcome) : ReportStep
    data class Confirm(val category: ReportCategory, val fix: LocationPolicy.ReportOutcome.Fix) : ReportStep
}

/**
 * «Laut! Melden» (Karten editions, while no measurement runs): consent (only while «Teilen» is
 * off, i.e. not after «Ja, auf die Karte») → «Was war laut?» → ONE precise fix (≤ 50 m) → «Meldung: …, Standort ± n m –
 * Senden?» → queued and uploaded (UploadModule.uploadSoon). The time is the tap on the button,
 * to the second. [onQueued] is called after «Senden».
 *
 * Returns the action for the button.
 */
@Composable
fun rememberReportAction(onQueued: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<ReportStep?>(null) }
    var tapMs by remember { mutableStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun locate(category: ReportCategory) {
        step = ReportStep.Locating(category)
        job?.cancel()
        job = scope.launch {
            step = when (val o = LocationFetcher.locateForReport(context)) {
                is LocationPolicy.ReportOutcome.Fix -> ReportStep.Confirm(category, o)
                is LocationPolicy.ReportOutcome.Problem -> ReportStep.Failed(category, o.outcome)
            }
        }
    }

    var pendingCategory by remember { mutableStateOf<ReportCategory?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cat = pendingCategory ?: return@rememberLauncherForActivityResult
        when (LocationFetcher.permission(context)) {
            LocationPolicy.Permission.PRECISE -> locate(cat)
            LocationPolicy.Permission.APPROXIMATE_ONLY -> step = ReportStep.Failed(cat, LocationPolicy.Outcome.ApproximateOnly)
            LocationPolicy.Permission.DENIED -> step = ReportStep.Failed(cat, LocationPolicy.Outcome.PermissionDenied)
        }
    }

    fun choose(category: ReportCategory) {
        pendingCategory = category
        if (LocationFetcher.hasPreciseLocation(context)) locate(category) else permission.launch(LocationFetcher.PERMISSIONS)
    }

    when (val s = step) {
        null -> Unit
        ReportStep.Consent -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Meldungen senden?") },
            text = { Text(REPORT_CONSENT_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    val m = c.upload
                    // Switches «Teilen» on (decision YES): the question «Auf die Lärmkarte?» is not asked again.
                    m.uploader.enable()
                    m.applySchedule()
                    step = ReportStep.Category
                }) { Text("Ja, einschalten") }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
        ReportStep.Category -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Was war laut?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportCategory.entries.forEach { cat ->
                        Button(onClick = { choose(cat) }, modifier = Modifier.fillMaxWidth()) { Text(cat.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
        is ReportStep.Locating -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Meldung: ${s.category.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocationPolicy.PROGRESS_TEXT)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job?.cancel(); step = null }) { Text("Abbrechen") } },
        )
        is ReportStep.Failed -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Kein genauer Standort") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocationPolicy.message(s.outcome))
                    SettingsButton(context, s.outcome)
                }
            },
            confirmButton = { TextButton(onClick = { choose(s.category) }) { Text("Nochmals") } },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
        is ReportStep.Confirm -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Meldung") },
            text = { Text(reportConfirmText(s.category, tapMs, s.fix.accuracyText)) },
            confirmButton = {
                TextButton(onClick = {
                    val zone = ZoneId.systemDefault()
                    ReportQueue.add(
                        c.upload.reports,
                        PendingReport(
                            epochMs = tapMs, timeIso = PendingReport.isoSeconds(tapMs, zone),
                            lat = s.fix.lat, lon = s.fix.lon, accuracyM = s.fix.accuracyM,
                            category = s.category.id, cell = s.fix.cell,
                        ),
                    )
                    c.upload.uploadSoon(5)
                    step = null
                    onQueued()
                }) { Text("Senden") }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
    }

    return {
        tapMs = System.currentTimeMillis()
        // Already «Ja, auf die Karte» (sharing on): no second consent.
        step = if (c.upload.store.state.value.enabled) ReportStep.Category else ReportStep.Consent
    }
}

/**
 * The short consent before the first report while «Teilen» is off: what is sent, never audio, that
 * the point is public (server/DESIGN.md §2 rule 10), and how to delete.
 */
const val REPORT_CONSENT_TEXT =
    "Gesendet werden Zeit, genauer Standort und Art des Lärms, nie Audio. " +
        "Der Punkt erscheint auf der Lärmkarte; wer oft vom gleichen Ort meldet, macht ihn sichtbar. " +
        "Dafür wird «Teilen» eingeschaltet; löschen kannst du alles mit «Vom Server löschen»."

/** «Meldung: Töff, 16:05, Standort ± 12 m – senden?» */
fun reportConfirmText(category: ReportCategory, tapMs: Long, accuracyM: Int, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = java.time.Instant.ofEpochMilli(tapMs).atZone(zone)
    return "Meldung: ${category.label}, ${"%02d:%02d".format(t.hour, t.minute)}, Standort ± $accuracyM m – senden?"
}

@Composable
private fun SettingsButton(context: Context, o: LocationPolicy.Outcome) {
    when (o) {
        LocationPolicy.Outcome.ApproximateOnly, LocationPolicy.Outcome.PermissionDenied ->
            OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("App-Einstellungen") }
        LocationPolicy.Outcome.LocationOff ->
            OutlinedButton(onClick = { openLocationSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("Standort einschalten") }
        else -> Unit
    }
}

fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {}
}

fun openLocationSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {}
}
