package ch.lessnoise.app

import android.app.Application
import android.content.Context
import ch.lessnoise.app.data.AppDatabase
import ch.lessnoise.app.data.CalibrationRepository
import ch.lessnoise.app.data.MeasurementRepository
import ch.lessnoise.app.data.NoiseFloorStore
import ch.lessnoise.app.data.Recalibrator
import ch.lessnoise.app.data.SettingsStore
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.ui.LocateState
import ch.lessnoise.app.update.UpdateReminderStore
import ch.lessnoise.app.upload.UploadModule
import ch.lessnoise.dsp.classify.CategoryMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LessNoiseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 0.8.5: «Laut! Melden» photos — the camera's and the dialog's leftovers (the app killed meanwhile)
        // are deleted, and from then on every photo no report refers to any more (a report dropped,
        // rejected or rolled out of the sent list, or its photo deleted), on every change of the queue.
        if (BuildConfig.UPLOAD_AVAILABLE) {
            container.appScope.launch(Dispatchers.IO) {
                ch.lessnoise.app.ui.ReportPhotos.clearLeftovers(this@LessNoiseApp)
                val reports = container.upload.reports
                reports.state.collect { ch.lessnoise.app.ui.ReportPhotos.sweep(this@LessNoiseApp, reports) }
            }
        }
        // The phone's noise floor («Messgrenze», 0.6.5): estimated once per app start, off the main thread.
        container.noiseFloor.refresh()
        // «Messwerte teilen»: keeps the upload schedule in line with the setting and uploads after
        // a measurement stops. Does nothing (no network) while sharing is off, and nothing at all
        // in the Offline and Labor builds.
        // Started off the main thread (the token is read through the Android Keystore).
        if (BuildConfig.UPLOAD_AVAILABLE) {
            container.appScope.launch { container.upload.start(container.appScope, container.live, container.recalibrator) }
            // «Unterwegs» (0.6.3): the «Sehr laut» notification; since 0.8.4 only Labor·Karte has it
            // (src/laborKarte); the stand-ins in src/nounterwegs (Online-Version) and src/nomap do nothing.
            ch.lessnoise.app.ui.LoudAlerts.start(this, container)
        } else {
            // An edition without upload installed over one with sharing on (Offline-Version over
            // Online-Version, Labor over the Labor edition with the map): switch it off, cancel the jobs.
            container.appScope.launch { container.upload.stopForOfflineEdition() }
        }
    }
}

/** Minimal manual dependency container. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val calibrations: CalibrationRepository by lazy { CalibrationRepository(db.calibrations()) }
    val measurements: MeasurementRepository by lazy { MeasurementRepository(db.measurements()) }
    val updateReminder: UpdateReminderStore by lazy { UpdateReminderStore(appContext) }
    /** For work that must outlive a screen (re-evaluating old measurements). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val recalibrator: Recalibrator by lazy { Recalibrator(db, appScope) }
    val live = MutableStateFlow(LiveState())
    /**
     * 0.7.2: the connected microphones and the user's choice («Einstellungen → Erweitert → Mikrofon»);
     * the built-in microphone unless another one was chosen and is connected.
     */
    val microphones: ch.lessnoise.app.audio.Microphones by lazy { ch.lessnoise.app.audio.Microphones(appContext, appScope) }
    /**
     * The phone's noise floor («Messgrenze», 0.6.5) for the audio source the app measures with,
     * estimated from the stored minutes (dsp NoiseFloor.kt); refreshed at app start and after each
     * measurement. Stays on the phone.
     */
    val noiseFloor: NoiseFloorStore by lazy {
        val source = ch.lessnoise.app.audio.AudioSourceSelector.select(appContext)
        NoiseFloorStore(db.measurements(), calibrations.activeFlow(source), source, appScope)
    }
    /**
     * The opt-in upload «Messwerte teilen» (server/DESIGN.md §4); with the tab «Karte» (0.6.3, a
     * WebView in src/karte/) one of the two network uses of the Karten editions.
     */
    val upload: UploadModule by lazy {
        UploadModule(appContext, db, calibrations, motionHoldMs = {
            // 0.6.7 («Unterwegs erkannt», editions with the map): while a «Messen» measurement runs,
            // its newest records wait until the motion detector could have marked them as moving.
            val l = live.value
            // 0.6.9 (Labor·Karte): longer while the position signal could still decide that the phone left.
            if (ch.lessnoise.app.motion.MotionWatch.AVAILABLE && l.running && !l.unterwegs) {
                maxOf(ch.lessnoise.app.upload.Uploader.MOTION_HOLD_MS, ch.lessnoise.app.motion.MotionWatch.extraHoldMs(System.currentTimeMillis()))
            } else 0L
        })
    }
    /** True while the calibration screen is using the microphone (blocks starting a measurement). */
    val calibrationActive = MutableStateFlow(false)
    /**
     * Karten editions (0.6.3): where the fix for the running measurement stands (ui/ShareFlow.kt).
     * Kept for the process so that «Messen» and «Teilen» show the same status line and a fix that
     * was interrupted by leaving the app is tried again when the app is opened.
     */
    val shareLocate = MutableStateFlow<LocateState>(LocateState.Idle)

    val labels: List<String> by lazy {
        CategoryMapper.parseLabels(appContext.assets.open("yamnet_labels.txt").bufferedReader().readText())
    }

    /** Category mapping; constructing it validates every name against the label file. */
    val categoryMapper: CategoryMapper by lazy {
        CategoryMapper.fromJson(appContext.assets.open("categories.json").bufferedReader().readText(), labels)
    }
}

val Context.container: AppContainer get() = (applicationContext as LessNoiseApp).container
