package ch.lessnoise.app.labor

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// LABOR BUILD ONLY (app/src/labor/). See PRIVACY.md → «Labor-Build».

/**
 * The Labor recording settings (SharedPreferences «labor»). 0.9.4.6 (owner: «like the production version, plus
 * the one thing you can turn on where the audio gets saved»): the UI has ONE switch, «Audio speichern» =
 * [recordAudio] (the same key `record_audio` as 0.9.4.5's «Audio während der Messung aufzeichnen», so an updated
 * phone keeps recording exactly as before). [clips], [continuous], [clipEvery] and [maxGb] are no longer shown;
 * the recorder keeps using whatever is stored — a new install: clips on, continuous on, every clip, 5 GB.
 */
data class LaborSettings(
    /** «Audio speichern» (until 0.9.4.5 «Audio während der Messung aufzeichnen»; off by default). */
    val recordAudio: Boolean = false,
    /** Event clips (±5 s, WAV 16 kHz); until 0.9.4.5 its own switch. */
    val clips: Boolean = true,
    /** Continuous AAC 64 kbit/s in hour files; until 0.9.4.5 its own switch. */
    val continuous: Boolean = true,
    /** Every n-th event gets a clip (1, 2, 5); until 0.9.4.5 «Clip-Rate». */
    val clipEvery: Int = 1,
    /** The audio budget in GB; until 0.9.4.5 «Maximaler Speicher». */
    val maxGb: Double = StorageBudget.DEFAULT_GB,
) {
    val maxBytes: Long get() = (maxGb * StorageBudget.GB).toLong()
}

/**
 * 0.9.4.6: what the Labor build shows while it stores audio — one small line on the running «Messen»
 * (EditionUi.RecordingNote) and the same words after the measurement notification's text
 * (LaborRecorder.notificationNote → MeasurementNotification.text: «Letzte Minute: 54,2 dB(A) · Audio wird gespeichert»).
 */
object RecordingNote {
    const val TEXT = "Audio wird gespeichert"

    /**
     * The note while the recorder is [active] (not stopped, «Audio speichern» not switched off since: [recordAudio]),
     * its audio budget not used up ([audioOn]) and it records clips or hour files; null otherwise (then only the
     * classifier log and the manifest are written, no audio).
     */
    fun text(active: Boolean, audioOn: Boolean, recordAudio: Boolean, clips: Boolean, continuous: Boolean): String? =
        if (active && audioOn && recordAudio && (clips || continuous)) TEXT else null
}

/** 0.9.4.6: the texts of the «Labor» card in «Einstellungen» (EditionUi.SettingsSection). Pure, tested on the JVM. */
object LaborCardTexts {
    const val TITLE = "Labor"
    const val SWITCH = "Audio speichern"
    const val SWITCH_SUPPORTING = "Nur auf diesem Telefon, nie gesendet."
    /** Under the switch while a run goes that started without it: the recorder starts with the next run. */
    const val NEXT_RUN = "Gilt ab der nächsten Messung."
    const val CONFIRM_TITLE = "Audio speichern?"
    const val CONFIRM_TEXT = "Speichert Audio auf diesem Telefon, auch Stimmen in der Nähe. Es bleibt dort, bis du es löschst."
    const val CONFIRM_BUTTON = "Speichern"
    const val CLIPS = "Clips anhören"
    const val EXPORT_FOLDER = "In Ordner exportieren"
    const val EXPORT_ZIP = "Als ZIP teilen"
    const val PICK_FOLDER = "Ordner wählen"
    const val DELETE_EXPORTED = "Exportierte Aufnahmen löschen"
    const val DELETE_ALL = "Alle Aufnahmen löschen"

    /** «1,2 GB von 5 GB belegt», «350 MB von 5 GB belegt» (GB with one decimal from 1 GB on). */
    fun used(usedBytes: Long, maxBytes: Long): String {
        val u = if (usedBytes >= StorageBudget.GB) {
            String.format(java.util.Locale.GERMANY, "%.1f GB", usedBytes / StorageBudget.GB.toDouble())
        } else {
            "${(usedBytes + 500_000L) / 1_000_000L} MB"
        }
        return "$u von ${StoragePolicy.gbLabel(maxBytes)} belegt"
    }

    /**
     * Whether the card shows more than the switch: while «Audio speichern» is on, or while recordings are on the
     * phone — clips, hour files or classifier logs (the logs are written only by the recorder) — so that the
     * export and the two deletes stay reachable as long as there is something of them. The manifest alone does
     * not count: Labor·Karte writes its `motion`, `motion_minute` and `report` lines also with the switch off.
     */
    fun showsFiles(recordAudio: Boolean, clips: Int, hourFiles: Int, classifierFiles: Int): Boolean =
        recordAudio || clips > 0 || hourFiles > 0 || classifierFiles > 0

    /**
     * «Audio speichern» switched on (after the confirmation): [LaborSettings.recordAudio] on, the other stored values
     * kept — except that a 0.9.4.5 setting with neither clips nor hour files (which recorded no audio at all) gets
     * both back, since the one switch now says «Audio speichern» and its sub-switches are gone.
     */
    fun switchedOn(s: LaborSettings): LaborSettings =
        if (s.clips || s.continuous) s.copy(recordAudio = true) else s.copy(recordAudio = true, clips = true, continuous = true)
}

/** What the recorder is doing right now (for the banner and the settings section). */
data class RecorderStatus(
    val recording: Boolean = false,
    val clips: Boolean = false,
    val continuous: Boolean = false,
    /** Recording stopped because «Maximaler Speicher» was reached (measuring goes on). */
    val storageFull: Boolean = false,
    val clipsThisSession: Long = 0,
    val droppedThisSession: Long = 0,
)

/** Labor singletons (settings, status, files). */
object Labor {
    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(LaborSettings())
    val settings: StateFlow<LaborSettings> = _settings
    val status = MutableStateFlow(RecorderStatus())
    /** Session id of the newest recorder (older ones may still be finishing their files). */
    @Volatile var currentSession: String? = null
    /** The hour file being written right now (`continuous/<name>`), or null; the folder export leaves it out. */
    @Volatile var openContinuousFile: String? = null
    /** Event ids that have a clip on disk (for the marker in «Letzte Ereignisse»). */
    val clipEventIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Bumped whenever files were written or deleted (refreshes the storage figures). */
    val filesVersion = MutableStateFlow(0L)

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("labor", Context.MODE_PRIVATE)
        prefs = p
        _settings.value = LaborSettings(
            recordAudio = p.getBoolean("record_audio", false),
            clips = p.getBoolean("clips", true),
            continuous = p.getBoolean("continuous", true),
            clipEvery = p.getInt("clip_every", 1).takeIf { it in ClipRateSampler.CHOICES } ?: 1,
            maxGb = p.getFloat("max_gb", StorageBudget.DEFAULT_GB.toFloat()).toDouble(),
        )
        Thread({ refreshClipIds(dirs(context)) }, "stadtlaerm-labor-scan").start()
    }

    fun update(context: Context, transform: (LaborSettings) -> LaborSettings) {
        init(context)
        val n = transform(_settings.value)
        prefs!!.edit()
            .putBoolean("record_audio", n.recordAudio)
            .putBoolean("clips", n.clips)
            .putBoolean("continuous", n.continuous)
            .putInt("clip_every", n.clipEvery)
            .putFloat("max_gb", n.maxGb.toFloat())
            .apply()
        _settings.value = n
    }

    fun dirs(context: Context): LaborDirs {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "external-fallback")
        return LaborDirs(File(base, "audio"))
    }

    private val CLIP_ID = Regex("""^ev_(\d+)_\d{8}_\d{6}(_\d+)?\.wav$""")

    fun refreshClipIds(d: LaborDirs) {
        clipEventIds.value = d.clips.listFiles()?.mapNotNull { CLIP_ID.find(it.name)?.groupValues?.get(1)?.toLongOrNull() }?.toSet()
            ?: emptySet()
    }

    fun clipWritten(eventId: Long?) {
        if (eventId != null) clipEventIds.update { it + eventId }
        filesVersion.update { it + 1 }
    }
}

/**
 * Where the Labor build puts its recordings: getExternalFilesDir(null)/audio/
 * {clips/, continuous/, classifier/, manifest.jsonl}.
 */
class LaborDirs(val root: File) {
    val clips = File(root, "clips")
    val continuous = File(root, "continuous")
    val classifier = File(root, "classifier")
    val manifest = File(root, "manifest.jsonl")

    fun ensure() { clips.mkdirs(); continuous.mkdirs(); classifier.mkdirs() }

    data class Stats(
        val usedBytes: Long,
        val clipCount: Int,
        val clipBytes: Long,
        val hourFiles: Int,
        val continuousBytes: Long,
        val manifestBytes: Long,
        val classifierFiles: Int = 0,
        val classifierBytes: Long = 0,
    ) {
        /** What «Als ZIP teilen» packs besides the hour files. */
        val textAndClipBytes: Long get() = clipBytes + manifestBytes + classifierBytes
    }

    private fun files(dir: File, ext: String): List<File> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(ext) } ?: emptyList()

    fun stats(): Stats {
        val c = files(clips, ".wav")
        val h = files(continuous, ".m4a")
        val k = files(classifier, ".jsonl")
        return Stats(
            usedBytes = usedBytes(),
            clipCount = c.size, clipBytes = c.sumOf { it.length() },
            hourFiles = h.size, continuousBytes = h.sumOf { it.length() },
            manifestBytes = if (manifest.isFile) manifest.length() else 0,
            classifierFiles = k.size, classifierBytes = k.sumOf { it.length() },
        )
    }

    fun usedBytes(): Long = if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** Deletes every recording and the manifest. */
    fun deleteAll() {
        if (root.exists()) root.walkBottomUp().forEach { if (it != root) it.delete() }
    }

    /**
     * Zips the manifest, the clips and the classifier logs (and the hourly files if
     * [withContinuous]) into [zip]. Entries keep their folder (clips/…, classifier/…, continuous/…).
     */
    fun zipTo(zip: File, withContinuous: Boolean) {
        zip.parentFile?.mkdirs()
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            fun add(f: File, name: String) {
                z.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            if (manifest.isFile) add(manifest, "manifest.jsonl")
            files(clips, ".wav").sortedBy { it.name }.forEach { add(it, "clips/${it.name}") }
            files(classifier, ".jsonl").sortedBy { it.name }.forEach { add(it, "classifier/${it.name}") }
            if (withContinuous) files(continuous, ".m4a").sortedBy { it.name }.forEach { add(it, "continuous/${it.name}") }
        }
    }

    companion object {
        /** Hourly files go into the ZIP only if they total less than this. */
        const val ZIP_CONTINUOUS_LIMIT = 500L * 1_000_000L
    }
}
