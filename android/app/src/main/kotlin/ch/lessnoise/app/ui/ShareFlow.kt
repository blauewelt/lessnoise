package ch.lessnoise.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.AppContainer
import ch.lessnoise.app.container
import ch.lessnoise.app.location.LocationFetcher
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.service.MeasurementService
import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.app.upload.NoiseSecret
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Karten editions only (BuildConfig.UPLOAD_AVAILABLE), since 0.6.3: «Starten» never waits for the
// location. The flow (server/DESIGN.md §2 rules 2–3, §9):
//
// 1. The first «Starten» asks ONE question, «Auf die Lärmkarte?» ([ShareDecision], asked once).
//    «Ja, auf die Karte» switches sharing on; «Nur auf dem Telefon» keeps it off. Either way the
//    measurement starts at once. Back: nothing starts.
// 2. With sharing on, the measurement starts WITHOUT a point; while the app is in the foreground the
//    one fix runs in parallel (LocationFetcher.locate, moved at once by the location noise). The
//    shifted point is then attached to the running measurement's row, so that all of its minutes
//    and events are sent, also those before the fix (MeasurementRepository.attachPoint,
//    Uploader.pointAttached). Never in the background: the fix runs in the activity's scope, is
//    cancelled when the app leaves the screen (ON_STOP) and tried again when it comes back (ON_START).
// 3. No fix: no dialog; the status line says «Kein Standort – tippen» and the tap offers the options.
// 4. Switching sharing on during a measurement (tab «Teilen», or a tap on the status line) shares that
//    whole measurement the same way. A measurement that already ended unshared stays unshared.

/** The controller of the flow above; null in the Offline and Labor builds. */
val LocalShareController = staticCompositionLocalOf<ShareController?> { null }

/** The dialogs of the flow. */
sealed interface ShareDialog {
    /** «Auf die Lärmkarte?»; [startMeasurement]: started from «Starten» (else from the switch / status line). */
    data class Consent(val startMeasurement: Boolean) : ShareDialog

    /** No fix for the running measurement: the options. */
    data object LocationOptions : ShareDialog

    /** «Koordinaten einfügen» for the running measurement. */
    data object Paste : ShareDialog
}

@Stable
class ShareController internal constructor(
    private val app: Context,
    private val c: AppContainer,
    /** The activity's scope: the fix runs only while the app is open. */
    private val scope: CoroutineScope,
) {
    var dialog: ShareDialog? by mutableStateOf(null)
        internal set

    /** Set by [rememberShareController]: the system's location permission prompt. */
    internal var requestPermission: () -> Unit = {}

    private var job: Job? = null
    private val module get() = c.upload
    private val store get() = c.upload.store

    /**
     * «Messen» (the microphone is allowed): the question once, then the measurement at once.
     * [unterwegs] (0.6.4, the button «Unterwegs»): the measurement starts at once and is never
     * shared — no question, no location, no point; only the «Sehr laut» notification and «Laut!
     * Melden» (its own consent) are possible.
     */
    fun start(unterwegs: Boolean = false) {
        if (unterwegs) {
            MeasurementService.start(app, unterwegs = true)
            return
        }
        val s = store.state.value
        when {
            s.shareDecision == ShareDecision.UNDECIDED -> dialog = ShareDialog.Consent(startMeasurement = true)
            s.enabled -> { MeasurementService.start(app); wantPoint(prompt = true) }
            else -> MeasurementService.start(app)
        }
    }

    /**
     * Sharing on, from the switch in «Teilen» ([ask] false: the question only while it was never
     * answered) or from a tap on the status line ([ask] true: always the question, it is the
     * explanation).
     */
    fun switchOn(ask: Boolean) {
        if (ask || store.state.value.shareDecision == ShareDecision.UNDECIDED) {
            dialog = ShareDialog.Consent(startMeasurement = false)
        } else {
            enableAndLocate()
        }
    }

    /** Sharing off: uploads stop at once (and no fix is wanted any more). */
    fun switchOff() {
        job?.cancel()
        module.uploader.disable()
        module.applySchedule()
        c.shareLocate.value = LocateState.Idle
    }

    fun consentYes(startMeasurement: Boolean) {
        dialog = null
        if (startMeasurement) {
            enable()
            MeasurementService.start(app)
            wantPoint(prompt = true)
        } else {
            enableAndLocate()
        }
    }

    fun consentNo(startMeasurement: Boolean) {
        dialog = null
        if (store.state.value.shareDecision != ShareDecision.NO || store.state.value.enabled) {
            module.uploader.disable()
            module.applySchedule()
        }
        if (startMeasurement) MeasurementService.start(app)
    }

    fun dismiss() {
        dialog = null
    }

    /** A tap on the status line (the tab switches are handled by the screen). */
    fun onStatus(action: ShareAction) {
        when (action) {
            ShareAction.TURN_ON -> switchOn(ask = true)
            ShareAction.LOCATION_OPTIONS -> dialog = ShareDialog.LocationOptions
            ShareAction.NONE, ShareAction.OPEN_MAP, ShareAction.OPEN_SHARE -> Unit
        }
    }

    private fun enable() {
        module.uploader.enable()
        module.applySchedule()
        // Registers soon, so that «Vom Server löschen» works from the first minute.
        module.uploadSoon(5)
    }

    private fun enableAndLocate() {
        enable()
        val l = c.live.value
        // An «Unterwegs» measurement stays unshared (0.6.4): sharing on only affects the next «Messen».
        if ((l.running || l.starting) && l.sharedPoint == null && !l.unterwegs) wantPoint(prompt = true)
    }

    /** A point for the running (or just starting) measurement is wanted. */
    private fun wantPoint(prompt: Boolean) {
        c.shareLocate.value = LocateState.Pending
        tryLocate(prompt)
    }

    /** One fix if the permission allows it; [prompt]: ask Android for it first if needed. */
    fun tryLocate(prompt: Boolean) {
        dialog = null
        when (LocationFetcher.permission(app)) {
            LocationPolicy.Permission.PRECISE -> runFix()
            LocationPolicy.Permission.APPROXIMATE_ONLY ->
                if (prompt) requestPermission() else c.shareLocate.value = LocateState.Failed(LocationPolicy.Outcome.ApproximateOnly)
            LocationPolicy.Permission.DENIED ->
                if (prompt) requestPermission() else c.shareLocate.value = LocateState.Failed(LocationPolicy.Outcome.PermissionDenied)
        }
    }

    /** The answer of the system's permission prompt. */
    internal fun onPermissionResult() = tryLocate(prompt = false)

    private fun noise() = LocationPolicy.Noise(NoiseSecret.getOrCreate(store), store.state.value.sigmaM)

    private fun runFix() {
        job?.cancel()
        c.shareLocate.value = LocateState.Locating
        job = scope.launch {
            when (val o = LocationFetcher.locate(app, noise())) {
                is LocationPolicy.Outcome.Shifted -> attach(o.point)
                else -> c.shareLocate.value = LocateState.Failed(o)
            }
        }
    }

    /** «Koordinaten einfügen» for the running measurement: moved by the location noise like a fix. */
    fun pasted(parsed: CellInput.Parsed.Ok) {
        dialog = null
        attach(LocationPolicy.fromPasted(parsed, noise()).point)
    }

    /**
     * Gives the running measurement its shifted point: the row (once the service has written it),
     * the live state, then the upload ([UploadModule.pointAttached]). Runs in the app's scope so that
     * leaving the screen does not lose a point that is already there.
     */
    private fun attach(point: LocationNoise.Point) {
        c.appScope.launch {
            val l = withTimeoutOrNull(START_WAIT_MS) { c.live.first { it.running && it.measurementStartMs != 0L } }
            var ok = false
            // Never for an «Unterwegs» measurement (0.6.4; the database refuses it too).
            if (l != null && !l.unterwegs) {
                // The service inserts the row right after «Starten»; give it a moment if the fix was faster.
                for (attempt in 0 until 20) {
                    if (c.measurements.attachPoint(l.measurementStartMs, point)) { ok = true; break }
                    if (!c.live.value.running) break
                    delay(500)
                }
            }
            if (ok && l != null) {
                c.live.update { if (it.running && it.measurementStartMs == l.measurementStartMs) it.copy(sharedPoint = point) else it }
                module.pointAttached(l.measurementStartMs)
            }
            // Not attached: the measurement ended in the meantime (it stays unshared) or already had a point.
            c.shareLocate.value = LocateState.Idle
        }
    }

    /** The app came to the foreground: a fix that was wanted (or failed) is tried again, without a prompt. */
    internal fun onForeground() {
        val l = c.live.value
        val s = store.state.value
        val st = c.shareLocate.value
        if (l.running && !l.unterwegs && l.sharedPoint == null && s.enabled && (st is LocateState.Pending || st is LocateState.Failed)) {
            tryLocate(prompt = false)
        }
    }

    /** The app left the foreground: no location while it is not open. */
    internal fun onBackground() {
        if (job?.isActive == true) {
            job?.cancel()
            c.shareLocate.value = LocateState.Pending
        }
    }

    /** The measurement stopped: nothing more to locate. */
    internal fun onStopped() {
        job?.cancel()
        c.shareLocate.value = LocateState.Idle
        if (dialog == ShareDialog.LocationOptions || dialog == ShareDialog.Paste) dialog = null
    }

    @Composable
    fun Dialogs() {
        val context = LocalContext.current
        when (val d = dialog) {
            null -> Unit
            is ShareDialog.Consent -> {
                val s by store.state.collectAsStateWithLifecycle()
                Dialog(onDismissRequest = ::dismiss) {
                    ConsentDialogContent(
                        sigmaM = s.sigmaM,
                        onYes = { consentYes(d.startMeasurement) },
                        onNo = { consentNo(d.startMeasurement) },
                    )
                }
            }
            ShareDialog.LocationOptions -> {
                val st by c.shareLocate.collectAsStateWithLifecycle()
                val outcome = (st as? LocateState.Failed)?.outcome
                AlertDialog(
                    onDismissRequest = ::dismiss,
                    title = { Text("Kein Standort") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (outcome != null) LocationPolicy.message(outcome) else STATUS_NO_POINT_HINT)
                            Button(onClick = { wantPoint(prompt = true) }, modifier = Modifier.fillMaxWidth()) { Text("Nochmals") }
                            if (outcome != null) LocationSettingsButton(context, outcome)
                            OutlinedButton(onClick = { dialog = ShareDialog.Paste }, modifier = Modifier.fillMaxWidth()) { Text("Koordinaten einfügen") }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = ::dismiss) { Text("Schliessen") } },
                )
            }
            ShareDialog.Paste -> {
                var text by remember { mutableStateOf("") }
                val parsed = if (text.isBlank()) null else CellInput.parse(text)
                AlertDialog(
                    onDismissRequest = ::dismiss,
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
                        TextButton(enabled = parsed is CellInput.Parsed.Ok, onClick = { (parsed as? CellInput.Parsed.Ok)?.let(::pasted) }) { Text("Übernehmen") }
                    },
                    dismissButton = { TextButton(onClick = ::dismiss) { Text("Abbrechen") } },
                )
            }
        }
    }

    companion object {
        /** How long [attach] waits for the service to report the running measurement. */
        const val START_WAIT_MS = 60_000L

        /** Options dialog without a known reason (the fix was never tried in this process). */
        const val STATUS_NO_POINT_HINT = "Für die Karte braucht die Messung einen Standort."
    }
}

/**
 * The controller, once per activity (AppRoot). Its fix runs in the activity's scope and is cancelled
 * when the app leaves the foreground; the location prompt is the system's.
 */
@Composable
fun rememberShareController(): ShareController {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val controller = remember { ShareController(context.applicationContext, c, scope) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.onPermissionResult()
    }
    controller.requestPermission = { permission.launch(LocationFetcher.PERMISSIONS) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> controller.onForeground()
                Lifecycle.Event.ON_STOP -> controller.onBackground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.onBackground() }
    }
    LaunchedEffect(controller) {
        var wasRunning = c.live.value.running || c.live.value.starting
        c.live.collect { l ->
            val now = l.running || l.starting
            if (wasRunning && !now) controller.onStopped()
            wasRunning = now
        }
    }
    return controller
}

/**
 * «Auf die Lärmkarte?»: three lines, «Mehr dazu» unfolds the full explanation of «Teilen» in place
 * (and links the privacy page), «Ja, auf die Karte» / «Nur auf dem Telefon». Also rendered on the JVM
 * (ShareScreenshots).
 */
@Composable
fun ConsentDialogContent(sigmaM: Int, onYes: () -> Unit, onNo: () -> Unit, initiallyExpanded: Boolean = false) {
    var more by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(CONSENT_TITLE, style = MaterialTheme.typography.headlineSmall)
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                consentLines(sigmaM).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (!more) {
                    TextButton(onClick = { more = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(CONSENT_MORE)
                    }
                } else {
                    ShareExplanation(sigmaM)
                }
            }
            Spacer(Modifier.padding(2.dp))
            Button(onClick = onYes, modifier = Modifier.fillMaxWidth()) { Text(CONSENT_YES) }
            OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth()) { Text(CONSENT_NO) }
        }
    }
}

/** The full explanation (what is sent, location, never sent, where, when, delete): unchanged in substance since 0.6.2. */
@Composable
fun ShareExplanation(sigmaM: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExplanationBlock("Was gesendet wird", SHARE_SENT_BULLETS)
        ExplanationBlock("Standort", shareLocationBullets(sigmaM))
        ExplanationBlock("Nie gesendet", listOf("Audio, Name, Telefonnummer oder E-Mail.", LOCATION_NEVER_SENT))
        ExplanationBlock("Wohin", SHARE_WHERE_BULLETS)
        ExplanationBlock("Wann", SHARE_WHEN_BULLETS)
        ExplanationBlock("Löschen", SHARE_DELETE_BULLETS)
        MoreLink(Links.PRIVACY, "Datenschutz")
    }
}

@Composable
private fun ExplanationBlock(title: String, lines: List<String>) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
}

/**
 * Whether the phone has a connection with internet right now (ConnectivityManager; needs
 * ACCESS_NETWORK_STATE, which the Karten editions have). Only for the status line «Kein Internet».
 */
@Composable
fun rememberOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(true) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun check() {
            online = try {
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } catch (_: SecurityException) {
                true
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = check()
            override fun onLost(network: Network) = check()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = check()
        }
        check()
        val registered = try { cm?.registerDefaultNetworkCallback(callback); cm != null } catch (_: Exception) { false }
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return online
}
