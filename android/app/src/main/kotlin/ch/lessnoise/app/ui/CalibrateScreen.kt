package ch.lessnoise.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Slider
import androidx.compose.material3.ButtonDefaults
import kotlinx.coroutines.flow.flowOf
import ch.lessnoise.app.data.AppSettings
import ch.lessnoise.dsp.calibration.EventFloor
import ch.lessnoise.dsp.calibration.Recalibration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.lessnoise.app.audio.AudioSourceSelector
import ch.lessnoise.app.container
import ch.lessnoise.app.data.CalibrationEntity
import ch.lessnoise.app.data.Recalibrator
import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.calibration.CalibrationMath
import ch.lessnoise.dsp.calibration.CalibrationWarning
import kotlinx.coroutines.launch

private fun parseDb(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

fun warningText(w: CalibrationWarning): String = when (w) {
    CalibrationWarning.UNSTEADY -> "Zu unruhig. Nimm ein gleichmässigeres Geräusch."
    CalibrationWarning.TOO_QUIET -> "Zu leise (unter 50 dB(A)). Nimm ein lauteres Geräusch."
    CalibrationWarning.FREQUENCY_OFF -> "Kein 1-kHz-Ton. Sitzt der Kalibrator richtig?"
    CalibrationWarning.NOT_TONAL -> "Signal ist kein sauberer Ton (Ankopplung prüfen, Umgebung leiser)."
    CalibrationWarning.IMPLAUSIBLE_OFFSET -> "Korrektur über 20 dB – vermutlich ein Fehler."
    CalibrationWarning.CLIPPING -> "Zu laut für das Telefon. Nimm 94 statt 114 dB."
}

fun methodName(m: String): String = when (m) {
    CalibrationEntity.METHOD_REFERENCE -> "Messgerät"
    CalibrationEntity.METHOD_CALIBRATOR -> "Kalibrator"
    CalibrationEntity.METHOD_MANUAL -> "von Hand"
    CalibrationEntity.METHOD_RESET -> "zurückgesetzt"
    else -> m
}

/**
 * Einstellungen › «Erweitert» (0.6.1): the two remaining detector options («Lärmspitzen») and
 * «Kalibrieren» (formerly its own tab), including «Alte Messungen anpassen» (formerly under «Daten»).
 */
@Composable
fun AdvancedScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: CalibrationViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val recal by vm.recalibration.collectAsStateWithLifecycle()
    val live by c.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingMode by remember { mutableStateOf<CalMode?>(null) }
    val requestPermission = rememberMicPermissionLauncher { granted -> if (granted) pendingMode?.let { vm.start(it) } }
    val startMode: (CalMode) -> Unit = { mode ->
        if (hasMicPermission(context)) vm.start(mode) else { pendingMode = mode; requestPermission() }
    }
    val busy = state.running != null
    val measuring = live.running || live.starting
    // Calibration must happen in the foreground: abort (unsaveable) when the app is stopped.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.onAppStopped() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) { Text("‹ Einstellungen") }
            Text(
                "Erweitert", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            if (live.running) {
                Text(
                    "Gilt ab der nächsten Messung.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // Lärmspitzen: the two detector options left (hidden here since 0.6.1). ---------------
        item {
            SectionCard("Lärmspitzen") {
                StatRow("Lärmspitze ab … über Grundrauschen", "${Fmt.db(settings.eventExcessDb, 1)} dB")
                Slider(
                    value = settings.eventExcessDb.toFloat(),
                    valueRange = AppSettings.EVENT_EXCESS_MIN.toFloat()..AppSettings.EVENT_EXCESS_MAX.toFloat(),
                    steps = ((AppSettings.EVENT_EXCESS_MAX - AppSettings.EVENT_EXCESS_MIN) * 2).toInt() - 1, // 0.5 dB steps
                    onValueChange = { v -> c.settings.update { it.copy(eventExcessDb = EventFloor.roundToHalf(v.toDouble())) } },
                )
                Text("Kleiner = mehr Spitzen. Standard 6.5 dB.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                StatRow("Lärmspitzen erst ab", "${Fmt.db(settings.eventMinLevelDb, 1)} dB(A)")
                Slider(
                    value = settings.eventMinLevelDb.toFloat(),
                    valueRange = AppSettings.EVENT_MIN_LEVEL_MIN.toFloat()..AppSettings.EVENT_MIN_LEVEL_MAX.toFloat(),
                    // 0.5 dB steps: the floor follows calibrations in 0.5 dB steps.
                    steps = ((AppSettings.EVENT_MIN_LEVEL_MAX - AppSettings.EVENT_MIN_LEVEL_MIN) * 2).toInt() - 1,
                    onValueChange = { v -> c.settings.update { it.copy(eventMinLevelDb = EventFloor.roundToHalf(v.toDouble())) } },
                )
                Text("Filtert Geräusche am Telefon selbst (Tippen, Atmen). Standard 30 dB(A).", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Kalibrieren ---------------------------------------------------------------------------
        item {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text("Kalibrieren", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Nur nötig, wenn du ein Messgerät hast. Ohne Kalibrierung sind die Werte Richtwerte.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                MoreLink(Links.CALIBRATE)
            }
        }
        item {
            SectionCard {
                val a = active
                Text(
                    if (a == null || !a.isCalibrated) "Nicht kalibriert (Standardwert ${Fmt.db(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, 2)} dB)."
                    else "Kalibriert am ${Fmt.dateTime(a.createdEpochMs)}: ${Fmt.db(a.offsetDb, 2)} dB (${methodName(a.method)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                StatRow("Gerät", vm.deviceModel)
                StatRow("Audioquelle", AudioSourceSelector.labelDe(vm.source))
            }
        }
        state.message?.let { msg ->
            item {
                SectionCard {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.clearMessage() }) { Text("OK") }
                }
            }
        }
        // «Alte Messungen anpassen» (re-evaluation with the active calibration).
        val calibratedNow = active?.isCalibrated == true
        if (calibratedNow || recal !is Recalibrator.State.Idle) {
            item {
                val a = active?.takeIf { it.isCalibrated }
                val target = a?.let { Recalibration.Target(it.id, it.offsetDb, vm.source) }
                val counts by remember(target) { target?.let { c.recalibrator.counts(it) } ?: flowOf(0 to 0) }
                    .collectAsStateWithLifecycle(0 to 0)
                val (n, m) = counts
                val runningRecal = recal is Recalibrator.State.Running
                var confirm by remember { mutableStateOf(false) }
                SectionCard("Alte Messungen anpassen") {
                    if (target != null && recal is Recalibrator.State.Idle) {
                        Text(
                            when {
                                n + m == 0 -> "Alle Messungen sind schon angepasst."
                                measuring -> "Zuerst die Messung stoppen."
                                else -> "${CalibrationTexts.minutes(n)} noch nicht angepasst."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (n + m > 0) {
                            Button(onClick = { confirm = true }, enabled = !measuring && !runningRecal) { Text("Anpassen") }
                        }
                    }
                    RecalibrationStatus(recal) { vm.acknowledgeRecalibration() }
                }
                if (confirm && target != null) {
                    RecalibrationDialog(
                        minutes = n, measuring = measuring,
                        onConfirm = { confirm = false; c.recalibrator.start(target) },
                        onDismiss = { confirm = false },
                    )
                }
            }
        }
        if (busy) {
            item {
                SectionCard("Misst …") {
                    Text("App offen lassen, sonst bricht die Messung ab.", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    StatRow("Noch", "${state.remainingSeconds} s")
                    StatRow("Jetzt", "${Fmt.db(state.currentDb)} dB(A)")
                    OutlinedButton(onClick = { vm.cancel() }) { Text("Abbrechen") }
                }
            }
        }

        // 1. Reference meter (fixed 30 s since 0.6.1) ----------------------------------------
        item {
            var reading by remember { mutableStateOf("") }
            var notes by remember { mutableStateOf("") }
            SectionCard("Mit Messgerät vergleichen") {
                Text(
                    "Telefon neben das Messgerät halten, beide gleichzeitig ${state.referenceWindowSeconds} s messen, Wert unten eintragen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { startMode(CalMode.REFERENCE) }, enabled = !busy) { Text("Messen (${state.referenceWindowSeconds} s)") }
                state.referenceResult?.let { r ->
                    HorizontalDivider()
                    StatRow("Telefon", "${Fmt.db(r.rawLaeqDb + (active?.offsetDb ?: Acoustics.DEFAULT_CALIBRATION_OFFSET_DB))} dB(A)")
                    OutlinedTextField(
                        value = reading, onValueChange = { reading = it },
                        label = { Text("Wert am Messgerät (dB(A))") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notiz (freiwillig)") }, modifier = Modifier.fillMaxWidth())
                    val ref = parseDb(reading)
                    if (ref != null) {
                        StatRow("Korrektur", "${Fmt.db(CalibrationMath.referenceOffset(ref, r.rawLaeqDb), 2)} dB")
                    }
                    vm.referenceWarnings(ref).forEach { Text("⚠ " + warningText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { ref?.let { vm.saveReference(it, notes); reading = ""; notes = "" } }, enabled = ref != null) { Text("Übernehmen") }
                }
            }
        }

        // 2. Acoustic calibrator -------------------------------------------------------------
        item {
            var notes by remember { mutableStateOf("") }
            SectionCard("Mit Kalibrator (1 kHz)") {
                Text("Kalibrator mit Adapter dicht aufs Mikrofon setzen.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(94.0, 114.0).forEach { v ->
                        FilterChip(selected = state.calibratorNominalDb == v, onClick = { vm.setCalibratorNominal(v) }, label = { Text("${v.toInt()} dB") }, enabled = !busy)
                    }
                }
                Button(onClick = { startMode(CalMode.CALIBRATOR) }, enabled = !busy) { Text("Messen (10 s)") }
                state.calibratorResult?.let { r ->
                    HorizontalDivider()
                    StatRow("Frequenz", "${Fmt.db(r.toneFrequencyHz, 0)} Hz")
                    StatRow("Korrektur", "${Fmt.db(CalibrationMath.calibratorOffset(state.calibratorNominalDb, r.rawLaeqDb), 2)} dB")
                    val warnings = vm.calibratorWarnings()
                    warnings.forEach { Text("⚠ " + warningText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    val blocking = CalibrationWarning.FREQUENCY_OFF in warnings || CalibrationWarning.NOT_TONAL in warnings
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notiz (freiwillig)") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.saveCalibrator(notes); notes = "" }, enabled = !blocking) { Text("Übernehmen") }
                }
            }
        }

        // 3. Manual -----------------------------------------------------------------------------
        item {
            var offset by remember { mutableStateOf("") }
            var notes by remember { mutableStateOf("") }
            SectionCard("Korrektur von Hand") {
                OutlinedTextField(
                    value = offset, onValueChange = { offset = it }, label = { Text("Korrektur (dB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notiz (freiwillig)") }, modifier = Modifier.fillMaxWidth())
                val v = parseDb(offset)
                if (v != null && CalibrationMath.implausible(v)) {
                    Text("⚠ " + warningText(CalibrationWarning.IMPLAUSIBLE_OFFSET), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { v?.let { vm.saveManual(it, notes); offset = ""; notes = "" } }, enabled = v != null && !busy) { Text("Speichern") }
            }
        }

        // History --------------------------------------------------------------------------------
        item {
            SectionCard("Verlauf") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch { shareFiles(context, listOf(vm.exportHistory()), "application/json", "Weniger Lärm Kalibrierungen") }
                    }) { Text("Exportieren") }
                    TextButton(onClick = { vm.resetToDefault() }, enabled = !busy) { Text("Zurücksetzen") }
                }
                if (history.isEmpty()) Text("Noch keine Kalibrierungen.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(history, key = { it.id }) { h ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(
                    "#${h.id} · ${Fmt.dateTime(h.createdEpochMs)} · ${methodName(h.method)} · ${Fmt.db(h.offsetDb, 2)} dB · " +
                        AudioSourceSelector.labelDe(h.audioSource) + (if (h.notes.isNotBlank()) " · ${h.notes}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    state.recalOffer?.let { offer ->
        RecalibrationDialog(
            minutes = offer.minutes, measuring = measuring,
            onConfirm = { vm.confirmRecalibration() }, onDismiss = { vm.dismissRecalibration() },
        )
    }
}
