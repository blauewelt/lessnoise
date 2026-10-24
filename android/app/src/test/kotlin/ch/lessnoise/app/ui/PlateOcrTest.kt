package ch.lessnoise.app.ui

import ch.lessnoise.app.upload.ReportPlate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** 0.8.2: the plate picked from the lines a text recognition read on the photo (PlateOcr). */
class PlateOcrTest {
    @Test
    fun swissPlatesWithAnySeparator() {
        assertEquals("ZH 123456", PlateOcr.bestPlate(listOf("ZH 123456")))
        assertEquals("ZH 123456", PlateOcr.bestPlate(listOf("ZH·123 456")), "the shield read as a middle dot, digits grouped")
        assertEquals("BE 12345", PlateOcr.bestPlate(listOf("be-12345")))
        assertEquals("SG 4711", PlateOcr.bestPlate(listOf("SG.4711")))
        assertEquals("TI 3", PlateOcr.bestPlate(listOf("TI3")))
        assertEquals("VS 98765", PlateOcr.bestPlate(listOf("VS • 98.765")))
        assertEquals(26, PlateOcr.CANTONS.size)
        for (c in PlateOcr.CANTONS) assertEquals("$c 42", PlateOcr.bestPlate(listOf("$c 42")), c)
    }

    @Test
    fun theSwissPlateAmongOtherText() {
        assertEquals("AG 1234", PlateOcr.bestPlate(listOf("SCHWEIZ", "CH", "AG 1234")))
        assertEquals("ZH 1234", PlateOcr.bestPlate(listOf("TAXI ZH 1234")))
        // Preferred over a line that looks like a foreign plate, and the longer of two Swiss ones wins.
        assertEquals("ZH 99", PlateOcr.bestPlate(listOf("M-AB 1234", "ZH 99")))
        assertEquals("LU 123456", PlateOcr.bestPlate(listOf("LU 12", "LU 123456")))
        // 7 digits are no Swiss plate; the valid one is taken.
        assertEquals("BE 12", PlateOcr.bestPlate(listOf("ZH 1234567", "BE 12")))
        // A canton code inside a word is not one.
        assertNull(PlateOcr.bestPlate(listOf("GARAGE 2024")))
    }

    @Test
    fun liechtensteinAfterTheCantons() {
        assertEquals("FL 1234", PlateOcr.bestPlate(listOf("FL 1234")))
        assertEquals("TI 3", PlateOcr.bestPlate(listOf("FL 12", "TI 3")))
        assertEquals("FL 12", PlateOcr.bestPlate(listOf("M-AB 1234", "FL 12")))
    }

    @Test
    fun foreignPlatesAsAFallback() {
        assertEquals("M-AB 1234", PlateOcr.bestPlate(listOf("m-ab 1234")))
        assertEquals("W-12345A", PlateOcr.bestPlate(listOf("W-12345A")))
        assertEquals("XY 123", PlateOcr.bestPlate(listOf("XY 123")), "not a canton: a foreign plate")
        assertEquals("AB 123 CD", PlateOcr.bestPlate(listOf("AB-123-CD".replace('-', ' '))))
        // Words, bare numbers, too short or too long lines are not plates.
        assertNull(PlateOcr.bestPlate(listOf("AUDI", "WIEN", "1234", "A1", "MERCEDES-BENZ 2024")))
        assertNull(PlateOcr.bestPlate(emptyList()))
        assertNull(PlateOcr.bestPlate(listOf("", "   ")))
        // Stray marks are dropped.
        assertEquals("M-AB 1234", PlateOcr.bestPlate(listOf("M·AB 1234".replace('·', '-'))))
    }

    @Test
    fun theResultFitsTheField() {
        for (lines in listOf(listOf("ZH 123456"), listOf("FL 123456"), listOf("ABCDE12345"))) {
            val p = PlateOcr.bestPlate(lines)!!
            assertTrue(p.length <= ReportPlate.MAX_LENGTH, p)
            assertEquals(p, ReportPlate.normalize(p), "already in the field's form: $p")
        }
    }

    @Test
    fun whatAPhotoGivesTheField() {
        assertEquals(PlatePhoto.Cancelled, plateFromPhoto(taken = false, lines = listOf("ZH 1")))
        assertEquals(PlatePhoto.Found("ZH 1"), plateFromPhoto(taken = true, lines = listOf("ZH 1")))
        assertEquals(PlatePhoto.Note("Kein Kontrollschild erkannt"), plateFromPhoto(taken = true, lines = listOf("AUDI")))
        assertEquals(PlatePhoto.Note("Kein Kontrollschild erkannt"), plateFromPhoto(taken = true, lines = emptyList()))
        // This build has no text recognition: the field stays empty, the line says why.
        assertFalse(PlateCamera.OCR)
        assertEquals(PlatePhoto.Note(PlateCamera.NO_OCR), plateFromPhoto(taken = true, lines = null))
    }

    /** The photo is the camera app's: no CAMERA permission anywhere; the file lives in cache/plate/ and is deleted at once. */
    @Test
    fun noCameraPermissionAndThePhotoIsDeleted() {
        val app = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
            .firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/main").isDirectory } ?: fail("app module not found")
        val manifests = File(app, "src").listFiles().orEmpty().map { File(it, "AndroidManifest.xml") }.filter { it.isFile }
        assertEquals(5, manifests.size, manifests.toString())
        for (m in manifests) {
            val xml = m.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            assertFalse(xml.contains("android.permission.CAMERA"), "$m declares CAMERA")
            assertFalse(xml.contains("android.hardware.camera"), "$m declares a camera feature")
        }
        // ML Kit (and with it Google Play services and Firebase's logging transport) is not in the build.
        val gradle = File(app, "build.gradle.kts").readText()
        for (bad in listOf("com.google.mlkit", "play-services", "firebase")) assertFalse(gradle.contains(bad), bad)
        // The FileProvider path for the photo is cache/plate/, in both file_paths.xml.
        for (f in listOf("src/main/res/xml/file_paths.xml", "src/labor/res/xml/file_paths.xml")) {
            assertTrue(File(app, f).readText().contains("""<cache-path name="plate" path="plate/" />"""), f)
        }
        val cam = File(app, "src/main/kotlin/ch/lessnoise/app/ui/PlateCamera.kt").readText()
        assertTrue(cam.contains("ActivityResultContracts.TakePicture()"))
        assertTrue(cam.contains("} finally {\n            file?.delete()"), "deleted in finally")
        assertTrue(cam.contains("File(context.cacheDir, DIR)") && cam.contains("const val DIR = \"plate\""))
        for (bad in listOf("Bitmap", "BitmapFactory", "MediaStore.Images", "Environment", "openFileOutput", "copyTo(", "renameTo(")) {
            assertFalse(cam.contains(bad), bad)
        }
        // The field's trailing camera button.
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        assertTrue(ui.contains("IconButton(onClick = { plateCamera() }, enabled = !waiting)"))
        assertEquals("Kein Kontrollschild erkannt", PlateCamera.NONE)
    }
}
