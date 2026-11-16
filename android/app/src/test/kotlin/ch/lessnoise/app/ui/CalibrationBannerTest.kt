package ch.lessnoise.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import ch.lessnoise.app.data.CalibrationEntity
import ch.lessnoise.chart.DarkColors
import ch.lessnoise.chart.LightColors
import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.calibration.CalibrationMath
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.9.6 (owner, 2026-11-01): a very clear indicator on «Messen» whether the phone is calibrated — green «Telefon
 * kalibriert: −2,3 dB · 01.11.2026», red «Telefon nicht kalibriert – Werte sind Richtwerte (± 5 dB). Kalibrieren ›»;
 * a tap opens «Kalibrieren». Texts, status, colours, and the wiring of the tap (CalibrationBanner.kt, MeasureScreen.kt,
 * MainActivity.kt, DataAndSettingsScreens.kt, CalibrateScreen.kt, LessNoiseApp.kt).
 */
class CalibrationBannerTest {
    private val std = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
    private val zurich = ZoneId.of("Europe/Zurich")
    private fun at(d: Int, m: Int, h: Int, min: Int) = LocalDateTime.of(2026, m, d, h, min).atZone(zurich).toInstant().toEpochMilli()
    private fun plain(s: String) = s.replace(' ', ' ')

    private fun entity(method: String, offsetDb: Double, created: Long) = CalibrationEntity(
        id = 7, deviceModel = "Google Pixel 8", audioSource = "UNPROCESSED", offsetDb = offsetDb, method = method,
        createdEpochMs = created, createdIso = "", notes = "",
    )

    private val app: File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
    private fun src(path: String) = File(app, "src/main/kotlin/ch/lessnoise/app/$path").readText()

    @Test
    fun calibratedText() {
        val created = at(1, 11, 15, 45)
        // The owner's example, exactly: deviation from the standard, signed, one decimal, the calibration's date.
        assertEquals("Telefon kalibriert: −2,3 dB · 01.11.2026", plain(CalibrationBannerTexts.calibrated(CalibrationMath.offsetFromDeviation(-2.3), created, zurich)))
        assertEquals("Telefon kalibriert: +4,0 dB · 01.11.2026", plain(CalibrationBannerTexts.calibrated(std + 4.0, created, zurich)))
        assertEquals("Telefon kalibriert: 0,0 dB · 01.11.2026", plain(CalibrationBannerTexts.calibrated(std, created, zurich)))
        // The date in the phone's zone: 00:30 in Zurich on 1 November is still 31 October in UTC.
        val early = at(1, 11, 0, 30)
        assertTrue(CalibrationBannerTexts.calibrated(std, early, zurich).endsWith("01.11.2026"))
        assertTrue(CalibrationBannerTexts.calibrated(std, early, ZoneId.of("UTC")).endsWith("31.10.2026"))
        // One line on «Messen» at 360 dp (renders 0.9.6-messen): at most 41 characters for any deviation under ±100 dB.
        assertTrue(plain(CalibrationBannerTexts.calibrated(std - 12.3, created, zurich)).length <= 41)
    }

    @Test
    fun deviationSignIsTheCalibrationScreens() {
        // The same value and formatter as «Kalibrieren» (0.9.5.1, CalibrationDeviation): «−» is the minus sign U+2212,
        // «+» for positive values, no sign at 0,0 (also for −0,04, no «−0,0»), one decimal, decimal comma.
        val created = at(1, 11, 15, 45)
        for ((deviation, shown) in listOf(-2.3 to "−2,3", 12.3 to "+12,3", 0.0 to "0,0", -0.04 to "0,0", 0.06 to "+0,1", -20.07 to "−20,1", 3.0 to "+3,0")) {
            val offset = CalibrationMath.offsetFromDeviation(deviation)
            val text = plain(CalibrationBannerTexts.calibrated(offset, created, zurich))
            assertEquals("Telefon kalibriert: $shown dB · 01.11.2026", text, "deviation $deviation")
            // Exactly what the calibration screen shows for the same stored calibration.
            assertTrue(text.contains(CalibrationDeviation.ofOffset(offset)), "$text / ${CalibrationDeviation.ofOffset(offset)}")
            assertTrue(CalibrationDeviation.activeLine(true, created, CalibrationEntity.METHOD_REFERENCE, offset).contains(CalibrationDeviation.ofOffset(offset)))
        }
        assertFalse(CalibrationBannerTexts.calibrated(std - 2.3, created, zurich).contains('-'), "the minus sign, not a hyphen")
        // Never the conversion constant itself (≈ 112 dB), as on the calibration screen since 0.9.5.1.
        assertFalse(CalibrationBannerTexts.calibrated(std + 20.1, created, zurich).contains("132"))
    }

    @Test
    fun notCalibratedText() {
        assertEquals("Telefon nicht kalibriert – Werte sind Richtwerte (± 5 dB). Kalibrieren ›", plain(CalibrationBannerTexts.text(CalibrationStatus.NotCalibrated, pointer = true)))
        // At the top of «Kalibrieren» itself: no pointer to itself.
        assertEquals("Telefon nicht kalibriert – Werte sind Richtwerte (± 5 dB).", plain(CalibrationBannerTexts.text(CalibrationStatus.NotCalibrated, pointer = false)))
        // No-break spaces keep the units and the pointer together when the line wraps.
        assertTrue(CalibrationBannerTexts.NOT_CALIBRATED.contains("± 5 dB"))
        assertEquals("Kalibrieren ›", CalibrationBannerTexts.POINTER)
        assertTrue(CalibrationBannerTexts.calibrated(std - 2.3, 0L, zurich).contains("−2,3 dB"))
        // The same tolerance as «Datenschutz» in the app and the Anleitung (± 5 dB).
        assertTrue(src("ui/DataAndSettingsScreens.kt").contains("Ohne Kalibrierung sind die Werte Richtwerte (± 5 dB)."))
        // Spoken without «›»; the tap's label says where it goes.
        assertEquals(plain(CalibrationBannerTexts.NOT_CALIBRATED), plain(CalibrationBannerTexts.spoken(CalibrationStatus.NotCalibrated)))
        assertEquals("Kalibrieren öffnen", CalibrationBannerTexts.OPEN_LABEL)
        // Swiss spelling, «du» form, no banned words (AppJvmTest.uiTextsUsePlainWords also reads this file).
        for (t in listOf(CalibrationBannerTexts.NOT_CALIBRATED, CalibrationBannerTexts.POINTER, CalibrationBannerTexts.calibrated(std, 0L, zurich))) {
            assertFalse(t.contains('ß') || Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t) || t.contains("unkalibriert"), t)
        }
        // Calibrated: no pointer text in the line (a chevron icon follows it on «Messen»).
        val cal = CalibrationStatus.Calibrated(std - 2.3, at(1, 11, 15, 45))
        assertEquals(CalibrationBannerTexts.text(cal, pointer = true, zurich), CalibrationBannerTexts.text(cal, pointer = false, zurich))
    }

    @Test
    fun statusFollowsTheActiveCalibration() {
        val created = at(1, 11, 15, 45)
        // Never calibrated (no row for this microphone) and «Zurücksetzen» (reset_default): not calibrated.
        assertEquals(CalibrationStatus.NotCalibrated, CalibrationStatus.of(null))
        assertEquals(CalibrationStatus.NotCalibrated, CalibrationStatus.of(entity(CalibrationEntity.METHOD_RESET, std, created)))
        // Every real method: calibrated, with that calibration's offset and date.
        for (m in listOf(CalibrationEntity.METHOD_REFERENCE, CalibrationEntity.METHOD_CALIBRATOR, CalibrationEntity.METHOD_MANUAL)) {
            assertEquals(CalibrationStatus.Calibrated(std - 2.3, created), CalibrationStatus.of(entity(m, std - 2.3, created)), m)
        }
        // A manual calibration at exactly the standard value is still a calibration (the person said so).
        assertEquals(CalibrationStatus.Calibrated(std, created), CalibrationStatus.of(entity(CalibrationEntity.METHOD_MANUAL, std, created)))
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun greenAndRedReadableInLightAndDark() {
        val cal = CalibrationStatus.Calibrated(std, 0L)
        for ((name, cs) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val green = bannerColors(cal, cs)
            val red = bannerColors(CalibrationStatus.NotCalibrated, cs)
            // Theme roles: the green tertiary container, the red error container.
            assertEquals(cs.tertiaryContainer, green.container, name)
            assertEquals(cs.onTertiaryContainer, green.content, name)
            assertEquals(cs.errorContainer, red.container, name)
            assertEquals(cs.onErrorContainer, red.content, name)
            // Green is green and red is red in this theme (a later theme change must keep that).
            assertTrue(green.container.green > green.container.red && green.container.green > green.container.blue, "$name green ${green.container}")
            assertTrue(red.container.red > red.container.green && red.container.red > red.container.blue, "$name red ${red.container}")
            // Text ≥ 7:1 (WCAG AAA), icon ≥ 3:1 (non-text), against the banner's own container.
            for ((what, c) in listOf("green" to green, "red" to red)) {
                assertTrue(contrast(c.content, c.container) >= 7.0, "$name $what text ${contrast(c.content, c.container)}")
                assertTrue(contrast(c.icon, c.container) >= 3.0, "$name $what icon ${contrast(c.icon, c.container)}")
            }
        }
        // No literal colour in the banner: the theme decides.
        val banner = src("ui/CalibrationBanner.kt")
        assertFalse(banner.contains("Color(0x"))
        assertEquals(48, BANNER_MIN_HEIGHT.value.toInt(), "a full touch target")
        // The meaning is also in the icon, not in the colour alone.
        assertTrue(banner.contains("if (status is CalibrationStatus.Calibrated) Icons.Filled.CheckCircle else Icons.Filled.Warning"))
    }

    @Test
    fun aTapOpensKalibrieren() {
        // The tab switch: «Einstellungen» shown (pushed like any tab switch, so back returns to «Messen»), one request.
        val link = CalibrationLink()
        assertFalse(link.requested)
        val h = link.open(TabHistory())
        assertEquals(Screen.SETTINGS, h.current)
        assertTrue(link.requested)
        assertEquals(Screen.MEASURE, h.back().current)
        link.taken()
        assertFalse(link.requested, "the next visit to «Einstellungen» starts at the top")
        // From another tab history: the same.
        assertEquals(listOf(Screen.MEASURE, Screen.MAP, Screen.SETTINGS), CalibrationLink().open(TabHistory().select(Screen.MAP)).stack)

        // The banner's tap is the link (MainActivity), and «Einstellungen» takes the request into «Erweitert».
        val root = src("ui/MainActivity.kt")
        assertTrue(root.contains("val calibrationLink = remember { CalibrationLink() }"))
        assertTrue(root.contains("onCalibrate = { history = calibrationLink.open(history) },"))
        assertTrue(root.contains("Screen.SETTINGS -> SettingsScreen(m, atCalibration = calibrationLink.requested, onAtCalibration = calibrationLink::taken)"))
        val settings = src("ui/DataAndSettingsScreens.kt")
        assertTrue(settings.contains("var advanced by rememberSaveable { mutableStateOf(atCalibration) }"), "«Erweitert» from the first frame")
        assertTrue(settings.contains("LaunchedEffect(atCalibration) {\n        if (atCalibration) {\n            advanced = true\n            advancedAtCalibration = true\n            onAtCalibration()"))
        assertTrue(settings.contains("AdvancedScreen(onBack = { advanced = false; advancedAtCalibration = false }, modifier = modifier, atCalibration = advancedAtCalibration)"))
        // The row «Erweitert» still opens it at the top (as before).
        assertTrue(settings.contains("onAdvanced = { advanced = true },"))

        // «Erweitert» starts at the heading «Kalibrieren»: the item CALIBRATE_ITEM is CalibrateIntro.
        val adv = src("ui/CalibrateScreen.kt")
        assertTrue(adv.contains("val list = rememberLazyListState(initialFirstVisibleItemIndex = if (atCalibration) CALIBRATE_ITEM else 0)"))
        assertTrue(adv.contains("LazyColumn(modifier.fillMaxWidth(), state = list) {"))
        val lazy = adv.substring(adv.indexOf("LazyColumn(modifier.fillMaxWidth(), state = list) {"), adv.indexOf("item { CalibrateIntro() }"))
        assertEquals(CALIBRATE_ITEM, Regex("""(?m)^\s+(item|items)\s*[({]""").findAll(lazy).count(), lazy)
        assertEquals(3, CALIBRATE_ITEM)
    }

    @Test
    fun theBannerOnMessenAndOnKalibrieren() {
        val screen = src("ui/MeasureScreen.kt")
        // «Messen»: the app's status, both states, a tap opens «Kalibrieren».
        assertTrue(screen.contains("val calibration by c.calibrationStatus.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("        calibration = calibration,\n        onCalibrate = onCalibrate,\n    )"))
        val idle = screen.substring(screen.indexOf("fun IdleMeasure("), screen.indexOf("fun MoreSheetContent("))
        val title = idle.indexOf("ScreenTitle(\"Messen\", Modifier.align(Alignment.Start))")
        val banner = idle.indexOf("calibration?.let { CalibrationBanner(it, onClick = onCalibrate) }")
        val logo = idle.indexOf("AppLogo(")
        assertTrue(title in 0 until banner && banner < idle.indexOf("Spacer(Modifier.weight(IDLE_WEIGHT_ABOVE))") && banner < logo, "$title $banner $logo")
        val content = screen.substring(screen.indexOf("fun MeasureContent("), screen.indexOf("/** The level card of a running measurement"))
        val running = content.substring(content.indexOf("return@Scaffold"))
        val rTitle = running.indexOf("item { ScreenTitle(\"Messen\") }")
        val rBanner = running.indexOf("calibration?.let { status -> item { CalibrationBanner(status, Modifier.padding(bottom = 6.dp), onClick = onCalibrate) } }")
        val silenced = running.indexOf("if (live.micSilenced || live.invalidAudio)")
        val card = running.indexOf("if (onReport != null) CompactLevelCard(")
        assertTrue(rTitle in 0 until rBanner && rBanner < silenced && silenced < card, "$rTitle $rBanner $silenced $card")
        assertTrue(content.contains("calibration = calibration, onCalibrate = onCalibrate,"), "idle gets the banner too")
        // Exactly two banners on «Messen» (idle, running), none drawn elsewhere in the file.
        assertEquals(2, Regex("""CalibrationBanner\(""").findAll(screen).count())

        // «Kalibrieren»: the same banner under the heading, without a tap (it would open itself), from the screen's
        // own active calibration (what the next measurement uses).
        val adv = src("ui/CalibrateScreen.kt")
        assertTrue(adv.contains("val banner by vm.bannerStatus.collectAsStateWithLifecycle()"))
        val intro = adv.indexOf("item { CalibrateIntro() }")
        val calBanner = adv.indexOf("banner?.let { status -> item { CalibrationBanner(status, Modifier.padding(top = 12.dp, bottom = 2.dp)) } }")
        assertTrue(intro in 0 until calBanner && calBanner < adv.indexOf("CalibrationDeviation.activeLine("), "$intro $calBanner")
        assertFalse(adv.substring(calBanner, adv.indexOf('\n', calBanner)).contains("onClick"))

        // The app's status follows the microphone: while measuring the running measurement's calibration (its id), else
        // the active one of the microphone a measurement would use now.
        val app = src("LessNoiseApp.kt")
        assertTrue(app.contains("live.map { it.running to it.calibrationId }.distinctUntilChanged(),"))
        assertTrue(app.contains("microphones.resolution.map { it.key }.distinctUntilChanged(),"))
        assertTrue(app.contains("if (running) flow { emit(CalibrationStatus.of(calibrationId?.let { calibrations.byId(it) })) }"))
        assertTrue(app.contains("else calibrations.activeFlow(source, mic).map { CalibrationStatus.of(it) }"))
        assertTrue(app.contains(".stateIn(appScope, SharingStarted.Eagerly, null)"))
        val vm = src("ui/CalibrationViewModel.kt")
        assertTrue(vm.contains("activeOfMic.map { CalibrationStatus.of(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)"))
    }
}
