package ch.lessnoise.app.upload

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * «Messwerte teilen» can never send audio — also not in the internal «Weniger Lärm Labor · Karte»
 * (flavour laborKarte), which has both the Labor recorder and the upload.
 *
 * The upload reads its data through [UploadSource] (Room: minutes, events; the calibrations;
 * the site settings) and sends only the JSON built in Payloads.kt. This test checks the SOURCE so
 * that a later change cannot quietly widen that:
 *
 * 1. The upload package (app/src/main/.../upload/) uses no file API (java.io.File, streams,
 *    filesDir, cache dirs, ContentResolver, assets, MediaStore …), no audio class and nothing of
 *    the Labor package `ch.lessnoise.app.labor`; its string literals name none of the Labor
 *    recording folders or file types; every project import is on an allow-list (a new one is a
 *    deliberate change of this test, with a look at what that class reads).
 * 2. No flavour source set (src/labor, src/laborKarte, src/public, src/offline) contains classes
 *    of the upload package: every edition uploads with exactly this code.
 * 3. The Labor code (app/src/labor/) does not touch the upload or any network API.
 * 4. Network code exists in exactly one file of the app, upload/UploadClient.kt.
 *
 * It reads the source files, so it runs the same in every flavour (testOfflineDebugUnitTest …
 * testLaborKarteDebugUnitTest). Comments are ignored; string literals are checked.
 */
class UploadSourceIsolationTest {

    private val appDir: File = locateAppDir()
    private val uploadDir = File(appDir, "src/main/kotlin/ch/lessnoise/app/upload")

    /** Forbidden in the upload package's code (comments stripped). */
    private val forbiddenInUpload: List<Pair<String, Regex>> = listOf(
        "the Labor package" to Regex("""ch\.lessnoise\.app\.labor\b"""),
        "Labor / audio classes" to Regex(
            """\b(Labor|LaborDirs|LaborRecorder|LaborPlayer|LaborSettings|ClipLibrary|ClipAssembler|ClipPlayerSheet|""" +
                """AacFileWriter|EditionClips|AudioTap|AudioTapProvider|AudioRecord|MediaRecorder|MediaCodec|MediaMuxer|MediaExtractor)\b"""
        ),
        "java.io file classes" to Regex("""\bjava\.io\.(File|RandomAccess)"""),
        "File" to Regex("""\bFile\b"""),
        "file streams / readers" to Regex("""\b(FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile|FileChannel|FileDescriptor)\b"""),
        "java.nio.file" to Regex("""\bjava\.nio\.file\b|\bFiles\.|\bPaths\.|\bPath\.of\b"""),
        "openFileInput/Output, fileList" to Regex("""\b(openFileInput|openFileOutput|fileList|getFileStreamPath)\b"""),
        "app files dirs" to Regex("""[Ff]ilesDirs?\b|\bdataDir\b|\bgetDir\s*\(|\bgetDatabasePath\b"""),
        "cache dirs" to Regex("""[Cc]acheDirs?\b"""),
        "ContentResolver / input streams" to Regex("""\b(ContentResolver|contentResolver|openInputStream|openFileDescriptor|openAssetFileDescriptor|openTypedAssetFileDescriptor)\b"""),
        "assets" to Regex("""\b(AssetManager|assets)\b"""),
        "shared storage" to Regex("""\b(MediaStore|Environment|FileProvider|DocumentsContract|DocumentFile|StorageManager)\b"""),
    )

    /** String literals in the upload package must not name a Labor recording folder or file type. */
    private val recordingPathLiteral =
        Regex("""(^|/)(audio|clips|continuous|classifier|labor_share|external-fallback)(/|$)|manifest\.jsonl|\.(wav|m4a|aac|jsonl|zip)\b""")

    /** Project classes the upload package may import. */
    private val allowedProjectImports = setOf(
        "ch.lessnoise.app.BuildConfig",
        "ch.lessnoise.app.container",
        "ch.lessnoise.app.audio.AudioSourceSelector", // name of the capture source (AudioManager property), no audio
        "ch.lessnoise.app.data.AppDatabase",
        "ch.lessnoise.app.data.CalibrationRepository",
        "ch.lessnoise.app.data.EventEntity",
        "ch.lessnoise.app.data.MeasurementEntity", // start, end and shifted point of a measurement (Room row), no audio
        "ch.lessnoise.app.data.sharedPoint", // MeasurementEntity → its shifted point, pure
        "ch.lessnoise.app.data.MinuteEntity",
        "ch.lessnoise.app.data.Recalibrator",
        "ch.lessnoise.app.service.LiveState",
        "ch.lessnoise.dsp.geo.Lv95",
        "ch.lessnoise.dsp.geo.LocationNoise", // pure math of the location noise (shifted point, secret), no I/O
        "ch.lessnoise.dsp.MeasurementSplitter", // pure function: which measurement a record belongs to
    )

    /** java.io classes the upload package may import (in-memory buffers and the exception type). */
    private val allowedJavaIoImports = setOf("java.io.ByteArrayOutputStream", "java.io.IOException")

    private val networkCode = Regex(
        """\b(HttpURLConnection|HttpsURLConnection|openConnection|Socket|SSLSocket|DatagramSocket|SocketChannel|WebView|DownloadManager)\b|""" +
            """\bjava\.net\.URL\b|\bURL\s*\(|\bokhttp3?\b|\bio\.ktor\b"""
    )

    @Test
    fun uploadPackageReadsNoFilesAndNoLaborCode() {
        val files = kotlinFiles(uploadDir)
        assertTrue(files.size >= 6, "upload sources not found in $uploadDir: $files")
        assertTrue(files.any { it.name == "UploadClient.kt" } && files.any { it.name == "UploadWorker.kt" }, files.toString())
        val problems = mutableListOf<String>()
        for (f in files) {
            val src = KotlinSource.parse(f.readText())
            for ((what, re) in forbiddenInUpload) {
                re.findAll(src.code).forEach { m -> problems += "${f.name}:${src.lineOf(m.range.first)}: $what («${m.value}»)" }
            }
            for (lit in src.literals) {
                if (recordingPathLiteral.containsMatchIn(lit)) problems += "${f.name}: string literal names a recording path: \"$lit\""
            }
            for (imp in src.imports) {
                when {
                    imp.endsWith(".*") && (imp.startsWith("ch.lessnoise.") || imp.startsWith("java.") || imp.startsWith("android.")) ->
                        problems += "${f.name}: wildcard import $imp"
                    imp.startsWith("ch.lessnoise.") && imp !in allowedProjectImports ->
                        problems += "${f.name}: project import not on the allow-list: $imp"
                    imp.startsWith("java.io.") && imp !in allowedJavaIoImports ->
                        problems += "${f.name}: java.io import not on the allow-list: $imp"
                    imp.startsWith("java.nio.") -> problems += "${f.name}: java.nio import: $imp"
                }
            }
        }
        if (problems.isNotEmpty()) fail("The upload package must only read the Room measurement tables:\n" + problems.joinToString("\n"))
    }

    @Test
    fun noFlavourReplacesTheUploadCode() {
        val src = File(appDir, "src")
        val flavourDirs = src.listFiles().orEmpty().filter { it.isDirectory && it.name != "main" && !it.name.startsWith("test") }
        assertTrue(flavourDirs.any { it.name == "labor" }, "flavour source sets not found in $src")
        val offenders = flavourDirs.flatMap { kotlinFiles(it) }.filter { f ->
            Regex("""^\s*package\s+ch\.lessnoise\.app\.upload\b""", RegexOption.MULTILINE).containsMatchIn(f.readText())
        }
        assertTrue(offenders.isEmpty(), "upload classes outside src/main: $offenders")
    }

    @Test
    fun laborCodeHasNoUploadOrNetworkAccess() {
        val laborDir = File(appDir, "src/labor/kotlin")
        val files = kotlinFiles(laborDir)
        assertTrue(files.size >= 5, "Labor sources not found in $laborDir")
        val forbidden = listOf(
            "the upload package" to Regex("""ch\.lessnoise\.app\.upload\b"""),
            "upload classes" to Regex("""\b(Upload\w*|Uploader|CellInput|SiteSettings)\b|\bcontainer\.upload\b|\.upload\b"""),
            "network code" to networkCode,
            "java.net" to Regex("""\bjava\.net\."""),
        )
        val problems = mutableListOf<String>()
        for (f in files) {
            val src = KotlinSource.parse(f.readText())
            for ((what, re) in forbidden) {
                re.findAll(src.code).forEach { m -> problems += "${f.relativeTo(appDir)}:${src.lineOf(m.range.first)}: $what («${m.value}»)" }
            }
        }
        if (problems.isNotEmpty()) fail("The Labor code must not reach the upload or the network:\n" + problems.joinToString("\n"))
    }

    @Test
    fun networkCodeOnlyInUploadClient() {
        val roots = listOf(File(appDir, "src")) + listOf("dsp", "chart").map { File(appDir.parentFile, "$it/src/main") }
        val files = roots.flatMap { kotlinFiles(it) }.filter { f -> f.path.split(File.separatorChar).none { it.startsWith("test") } }
        assertTrue(files.size > 30, "sources not found under $roots")
        val hits = files.filter { f -> networkCode.containsMatchIn(KotlinSource.parse(f.readText()).code) }.map { it.relativeTo(appDir.parentFile).path }
        assertTrue(hits == listOf("app/src/main/kotlin/ch/lessnoise/app/upload/UploadClient.kt"), "network code in: $hits")
    }

    @Test
    fun commentStripperKeepsCodeAndStrings() {
        val s = KotlinSource.parse(
            "import java.io.IOException\n// File here\nval a = \"x // y\" /* File /* nested */ still */ + '\"' + \"\${f(\"audio/\")}\"\nval b = \"\"\"raw File\"\"\"\n"
        )
        assertTrue("File" !in s.code.replace("raw File", ""), s.code)
        assertTrue(s.literals.contains("x // y"), s.literals.toString())
        assertTrue(s.literals.contains("audio/"), s.literals.toString())
        assertTrue(s.literals.contains("raw File"), s.literals.toString())
        assertTrue(s.imports == listOf("java.io.IOException"), s.imports.toString())
        assertTrue(s.lineOf(s.code.indexOf("val b")) == 4)
    }

    private fun kotlinFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.sortedBy { it.path }.toList()

    private fun locateAppDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val candidates = generateSequence(start) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
        return candidates.firstOrNull { File(it, "src/main/kotlin/ch/lessnoise/app/upload").isDirectory }
            ?: fail("app module not found from $start")
    }
}

/**
 * Kotlin source with comments blanked out (newlines kept, so line numbers stay right), plus the
 * contents of its string literals and its imports. Handles nested block comments, raw strings,
 * escapes, character literals and string templates with nested strings.
 */
internal class KotlinSource private constructor(val code: String, val literals: List<String>) {
    val imports: List<String> =
        Regex("""^\s*import\s+([\w.*]+)""", RegexOption.MULTILINE).findAll(code).map { it.groupValues[1] }.toList()

    fun lineOf(index: Int): Int = code.substring(0, index).count { it == '\n' } + 1

    companion object {
        fun parse(text: String): KotlinSource = Parser(text).run()
    }

    private class Parser(private val text: String) {
        private val n = text.length
        private val out = StringBuilder(n)
        private val literals = mutableListOf<String>()
        private var i = 0

        fun run(): KotlinSource {
            scanCode(inTemplate = false)
            return KotlinSource(out.toString(), literals)
        }

        private fun blank(from: Int, to: Int) {
            for (k in from until to) out.append(if (text[k] == '\n') '\n' else ' ')
        }

        /** Code from [i] to the end, or (in a `${…}` template) to its unmatched closing brace. */
        private fun scanCode(inTemplate: Boolean) {
            var depth = 0
            while (i < n) {
                val c = text[i]
                when {
                    text.startsWith("//", i) -> {
                        val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                        blank(i, end); i = end
                    }
                    text.startsWith("/*", i) -> {
                        val from = i
                        var d = 0
                        while (i < n) {
                            if (text.startsWith("/*", i)) { d++; i += 2 }
                            else if (text.startsWith("*/", i)) { d--; i += 2; if (d == 0) break }
                            else i++
                        }
                        blank(from, i)
                    }
                    text.startsWith("\"\"\"", i) -> scanRawString()
                    c == '"' -> scanString()
                    c == '\'' -> {
                        val end = if (i + 1 < n && text[i + 1] == '\\') text.indexOf('\'', i + 3) else text.indexOf('\'', i + 2)
                        val stop = if (end < 0) n else end + 1
                        out.append(text, i, stop); i = stop
                    }
                    inTemplate && c == '{' -> { depth++; out.append(c); i++ }
                    inTemplate && c == '}' -> { if (depth == 0) return; depth--; out.append(c); i++ }
                    else -> { out.append(c); i++ }
                }
            }
        }

        private fun scanRawString() {
            val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it }
            var close = minOf(end + 3, n)
            while (close < n && text[close] == '"') close++ // a raw string may end in extra quotes
            literals += text.substring(i + 3, maxOf(i + 3, minOf(end, n)))
            out.append(text, i, close); i = close
        }

        private fun scanString() {
            out.append('"'); i++
            val lit = StringBuilder()
            while (i < n) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < n -> { out.append(text, i, i + 2); lit.append(text, i, i + 2); i += 2 }
                    c == '"' -> { out.append('"'); i++; break }
                    c == '$' && i + 1 < n && text[i + 1] == '{' -> {
                        out.append("\${"); i += 2
                        scanCode(inTemplate = true)
                        if (i < n) { out.append('}'); i++ }
                    }
                    else -> { out.append(c); lit.append(c); i++ }
                }
            }
            literals += lit.toString()
        }
    }
}
