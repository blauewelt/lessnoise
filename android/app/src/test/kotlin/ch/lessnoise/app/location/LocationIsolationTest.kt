package ch.lessnoise.app.location

import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.upload.KotlinSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The location permission (v0.5.1) exists only where «Messwerte teilen» does, never in the
 * background, and the coordinates stay inside the location code. Reads the manifests and the Kotlin
 * sources, so it runs the same in every flavour (testOfflineDebugUnitTest … testLaborKarteDebugUnitTest).
 * The built APKs are checked with `aapt2 dump permissions` (PRIVACY.md → «How to verify»).
 */
class LocationIsolationTest {

    private val appDir: File = locateAppDir()
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val background = "android.permission.ACCESS_BACKGROUND_LOCATION"

    /** `<uses-permission android:name="…" …/>` elements of a manifest: name → removed? */
    private fun permissions(flavour: String): Map<String, Boolean> {
        val f = File(appDir, "src/$flavour/AndroidManifest.xml")
        assertTrue(f.isFile, "missing $f")
        val xml = f.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<uses-permission\b[^>]*>""").findAll(xml).associate { m ->
            val name = Regex("""android:name="([^"]+)"""").find(m.value)?.groupValues?.get(1) ?: fail("no name in ${m.value}")
            name to m.value.contains("""tools:node="remove"""")
        }
    }

    @Test
    fun locationPermissionOnlyInTheEditionsWithSharing() {
        // The editions with «Messwerte teilen» declare both (Android 12+ needs both for «Genau»/«Ungefähr»).
        for (flavour in listOf("public", "laborKarte")) {
            val p = permissions(flavour)
            assertEquals(false, p[fine], "$flavour: $p")
            assertEquals(false, p[coarse], "$flavour: $p")
        }
        // Offline and Labor remove them, like INTERNET, in case a library ever merges them in.
        for (flavour in listOf("offline", "labor")) {
            val p = permissions(flavour)
            assertEquals(true, p[fine], "$flavour: $p")
            assertEquals(true, p[coarse], "$flavour: $p")
            assertEquals(true, p["android.permission.INTERNET"], "$flavour: $p")
        }
        // The shared manifest declares no location, and removes background location for everyone.
        val main = permissions("main")
        assertTrue(fine !in main && coarse !in main, "src/main: $main")
        assertEquals(true, main[background], "src/main: $main")
        for (flavour in listOf("main", "public", "laborKarte", "offline", "labor")) {
            assertTrue(permissions(flavour)[background] != false, "$flavour declares background location")
        }
        // This variant: location exactly when the upload exists.
        val own = permissions(BuildConfig.FLAVOR)
        assertEquals(BuildConfig.UPLOAD_AVAILABLE, own[fine] == false, "${BuildConfig.FLAVOR}: $own")
    }

    /** Pure helpers the location code may use: lat/lon → LV95, and the location noise (both store nothing). */
    private val allowedLocationImports = setOf("ch.lessnoise.app.upload.CellInput", "ch.lessnoise.dsp.geo.LocationNoise")

    @Test
    fun coordinatesStayInTheLocationCode() {
        val main = File(appDir, "src/main/kotlin/ch/lessnoise/app")
        val locationFiles = kotlinFiles(File(main, "location"))
        assertEquals(listOf("LocationFetcher.kt", "LocationPolicy.kt"), locationFiles.map { it.name })
        val problems = mutableListOf<String>()

        // 1. The location code stores, logs and sends nothing.
        val forbidden = listOf(
            "logging" to Regex("""\bLog\.|\bprintln\b|\bprint\s*\(|\bSystem\.(out|err)\b|\bTimber\b|printStackTrace"""),
            "storage" to Regex("""\b(SharedPreferences|getSharedPreferences|DataStore|putString|putFloat|putLong|putDouble|Room|Dao|container)\b|\.edit\s*[({]"""),
            "files" to Regex("""\b(File|FileOutputStream|openFileOutput|writeText|filesDir|cacheDir)\b"""),
            "network / upload" to Regex("""\b(HttpURLConnection|URL|Socket|Uploader|UploadClient|Payloads|SiteSettings)\b"""),
        )
        for (f in locationFiles) {
            val src = KotlinSource.parse(f.readText())
            for ((what, re) in forbidden) {
                re.findAll(src.code).forEach { m -> problems += "location/${f.name}:${src.lineOf(m.range.first)}: $what («${m.value}»)" }
            }
            src.imports.filter { it.startsWith("ch.lessnoise.") && it !in allowedLocationImports }
                .forEach { problems += "location/${f.name}: project import $it (only $allowedLocationImports)" }
        }

        // 2. android.location is used only by LocationFetcher (and, 0.6.9, by the Labor·Karte position
        //    signal src/laborKarte/.../position/PositionWatch.kt); the upload never sees a Location.
        val all = kotlinFiles(File(appDir, "src")).filter { f -> f.path.split(File.separatorChar).none { it.startsWith("test") } }
        assertTrue(all.size > 30, "sources not found")
        val positionWatch = File(appDir, "src/laborKarte/kotlin/ch/lessnoise/app/position/PositionWatch.kt")
        for (f in all) {
            val src = KotlinSource.parse(f.readText())
            if (f.name != "LocationFetcher.kt" && f.absoluteFile != positionWatch.absoluteFile &&
                Regex("""\bandroid\.location\b|\bLocationManager\b""").containsMatchIn(src.code)
            ) {
                problems += "${f.relativeTo(appDir)}: uses android.location outside location/LocationFetcher.kt"
            }
            // 3. No log line anywhere mentions a coordinate or a location.
            Regex("""\bLog\.[a-z]+\s*\([^\n]*""").findAll(src.code).forEach { m ->
                if (Regex("""(?i)\b(lat|lon|latitude|longitude|location|loc|fix|coord\w*|standort)\b""").containsMatchIn(m.value)) {
                    problems += "${f.relativeTo(appDir)}:${src.lineOf(m.range.first)}: log line mentions a location («${m.value.trim()}»)"
                }
            }
        }
        for (f in kotlinFiles(File(main, "upload"))) {
            val src = KotlinSource.parse(f.readText())
            if (Regex("""ch\.lessnoise\.app\.location\b|\bLocationFetcher\b|\bLocationPolicy\b|\bLocation\b""").containsMatchIn(src.code)) {
                problems += "upload/${f.name}: refers to the location code"
            }
        }
        // 4. 0.6.9: the Labor·Karte position signal stores, logs and sends nothing either.
        for (f in kotlinFiles(File(appDir, "src/laborKarte/kotlin/ch/lessnoise/app/position"))) {
            val src = KotlinSource.parse(f.readText())
            for ((what, re) in forbidden) {
                re.findAll(src.code).forEach { m -> problems += "position/${f.name}:${src.lineOf(m.range.first)}: $what («${m.value}»)" }
            }
            src.imports.filter { it.startsWith("ch.lessnoise.") }.forEach { problems += "position/${f.name}: project import $it" }
        }
        if (problems.isNotEmpty()) fail("Coordinates must stay inside the location code:\n" + problems.joinToString("\n"))
    }

    private fun kotlinFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.sortedBy { it.path }.toList()

    private fun locateAppDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val candidates = generateSequence(start) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
        return candidates.firstOrNull { File(it, "src/main/kotlin/ch/lessnoise/app/location").isDirectory }
            ?: fail("app module not found from $start")
    }
}
