package ch.lessnoise.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import ch.lessnoise.app.R
import ch.lessnoise.app.audio.AudioCapture
import ch.lessnoise.app.audio.AudioSourceSelector
import ch.lessnoise.app.audio.MicChoice
import ch.lessnoise.app.audio.AudioTap
import ch.lessnoise.app.audio.AudioTapSession
import ch.lessnoise.app.audio.NoAudioTap
import ch.lessnoise.app.edition.AudioTapProvider
import ch.lessnoise.app.edition.EditionDetector
import ch.lessnoise.app.edition.EditionEventHead
import ch.lessnoise.app.motion.MotionWatch
import ch.lessnoise.app.classify.YamnetClassifier
import ch.lessnoise.app.container
import ch.lessnoise.app.audio.CaptureInfo
import ch.lessnoise.app.data.AppSettings
import ch.lessnoise.app.data.CalibrationRepository
import ch.lessnoise.app.ui.LoudAlerts
import ch.lessnoise.app.ui.MainActivity
import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.EngineConfig
import ch.lessnoise.dsp.EventFeatures
import ch.lessnoise.dsp.LafTick
import ch.lessnoise.dsp.MeasurementEngine
import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.NoiseEvent
import ch.lessnoise.dsp.SecondResult
import ch.lessnoise.dsp.classify.CategoryDecision
import ch.lessnoise.dsp.classify.ClassifierFrame
import ch.lessnoise.dsp.classify.ClassifierPreprocessor
import ch.lessnoise.dsp.classify.ClassifierResult
import ch.lessnoise.dsp.classify.LabelScore
import ch.lessnoise.dsp.classify.SoundClassifier
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Live values shown on the Measure screen. Only aggregates; never audio. */
data class LiveState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val startedAtMs: Long = 0,
    val audioSource: String? = null,
    val encoding: String? = null,
    val effects: List<String> = emptyList(),
    val calibrated: Boolean = false,
    val calibrationId: Long? = null,
    val calibrationOffsetDb: Double? = null,
    val lafDb: Double? = null,
    /**
     * 0.8.5: the newest 125 ms step's LAFmax and LAeq (null while invalid) and its time, read by
     * «Laut! Melden» while a measurement runs (ui/ReportLevel.kt) so its level means the same as
     * the dialog's own engine. Numbers only.
     */
    val lafMaxTickDb: Double? = null,
    val leqTickDb: Double? = null,
    val tickEpochMs: Long = 0,
    val laeq60sDb: Double? = null,
    val backgroundDb: Double? = null,
    /** Local floor (L90 over the trailing local window), the event reference since v0.4.0. */
    val localFloorDb: Double? = null,
    val dominantCategory: String? = null,
    val dominantScore: Float = 0f,
    val topLabels: List<LabelScore> = emptyList(),
    val lastMinute: MinuteRecord? = null,
    val classifierEnabled: Boolean = false,
    /**
     * The running measurement's shifted point if it is shared («Messwerte teilen»), else null. Since
     * 0.6.3 the measurement starts without it and the point is attached once the fix is there
     * (ui/ShareFlow.kt), or when sharing is switched on during the measurement.
     */
    val sharedPoint: LocationNoise.Point? = null,
    /** Start of the running measurement's row (MeasurementEntity.startMs); 0 when none runs. */
    val measurementStartMs: Long = 0,
    /** The system currently silences the microphone (call, voice assistant): audio not evaluated. */
    val micSilenced: Boolean = false,
    /** Current audio is not evaluated (silenced, digital silence or 0.5 s recovery after it). */
    val invalidAudio: Boolean = false,
    val error: String? = null,
    /**
     * The newest event of the running measurement (0.6.3): it starts the live upload of loud
     * events (UploadModule, LiveUploadPlan) and, in Labor·Karte (0.8.4; until 0.8.3 also the
     * Online-Version), the «Sehr laut» alert while «Unterwegs» is on. Null until the first event.
     */
    val lastEvent: LiveEvent? = null,
    /**
     * 0.6.4: the running measurement was started as «Unterwegs» (since 0.8.4 only Labor·Karte's
     * «Unterwegs aufnehmen» starts one): it measures, may show the «Sehr laut» notification and
     * allows «Laut! Melden», but shares nothing —
     * no point, no minutes, no events (MeasurementEntity.unterwegs).
     */
    val unterwegs: Boolean = false,
    /** 0.6.6: the input delivering the audio (BuiltInMic.label), shown in the Labor build only. */
    val inputDevice: String? = null,
    /**
     * 0.7.2: the line «Mikrofon: …» of the microphone in use (MicChoice.Resolution.status), e.g.
     * «Mikrofon: eingebaut» or «Mikrofon: eingebaut (gewähltes nicht verbunden)»; shown next to
     * «Audioquelle» in «Kalibrieren» and, unless it is the plain default, on «Messen».
     */
    val microphone: String? = null,
    /** 0.7.2: the key of the microphone in use (MicChoice.key); «builtin» by default. */
    val micKey: String = MicChoice.BUILTIN,
    /**
     * 0.6.7 («Unterwegs erkannt», editions with the map): in a measurement started with «Messen», the
     * motion detector says the phone is being carried. Meanwhile it behaves like «Unterwegs»: the
     * «Sehr laut» notification may show, and nothing of this time span is shared (moving_spans). Always
     * false in an «Unterwegs» measurement (that one is never shared anyway) and in the editions without the map.
     */
    val moving: Boolean = false,
    /**
     * 0.6.9, Labor·Karte only (null elsewhere): one line on «Messen» with the motion value, the distance
     * from the run's first position (rounded to 50 m, «–» without a fix) and the state, e.g.
     * «Bewegung 0,12 m/s² · 400 m · unterwegs». Never stored or sent.
     */
    val unterwegsReadout: String? = null,
)

/** An event as it was detected (0.6.3, [LiveState.lastEvent]). Levels as shown (calibrated if the phone is). */
data class LiveEvent(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val lafMaxDb: Double,
    /** Category id («loud_vehicle», «road_traffic» …), «unclassified», «wind», or null (classifier off). */
    val category: String?,
    /** Wind on the microphone (detector v2): not counted, not shared, never an alert. */
    val wind: Boolean,
    val calibrated: Boolean,
    /** The excess setting in force at the start (NoiseEvent.thresholdDb, sent as `threshold_db`); 0.6.4, for Payloads.isMapEvent. */
    val thresholdDb: Double,
    /** The LAFmax floor setting in force (NoiseEvent.minLevelDb; NaN before 0.3.0); 0.6.4, for Payloads.isMapEvent. */
    val minLevelDb: Double?,
)

/**
 * Foreground service (type "microphone") that runs the measurement so it continues for hours with
 * the screen off. Every start records a measurement row (MeasurementEntity) with the shifted point
 * it was started with; «Stop» closes it. Holds a partial wake lock while measuring (configurable): see README → Battery.
 */
class MeasurementService : Service() {

    companion object {
        const val ACTION_START = "ch.lessnoise.app.START"
        const val ACTION_STOP = "ch.lessnoise.app.STOP"
        /**
         * 0.7.6 (since 0.8.4 Labor·Karte only): the «Sehr laut» alert (src/laborKarte/…/alert/) replaces the
         * content of this service's notification for [LOUD_ALERT_WINDOW_MS] instead of posting a second
         * notification. Sent with startService while a measurement runs; ignored otherwise. Extras:
         * [EXTRA_ALERT_TITLE], [EXTRA_ALERT_CHANNEL] (the alert's own channel, importance HIGH, created
         * by the sender) and [EXTRA_ALERT_TAP] (the PendingIntent that opens «Laut! Melden»).
         */
        const val ACTION_LOUD_ALERT = "ch.lessnoise.app.LOUD_ALERT"
        const val EXTRA_ALERT_TITLE = "alert_title"
        const val EXTRA_ALERT_CHANNEL = "alert_channel"
        const val EXTRA_ALERT_TAP = "alert_tap"
        /** 0.7.6: how long the alert stays in the notification before the level text comes back. */
        const val LOUD_ALERT_WINDOW_MS = 30_000L
        /**
         * Since 0.3.3 importance DEFAULT (icon in the status bar). A channel's importance cannot be
         * raised after it was created, hence a new id; the old LOW channel is deleted.
         */
        private const val CHANNEL = "measurement_active"
        private const val LEGACY_CHANNEL = "measurement"
        private const val TAG = "MeasurementService"
        private const val NOTIFICATION_ID = 1
        /** 0.7.2: reopenings of the capture after a misroute allowed per minute. */
        private const val MAX_MISROUTES = 3

        /** Extras of ACTION_START: the shifted point of a shared measurement (absent: not shared). */
        const val EXTRA_POINT_E = "point_e"
        const val EXTRA_POINT_N = "point_n"
        const val EXTRA_SIGMA_M = "sigma_m"
        /** Extra of ACTION_START (0.6.4): started as «Unterwegs» (never shared). */
        const val EXTRA_UNTERWEGS = "unterwegs"

        fun start(context: Context, point: LocationNoise.Point? = null, unterwegs: Boolean = false) {
            val i = Intent(context, MeasurementService::class.java).setAction(ACTION_START)
            if (unterwegs) {
                i.putExtra(EXTRA_UNTERWEGS, true)
            } else if (point != null) {
                i.putExtra(EXTRA_POINT_E, point.e).putExtra(EXTRA_POINT_N, point.n).putExtra(EXTRA_SIGMA_M, point.sigmaM)
            }
            ContextCompat.startForegroundService(context, i)
        }

        /** The shifted point of an ACTION_START intent, or null (not shared, or incomplete). */
        private fun pointOf(intent: Intent?): LocationNoise.Point? {
            if (intent == null || !intent.hasExtra(EXTRA_POINT_E) || !intent.hasExtra(EXTRA_POINT_N)) return null
            val sigma = intent.getIntExtra(EXTRA_SIGMA_M, 0)
            if (!LocationNoise.isSigma(sigma)) return null
            return LocationNoise.Point(intent.getIntExtra(EXTRA_POINT_E, 0), intent.getIntExtra(EXTRA_POINT_N, 0), sigma)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeasurementService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capture: AudioCapture? = null
    private var engine: MeasurementEngine? = null
    /** Public build: always [NoAudioTap] (see AudioTap.kt, PRIVACY.md → «Labor-Build»). */
    @Volatile private var tap: AudioTap = NoAudioTap
    /** 0.6.7: the accelerometer watch of a «Messen» measurement (editions with the map; null elsewhere). */
    private var motion: MotionWatch.Handle? = null
    private var classifier: SoundClassifier? = null
    private var inference: ExecutorService? = null
    private val inferenceBusy = AtomicBoolean(false)
    /** Classifier results, tagged with the [generation] of the engine whose window they classified. */
    private val frames = ConcurrentLinkedQueue<Pair<Int, ClassifierFrame>>()
    /**
     * 0.7.2: the engine/capture pair in use; a new pair (other microphone, or the same one reopened)
     * gets the next number, so a late classifier result of the previous engine is dropped.
     */
    @Volatile private var generation = 0
    /** 0.7.2: the microphone the running capture records from. */
    @Volatile private var mic: MicChoice.Resolution? = null
    /** 0.7.2: the audio source of the running measurement (the same for every pipeline of it). */
    @Volatile private var audioSourceName: String = ""
    /** 0.7.2: follows the connected microphones while measuring. */
    private var micWatch: Job? = null
    /** 0.7.2: times (ms) the capture was reopened because Android routed another input. */
    private val misroutes = ArrayDeque<Long>()
    private var classifierWindow = FloatArray(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
    private var wakeLock: PowerManager.WakeLock? = null
    /** 0.6.8: level text every 2 s, or the fixed «Antippen zum Melden» while the run is «unterwegs». */
    private val cadence = NotificationCadence()
    /** The collector that re-posts the notification when the run becomes «unterwegs» or not (0.6.8). */
    private var tapWatch: Job? = null
    /**
     * 0.6.9: the notification's report tap/button in force (null: none). Read by the level posts, so
     * that a «Melden» button (Labor·Karte) stays on the notification. Guarded by [cadence].
     */
    private var currentTap: NotificationTap? = null
    /** 0.6.9 (Labor·Karte): the service runs with the foreground type «location» too (see [promoteToForeground]). */
    @Volatile private var positionAllowed = false
    @Volatile private var stopping = false
    @Volatile private var normalizeClassifierInput = true
    /** Row id of the running measurement, once its insert has finished (null if the insert failed). */
    @Volatile private var measurementRow: Deferred<Long?>? = null
    /** Database inserts still running; a stop waits for them (briefly) before leaving the foreground. */
    private val dbJobs: MutableSet<Job> = java.util.Collections.synchronizedSet(HashSet())

    private fun persist(what: String, block: suspend () -> Unit) {
        val job = scope.launch {
            try {
                block()
            } catch (e: Exception) {
                // Never log data, only the failure type.
                Log.w(TAG, "$what insert failed: ${e.javaClass.simpleName}")
            }
        }
        dbJobs += job
        job.invokeOnCompletion { dbJobs -= job }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMeasurement(); return START_NOT_STICKY }
            ACTION_LOUD_ALERT -> { showLoudAlert(intent, startId); return START_NOT_STICKY }
            else -> {
                val unterwegs = intent?.getBooleanExtra(EXTRA_UNTERWEGS, false) == true
                startMeasurement(if (unterwegs) null else pointOf(intent), unterwegs)
            }
        }
        return START_NOT_STICKY
    }

    private fun startMeasurement(point: LocationNoise.Point?, unterwegs: Boolean) {
        val live = container.live
        createChannel()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // startForeground() must succeed for every startForegroundService() — also when a
        // measurement is already running or the permission is missing — otherwise the system
        // kills the app. So promote first, and only then take any path that stops the service.
        val promotion = promoteToForeground(hasMic, wantPosition = !unterwegs)
        if (promotion == Promotion.FAILED) {
            live.value = live.value.copy(error = "Android hat die Messung nicht erlaubt. Bitte nochmals starten.")
            stopSelf(); return
        }
        if (live.value.running || live.value.starting) return
        if (promotion == Promotion.SHORT_SERVICE && hasMic) {
            live.value = LiveState(error = "Bitte die App öffnen und nochmals starten.")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(); return
        }
        if (!hasMic) {
            live.value = LiveState(error = "Ohne Mikrofon kann die App nicht messen.")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(); return
        }
        live.value = LiveState(starting = true, unterwegs = unterwegs)
        stopping = false
        inferenceBusy.set(false)
        scope.launch {
            try {
                val c = container
                val settings = c.settings.state.value
                normalizeClassifierInput = settings.classifierNormalize
                val source = AudioSourceSelector.select(this@MeasurementService)
                // 0.7.2: the built-in microphone, or the chosen one if it is connected; its own calibration.
                val res = c.microphones.resolveNow()
                val cal = c.calibrations.active(source, res.key)
                if (settings.classifierEnabled) {
                    classifier = YamnetClassifier(this@MeasurementService, c.labels)
                    inference = Executors.newSingleThreadExecutor { r -> Thread(r, "lessnoise-classifier") }
                    c.categoryMapper // loaded here (lazily, from assets), not under the lock in openPipeline
                }
                // Stop may have been requested (notification "Stopp") while the model was loading.
                // Commit the start atomically with respect to stopMeasurement(), so a stopped
                // service never ends up with a running microphone, a held wake lock or a stale
                // "running" state.
                synchronized(this@MeasurementService) {
                    if (stopping) {
                        releaseClassifier()
                        return@launch
                    }
                    audioSourceName = source
                    if (settings.wakeLock) acquireWakeLock()
                    val startedAt = System.currentTimeMillis()
                    measurementRow = scope.async {
                        try {
                            container.measurements.beginMeasurement(startedAt, point, unterwegs)
                        } catch (e: Exception) {
                            Log.w(TAG, "measurement insert failed: ${e.javaClass.simpleName}")
                            null
                        }
                    }
                    val info = openPipeline(source, res, cal, settings)
                    live.value = LiveState(
                        running = true, startedAtMs = System.currentTimeMillis(), audioSource = info.source,
                        encoding = info.encoding, effects = info.effects, calibrated = cal.calibrated,
                        calibrationId = cal.id, calibrationOffsetDb = cal.offsetDb,
                        classifierEnabled = settings.classifierEnabled, sharedPoint = point,
                        measurementStartMs = startedAt, unterwegs = unterwegs, inputDevice = info.inputDevice,
                        microphone = res.status, micKey = res.key,
                    )
                    // «Unterwegs erkannt» (0.6.7): only for «Messen» («Unterwegs» is unterwegs anyway),
                    // only in the editions with the map (MotionWatch is a stand-in returning null elsewhere).
                    if (!unterwegs && MotionWatch.AVAILABLE) motion = MotionWatch.start(this@MeasurementService, container, position = positionAllowed)
                    watchNotificationTap()
                    watchMicrophone()
                }
            } catch (e: Exception) {
                live.value = LiveState(error = MicChoice.errorText("Start fehlgeschlagen: ", e.message))
                synchronized(this@MeasurementService) {
                    if (stopping) releaseClassifier() else stopMeasurement()
                }
            }
        }
    }

    /**
     * 0.7.2: engine, audio tap and capture for the microphone [res] with its calibration [cal]
     * (called under the service's lock). The engine's records carry [res]'s key. If the capture
     * cannot start, the exception reaches the caller with [engine] and [tap] set, so that
     * stopMeasurement() releases them as before 0.7.2.
     */
    private fun openPipeline(
        source: String, res: MicChoice.Resolution, cal: CalibrationRepository.Active, settings: AppSettings,
    ): CaptureInfo {
        val config = EngineConfig(
            calibrationOffsetDb = cal.offsetDb,
            calibrated = cal.calibrated,
            calibrationId = cal.id,
            audioSource = source,
            eventExcessDb = settings.eventExcessDb,
            localFloorWindowSeconds = settings.localFloorWindowSeconds.toDouble(),
            windLfShareMin = settings.windLfShareMin,
            windFlutterMinDb = settings.windFlutterMinDb,
            eventMinLevelDb = settings.eventMinLevelDb,
            // 0.9.5: public editions always EventRules.V2 (unchanged); Labor editions V3 unless switched off.
            eventRules = EditionDetector.rules(this),
            classifierEnabled = settings.classifierEnabled,
            classifierIntervalSeconds = settings.classifierIntervalSeconds.toDouble(),
            zone = ZoneId.systemDefault(),
        )
        val mapper = if (settings.classifierEnabled) container.categoryMapper else null
        val gen = ++generation
        frames.clear()
        // Sample time is anchored to the wall clock when the first audio block arrives.
        // Event head: Labor editions only (logging into the Labor manifest); public editions get null.
        val eng = MeasurementEngine(config, mapper, EngineListener(res.key), eventHead = EditionEventHead.create())
        engine = eng
        mic = res
        val t = AudioTapProvider.create(this)
        tap = t
        val cap = AudioCapture(
            this, ::onBlock,
            onError = { msg ->
                container.live.value = container.live.value.copy(error = MicChoice.errorText("Mikrofon-Fehler: ", msg))
                // Called on the capture thread: stop from another thread so join() can complete.
                scope.launch { stopMeasurement() }
            },
            onSilenced = { silenced ->
                eng.setMicSilenced(silenced)
                container.live.update { it.copy(micSilenced = silenced) }
            },
            onInputDevice = { device -> container.live.update { it.copy(inputDevice = device) } },
            onMisrouted = { scope.launch { reopenAfterMisroute(gen) } },
        )
        val info = cap.start(source, res)
        capture = cap
        t.onSessionStart(
            AudioTapSession(
                startedAtMs = System.currentTimeMillis(), audioSource = info.source, encoding = info.encoding,
                effects = info.effects, inputDevice = info.inputDevice,
                calibrationId = cal.id, calibrationOffsetDb = cal.offsetDb,
                calibrated = cal.calibrated, eventExcessDb = config.eventExcessDb,
                localFloorWindowSeconds = config.localFloorWindowSeconds,
                eventMinLevelDb = config.eventMinLevelDb,
                windLfShareMin = config.windLfShareMin, windFlutterMinDb = config.windFlutterMinDb,
                classifierEnabled = settings.classifierEnabled,
                classifierNormalize = settings.classifierNormalize,
                classifierIntervalSeconds = config.classifierIntervalSeconds,
                eventRules = config.eventRules,
            )
        )
        return info
    }

    /** Stops capture, engine (its partial minute and open event are stored) and audio tap (under the lock). */
    private fun closePipeline() {
        capture?.stop()
        capture = null
        engine?.let { eng ->
            drainFrames(eng)
            eng.stop()
        }
        engine = null
        tap.onSessionStop()
        tap = NoAudioTap
        frames.clear()
    }

    /** Delivers the finished classifications of the current engine; drops those of an earlier one. */
    private fun drainFrames(eng: MeasurementEngine) {
        val gen = generation
        while (true) {
            val (g, frame) = frames.poll() ?: break
            if (g == gen) eng.onClassifierFrame(frame)
        }
    }

    /**
     * 0.7.2: while measuring, a chosen microphone is connected or disconnected (or the choice
     * changes): the measurement continues with the newly resolved microphone and its calibration —
     * the same measurement, a new engine. Only the status line changes if the microphone stays.
     */
    private fun watchMicrophone() {
        micWatch?.cancel()
        micWatch = scope.launch {
            container.microphones.resolution.collect { r ->
                val current = mic ?: return@collect
                if (r.key != current.key) switchMicrophone(r, expectedGeneration = null)
                else if (r.status != current.status) {
                    mic = r
                    container.live.update { if (it.running) it.copy(microphone = r.status) else it }
                }
            }
        }
    }

    /**
     * Reopens the pipeline with [res]: the old engine's partial minute is stored with the old
     * microphone, the new one starts with [res]'s calibration. [expectedGeneration]: only if that
     * pipeline is still the current one (a misroute report of a pipeline already replaced is void).
     */
    private suspend fun switchMicrophone(res: MicChoice.Resolution, expectedGeneration: Int?) {
        val source = audioSourceName
        val cal = try {
            container.calibrations.active(source, res.key)
        } catch (e: Exception) {
            Log.w(TAG, "calibration lookup failed: ${e.javaClass.simpleName}")
            CalibrationRepository.Active(null)
        }
        val settings = container.settings.state.value
        synchronized(this) {
            if (stopping || capture == null) return
            if (expectedGeneration != null && expectedGeneration != generation) return
            if (expectedGeneration == null && mic?.key == res.key) return
            // Kinds only in the log, never names or addresses.
            Log.i(TAG, "microphone ${MicChoice.kindId(mic?.key ?: MicChoice.BUILTIN)} -> ${MicChoice.kindId(res.key)}")
            closePipeline()
            try {
                val info = openPipeline(source, res, cal, settings)
                container.live.update {
                    it.copy(
                        calibrated = cal.calibrated, calibrationId = cal.id, calibrationOffsetDb = cal.offsetDb,
                        encoding = info.encoding, effects = info.effects, inputDevice = info.inputDevice,
                        microphone = res.status, micKey = res.key, micSilenced = false,
                    )
                }
            } catch (e: Exception) {
                container.live.update { it.copy(error = MicChoice.errorText("Mikrofon-Fehler: ", e.message)) }
                stopMeasurement()
            }
        }
    }

    /**
     * 0.7.2: Android routed another input than the resolved microphone during the measurement: reopen
     * the capture with the microphone resolved now (at most [MAX_MISROUTES] times a minute; then the
     * measurement stops with [MicChoice.ROUTE_MESSAGE] rather than measuring with the wrong one).
     */
    private suspend fun reopenAfterMisroute(gen: Int) {
        val now = System.currentTimeMillis()
        val again = synchronized(misroutes) {
            while (misroutes.isNotEmpty() && now - misroutes.first() > 60_000) misroutes.removeFirst()
            misroutes.addLast(now)
            misroutes.size <= MAX_MISROUTES
        }
        if (again) {
            switchMicrophone(container.microphones.resolveNow(), expectedGeneration = gen)
        } else {
            synchronized(this) {
                if (stopping || gen != generation) return
                container.live.update { it.copy(error = MicChoice.ROUTE_MESSAGE) }
                stopMeasurement()
            }
        }
    }

    /**
     * Closes the classifier on its own inference thread, i.e. strictly after any inference that
     * is still running (closing a LiteRT interpreter during run() is a native use-after-free).
     */
    private fun releaseClassifier() {
        val exec = inference
        val cls = classifier
        inference = null
        classifier = null
        val close = Runnable { try { cls?.close() } catch (_: Exception) {} }
        if (exec != null) {
            try { exec.execute(close) } catch (_: RejectedExecutionException) { close.run() }
            exec.shutdown()
        } else {
            close.run()
        }
    }

    /** Runs on the capture thread for every 125 ms block. */
    private fun onBlock(samples: FloatArray, count: Int) {
        val eng = engine ?: return
        // Deliver finished classifications first, so an event ending in this block can use them.
        drainFrames(eng)
        eng.process(samples, count)
        tap.onBlock(samples, count, Acoustics.SAMPLE_RATE, System.currentTimeMillis())
        val cls = classifier ?: return
        val exec = inference ?: return
        if (eng.classifierDue() && inferenceBusy.compareAndSet(false, true)) {
            val end = eng.copyClassifierWindow(classifierWindow)
            if (end < 0) { inferenceBusy.set(false); return }
            val window = classifierWindow
            val normalize = normalizeClassifierInput
            val gen = generation
            exec.execute {
                try {
                    val gainDb = if (normalize) ClassifierPreprocessor.normalize(window) else 0.0
                    val scores = cls.classify(window)
                    frames.add(gen to ClassifierFrame(end, eng.classifierWindowLength48k, scores, gainDb))
                } catch (_: Exception) {
                    // A failed inference only loses one classification frame.
                } finally {
                    window.fill(0f)
                    inferenceBusy.set(false)
                }
            }
        }
    }

    /** [micKey]: the microphone of this engine (MicChoice.key); every minute and event it stores carries it. */
    private inner class EngineListener(private val micKey: String) : MeasurementEngine.Listener {
        override fun onTick(tick: LafTick) {
            container.live.update {
                it.copy(
                    lafDb = if (tick.valid) tick.lafDb else null, invalidAudio = !tick.valid,
                    lafMaxTickDb = if (tick.valid) tick.lafMaxDb else null, leqTickDb = if (tick.valid) tick.leqDb else null,
                    tickEpochMs = tick.epochMs,
                )
            }
        }

        override fun onSecond(second: SecondResult) {
            val l = container.live
            l.update {
                it.copy(
                    laeq60sDb = second.laeqRunning60sDb.takeUnless { v -> v.isNaN() },
                    backgroundDb = second.backgroundDb.takeUnless { v -> v.isNaN() },
                    localFloorDb = second.localFloorDb.takeUnless { v -> v.isNaN() },
                )
            }
            val now = System.currentTimeMillis()
            synchronized(cadence) {
                if (cadence.levelDue(now)) {
                    // 0.7.6: the first post after a «Sehr laut» alert may come in report mode (Online-Version
                    // «unterwegs»): then the tap's fixed text, else the level text as always.
                    val text = currentTap?.text ?: MeasurementNotification.text(
                        laeqDb = second.laeqRunning60sDb, calibrated = l.value.calibrated,
                        silenced = l.value.micSilenced || second.validFraction == 0.0, note = tap.notificationNote,
                    )
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, currentTap))
                }
            }
        }

        override fun onMinute(minute: MinuteRecord) {
            val l = container.live
            // A minute held for a long event can arrive after a later one: show the newest.
            l.update { if ((it.lastMinute?.startEpochMs ?: Long.MIN_VALUE) <= minute.startEpochMs) it.copy(lastMinute = minute) else it }
            persist("minute") { container.measurements.insert(minute, micKey) }
        }

        override fun onEventEmitted(event: NoiseEvent, startSample: Long, endSample: Long) {
            val t = tap
            container.live.update {
                it.copy(
                    lastEvent = LiveEvent(
                        startEpochMs = event.startEpochMs,
                        endEpochMs = event.startEpochMs + (event.durationSeconds * 1000).toLong(),
                        lafMaxDb = event.lafMaxDb, category = event.dominantCategory, wind = event.wind,
                        calibrated = event.calibrated, thresholdDb = event.thresholdDb, minLevelDb = event.minLevelDb,
                    ),
                )
            }
            persist("event") {
                val id = container.measurements.insert(event, micKey)
                t.onEventStored(id, event, startSample, endSample)
            }
        }

        override fun onClockCorrection(correctionMs: Long) {
            Log.i(TAG, "sample clock re-anchored by $correctionMs ms")
            tap.onClockCorrection(correctionMs)
        }

        override fun onClockAnchored(anchorSample: Long, anchorEpochMs: Long) = tap.onClockAnchor(anchorSample, anchorEpochMs)
        override fun onEventCandidate(startSample: Long) = tap.onEventStarted(startSample)
        override fun onEventConfirmed(startSample: Long) = tap.onEventConfirmed(startSample)
        override fun onEventDiscarded(startSample: Long) = tap.onEventDiscarded(startSample)
        override fun onEventClosed(startSample: Long, endSample: Long, features: EventFeatures) =
            tap.onEventEnded(startSample, endSample, features)
        override fun onClassifierResult(result: ClassifierResult) = tap.onClassifierResult(result.endEpochMs, result)

        override fun onClassification(endEpochMs: Long, decision: CategoryDecision, top: List<LabelScore>) {
            container.live.update {
                it.copy(dominantCategory = decision.dominant, dominantScore = decision.dominantScore, topLabels = top)
            }
        }
    }

    @Synchronized
    private fun stopMeasurement() {
        if (stopping) return
        stopping = true
        micWatch?.cancel()
        micWatch = null
        capture?.stop()
        capture = null
        motion?.stop()
        motion = null
        tapWatch?.cancel()
        tapWatch = null
        synchronized(cadence) {
            cadence.modeChanged(false)
            cadence.tapChanged(false, false)
            cadence.endAlert()
            currentTap = null
        }
        // The capture thread has ended: finish the engine (partial minute, open event) here.
        engine?.let { eng ->
            drainFrames(eng)
            eng.stop()
        }
        engine = null
        mic = null
        synchronized(misroutes) { misroutes.clear() }
        tap.onSessionStop()
        tap = NoAudioTap
        releaseClassifier()
        frames.clear()
        classifierWindow = FloatArray(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
        releaseWakeLock()
        val err = container.live.value.error
        container.live.value = LiveState(error = err, lastMinute = container.live.value.lastMinute)
        // Close this measurement's row once its insert has finished. A new «Start» gets its own row.
        val endAt = System.currentTimeMillis()
        val row = measurementRow
        measurementRow = null
        if (row != null) persist("measurement end") { row.await()?.let { container.measurements.endMeasurement(it, endAt) } }
        // engine.stop() has just emitted the partial minute and the open event. Stay in the
        // foreground until they are in the database (at most 3 s): after the task was swiped away
        // the process may be killed as soon as the service is gone.
        val pending = synchronized(dbJobs) { dbJobs.toList() }
        scope.launch {
            withTimeoutOrNull(3_000) { pending.joinAll() }
            // The measurement's minutes are stored: re-estimate the noise floor (0.6.5), once.
            container.noiseFloor.refresh()
            withContext(Dispatchers.Main) {
                synchronized(this@MeasurementService) {
                    // A new measurement may have been started in the meantime: leave it alone.
                    if (!stopping) return@withContext
                }
                ServiceCompat.stopForeground(this@MeasurementService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * The app's task was swiped away. By default the measurement continues (foreground service,
     * notification with «Stopp»); with «Messung beenden, wenn die App geschlossen wird» it stops
     * exactly like «Stopp», including the partial minute.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val live = container.live.value
        val action = TaskRemovedPolicy.onTaskRemoved(
            stopOnTaskRemoved = container.settings.state.value.stopOnTaskRemoved,
            running = live.running, starting = live.starting,
        )
        if (action == TaskRemovedPolicy.Action.STOP) stopMeasurement()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Promotes the service to the foreground. On API 34+ the "microphone" type requires the
     * RECORD_AUDIO permission at this moment; if it is missing (revoked after the UI checked) or
     * the type is refused, the service is promoted as a "shortService" instead (no permission
     * needed) so that it can stop cleanly instead of crashing the app.
     */
    private enum class Promotion { MICROPHONE, SHORT_SERVICE, FAILED }

    private fun promoteToForeground(hasMic: Boolean, wantPosition: Boolean): Promotion {
        val shortType = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        val micType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val first = if (Build.VERSION.SDK_INT >= 34 && !hasMic) shortType else micType
        val text = if (hasMic) MeasurementNotification.STARTING else MeasurementNotification.NO_PERMISSION
        // 0.6.9 (Labor·Karte only, «Messen» runs; null in every other edition and whenever location
        // is not granted): also the type «location», so the position can be read with the screen off.
        // A measurement that is already running keeps the types it was started with.
        val busy = container.live.value.running || container.live.value.starting
        val positionType = when {
            first != micType -> null
            busy -> if (positionAllowed) MotionWatch.positionForegroundType(this) else null
            wantPosition -> MotionWatch.positionForegroundType(this)
            else -> null
        }
        if (positionType != null) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), first or positionType)
                if (!busy) positionAllowed = true
                return Promotion.MICROPHONE
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with the second type failed: ${e.javaClass.simpleName}")
            }
        }
        if (!busy) positionAllowed = false
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), first)
            return if (first == micType) Promotion.MICROPHONE else Promotion.SHORT_SERVICE
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
        }
        if (Build.VERSION.SDK_INT >= 34 && first != shortType) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), shortType)
                // The caller stops right after: this promotion only exists to avoid a crash.
                return Promotion.SHORT_SERVICE
            } catch (e: Exception) {
                Log.w(TAG, "startForeground(shortService) failed: ${e.javaClass.simpleName}")
            }
        }
        return Promotion.FAILED
    }

    /** Short-service timeout (API 34+); only reached if the fallback promotion was used. */
    override fun onTimeout(startId: Int) {
        stopMeasurement()
    }

    override fun onDestroy() {
        if (!stopping) stopMeasurement()
        // Let pending DB inserts finish; the scope is cancelled after a grace period.
        scope.launch { kotlinx.coroutines.delay(5000); scope.cancel() }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LessNoise::Measurement").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // safety timeout; released when the measurement stops
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(LEGACY_CHANNEL) != null) nm.deleteNotificationChannel(LEGACY_CHANNEL)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // DEFAULT: visible in the status bar. No sound or vibration (the notification is
            // updated every 2 s and must never make a noise during a measurement).
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = getString(R.string.notif_channel_desc)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    /**
     * 0.6.8 (since 0.8.4 Labor·Karte only; LoudAlerts.reportTap is null in every other edition): while the run is
     * «unterwegs» — started with «Unterwegs», or LiveState.moving in a «Messen» run — the notification's
     * tap opens «Laut! Melden» and its text is «Antippen zum Melden». Re-posted only when that state
     * changes; back to the level text and the usual tap when it ends.
     * 0.6.9 (Labor·Karte): a «Melden» button during the whole run and the level text in every state;
     * re-posted when the tap's target changes ([NotificationCadence.tapChanged]).
     */
    private fun watchNotificationTap() {
        tapWatch?.cancel()
        tapWatch = scope.launch {
            container.live.map { it.running && (it.unterwegs || it.moving) }.distinctUntilChanged().collect {
                val l = container.live.value
                val reportTap = LoudAlerts.reportTap(this@MeasurementService, l.running, l.unterwegs, l.moving)
                synchronized(this@MeasurementService) { if (stopping) return@collect }
                // Under the cadence's lock, so a level post of the capture thread cannot overtake this one.
                synchronized(cadence) {
                    currentTap = reportTap
                    val modeChanged = cadence.modeChanged(reportTap?.text != null)
                    val tapChanged = cadence.tapChanged(reportTap?.intent != null, reportTap?.button != null)
                    if (!modeChanged && !tapChanged) return@collect
                    // 0.7.6: a «Sehr laut» alert is in the notification: the change is applied by the
                    // first post after its window (NotificationCadence.levelDue), not over the alert.
                    if (cadence.alertOpen(System.currentTimeMillis())) return@collect
                    val text = reportTap?.text ?: l.laeq60sDb?.let {
                        MeasurementNotification.text(laeqDb = it, calibrated = l.calibrated, silenced = l.micSilenced, note = tap.notificationNote)
                    } ?: MeasurementNotification.STARTING
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, reportTap))
                }
            }
        }
    }

    /**
     * 0.7.6 (since 0.8.4 Labor·Karte only, the only sender): ACTION_LOUD_ALERT. While a measurement runs, the notification
     * (id [NOTIFICATION_ID], the foreground service's own) is posted ONCE with the alert: on the
     * alert's channel (importance HIGH, so this one post makes the heads-up with sound and
     * vibration), its title, «Antippen zum Melden», the tap as content intent and as a «Melden»
     * action next to «Stopp». For [LOUD_ALERT_WINDOW_MS] nothing overwrites it (NotificationCadence);
     * then the next level post restores the normal notification on the silent channel.
     */
    private fun showLoudAlert(intent: Intent, startId: Int) {
        val title = intent.getStringExtra(EXTRA_ALERT_TITLE)
        val channel = intent.getStringExtra(EXTRA_ALERT_CHANNEL)
        val tapIntent = IntentCompat.getParcelableExtra(intent, EXTRA_ALERT_TAP, PendingIntent::class.java)
        val live = container.live.value
        synchronized(this) {
            if (!live.running || stopping) {
                // A service started only by this intent (no measurement at all) must not stay started;
                // one that is stopping ends itself (stopMeasurement).
                if (!live.running && !live.starting && !stopping) stopSelf(startId)
                return
            }
        }
        if (title == null || channel == null || tapIntent == null) return
        // A channel the owner switched off (or that does not exist) would drop the post: then the
        // measurement's notification stays as it is, and no window holds back its level text.
        val nm = getSystemService(NotificationManager::class.java)
        val ch = nm.getNotificationChannel(channel)
        if (ch == null || ch.importance == NotificationManager.IMPORTANCE_NONE) return
        val now = System.currentTimeMillis()
        synchronized(cadence) {
            if (!cadence.alertUntil(now, now + LOUD_ALERT_WINDOW_MS)) return
            nm.notify(NOTIFICATION_ID, alertNotification(channel, title, tapIntent, now))
        }
    }

    private fun alertNotification(channel: String, title: String, tapIntent: PendingIntent, whenMs: Long): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeasurementService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_level)
            .setContentTitle(title)
            .setContentText(MeasurementNotification.ALERT_TEXT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setWhen(whenMs)
            .setShowWhen(true)
            .setOngoing(true)
            // This one post must alert (heads-up, sound, vibration from the HIGH channel): not silent,
            // not only-once; the restoring post afterwards is silent again (notification()).
            .setOnlyAlertOnce(false)
            .setContentIntent(tapIntent)
            .addAction(0, "Stopp", stop)
            .addAction(0, MeasurementNotification.ALERT_BUTTON, tapIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notification(text: String, reportTap: NotificationTap? = null): Notification {
        val open = reportTap?.intent ?: PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeasurementService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_level)
            .setContentTitle(MeasurementNotification.TITLE)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stopp", stop)
            .apply { reportTap?.button?.let { addAction(0, reportTap.buttonLabel, it) } }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
