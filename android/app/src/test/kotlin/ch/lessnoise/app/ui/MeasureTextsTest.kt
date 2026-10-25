package ch.lessnoise.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.8.4 (was MeasureModeTest, 0.8.3): «Messen» without a mode switch — running means the window, idle
 * means the road. «Laut! Melden» first while idle (editions with the map), «Messung starten» under it,
 * one line; Labor·Karte's «Unterwegs aufnehmen» as a third button (MeasureTexts.kt, MeasureScreen.kt).
 */
class MeasureTextsTest {
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
    private val screen by lazy { File(app, "src/main/kotlin/ch/lessnoise/app/ui/MeasureScreen.kt").readText() }

    @Test
    fun texts() {
        assertEquals("Laut! Melden", REPORT_BUTTON)
        // 0.8.8: the button is about the long measurement at the window (until 0.8.7 «Messung starten»).
        assertEquals("Langzeitmessung starten", START_BUTTON)
        assertEquals("Stoppen", STOP_BUTTON)
        // 0.8.8: one helper line under each button, the same in every edition.
        assertEquals("Für Stunden am offenen Fenster, am Ladegerät.", START_HINT)
        assertEquals("Unterwegs: lauten Verkehr sofort melden.", REPORT_HINT)
        assertTrue(REPORT_HINT.length <= 45 && START_HINT.length <= 45)
        assertEquals("Unterwegs aufnehmen", UNTERWEGS_BUTTON)
        assertEquals("Labor: nimmt Audio auf, teilt nichts.", UNTERWEGS_LABOR_HINT)
        assertEquals("Läuft seit 18:30 · Bildschirm darf aus sein.", runningLine("18:30"))
        for (t in listOf(START_HINT, REPORT_HINT, UNTERWEGS_LABOR_HINT, runningLine("18:30"), PLACEMENT_HINT, GUIDE_LINK)) {
            assertTrue(t.length <= 80, "one line: $t")
            assertFalse(t.contains('ß') || Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
        }
    }

    @Test
    fun theModeSwitchAndItsStoredChoiceAreGone() {
        assertFalse(File(app, "src/main/kotlin/ch/lessnoise/app/ui/MeasureMode.kt").exists())
        for (bad in listOf("ModeSwitch", "MeasureMode", "SegmentedButton", "ExtendedFloatingActionButton", "floatingActionButton", "getSharedPreferences")) {
            assertFalse(screen.contains(bad), bad)
        }
        val texts = File(app, "src/main/kotlin/ch/lessnoise/app/ui/MeasureTexts.kt").readText()
        for (bad in listOf("enum class", "SharedPreferences", "\"Am Fenster\"", "Zum Wechseln")) assertFalse(texts.contains(bad), bad)
    }

    @Test
    fun idleReportFirstThenStartTonal() {
        // Order in the idle branch: the primary «Laut! Melden» (56 dp), then the tonal «Messung starten», the line,
        // Labor·Karte's outlined «Unterwegs aufnehmen» with its line. 0.8.6: all three 56 dp (STACK_BUTTON_HEIGHT)
        // with one label style (stackButtonLabel), the helper lines in HelperText.
        val idle = screen.substring(screen.indexOf("// Editions with the map: «Laut! Melden» is the first action"))
        val report = idle.indexOf("Text(REPORT_BUTTON, style = stackButtonLabel())")
        val reportHint = idle.indexOf("StackHint(REPORT_HINT)")
        val tonal = idle.indexOf("FilledTonalButton(onClick = onStart")
        val hint = idle.indexOf("StackHint(START_HINT)")
        val road = idle.indexOf("Text(UNTERWEGS_BUTTON, style = stackButtonLabel())")
        val roadHint = idle.indexOf("StackHint(UNTERWEGS_LABOR_HINT)")
        assertTrue(report in 0 until reportHint && reportHint < tonal && tonal < hint && hint < road && road < roadHint, "$report $reportHint $tonal $hint $road $roadHint")
        // 0.8.8: the same helper style for all three buttons, and the report line only where «Laut! Melden»
        // exists (inside `if (onReport != null)`). 0.9.0 (owner): centred over the full width (StackHint),
        // 8 dp under its button (was 4), 20 dp between the groups (was 16).
        assertTrue(idle.contains("Text(REPORT_BUTTON, style = stackButtonLabel())\n                    }\n                    Spacer(Modifier.height(STACK_HINT_GAP))\n                    StackHint(REPORT_HINT)\n                    Spacer(Modifier.height(STACK_GROUP_GAP))"))
        assertTrue(idle.contains("Spacer(Modifier.height(STACK_HINT_GAP))\n                StackHint(START_HINT)"))
        assertTrue(idle.contains("Spacer(Modifier.height(STACK_HINT_GAP))\n                    StackHint(UNTERWEGS_LABOR_HINT)"))
        assertEquals(8, STACK_HINT_GAP.value.toInt())
        assertEquals(20, STACK_GROUP_GAP.value.toInt())
        assertTrue(screen.contains("HelperText(text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)"), "the helper lines are centred, full width")
        assertFalse(Regex("""HelperText\((REPORT_HINT|START_HINT|UNTERWEGS_LABOR_HINT)""").containsMatchIn(screen), "every stack hint goes through StackHint")
        assertTrue(idle.substring(idle.indexOf("if (onReport != null) {"), reportHint).contains("onClick = onReport,"))
        assertTrue(idle.substring(0, report).contains("onClick = onReport,") && idle.substring(0, report).contains("Modifier.fillMaxWidth().height(STACK_BUTTON_HEIGHT)"))
        assertEquals(56, STACK_BUTTON_HEIGHT.value.toInt())
        // One height for every stacked button: no other height literal in the idle and running branches.
        val stack = screen.substring(screen.indexOf("// 0.8.6: one button height"), screen.indexOf("if (calibrating) HelperText("))
        assertFalse(Regex("""fillMaxWidth\(\)\.height\(\d+\.dp\)""").containsMatchIn(stack) || stack.contains("fontSize ="), "one height, one label style")
        assertEquals(4, Regex("""height\(STACK_BUTTON_HEIGHT\)""").findAll(stack).count(), "Laut! Melden, Messung starten (two variants), Unterwegs aufnehmen")
        // 0.8.7: «Stoppen» is in the running branch (MeasureContent), the idle stack in IdleMeasure; the same height.
        val running = screen.substring(screen.indexOf("// Running: «Stoppen»"), screen.indexOf("item { SectionHeading(\"Letzte Lärmspitzen\") }"))
        assertEquals(1, Regex("""height\(STACK_BUTTON_HEIGHT\)""").findAll(running).count(), "Stoppen")
        assertTrue(running.contains("Text(STOP_BUTTON, style = stackButtonLabel())"))
        // Without reports «Messung starten» stays the primary button.
        assertTrue(idle.contains("Button(onClick = onStart, enabled = startEnabled, modifier = Modifier.fillMaxWidth().height(STACK_BUTTON_HEIGHT), content = startContent)"))
        assertTrue(idle.contains("if (onUnterwegs != null) {\n                    Spacer(Modifier.height(STACK_GROUP_GAP))\n                    OutlinedButton(onClick = onUnterwegs"))
    }

    @Test
    fun noLautMeldenWhileRunning() {
        // The running branch («Stoppen» …) has no report button; the report dialog still opens from a preset
        // (shortcut, widget, Labor·Karte's notification «Melden»).
        // 0.8.7: the running branch is everything of MeasureContent after the idle branch returns.
        val content = screen.substring(screen.indexOf("fun MeasureContent("), screen.indexOf("/** 0.8.7: the logo's circle"))
        val running = content.substring(content.indexOf("return@Scaffold"))
        assertTrue(running.contains("onClick = onStop,"))
        assertFalse(running.contains("onReport"), running)
        assertTrue(content.contains("if (!live.running) {\n            IdleMeasure("))
        assertTrue(screen.contains("preset = reportPreset, onPresetUsed = onReportPresetUsed,"))
    }

    @Test
    fun startingIsTheWindowExceptLaborKartesUnterwegsButton() {
        // «Messung starten» is the window measurement; only the Labor·Karte button starts with EXTRA_UNTERWEGS
        // (ShareController.start(true) / MeasurementService.start(unterwegs = true)) and asks for notifications.
        assertTrue(screen.contains("onStart = { onStart(unterwegs = false) },"))
        assertTrue(screen.contains("onUnterwegs = if (LoudAlerts.AVAILABLE) ({ onStart(unterwegs = true) }) else null,"))
        assertTrue(screen.contains("val road = unterwegs && LoudAlerts.AVAILABLE"))
        assertTrue(screen.contains("share != null -> share.start(road)"))
        assertTrue(screen.contains("else -> MeasurementService.start(context, unterwegs = road)"))
        assertTrue(screen.contains("if (road) askUnterwegs()"))
        // The notification permission of the measurement itself is asked with the microphone's (Android 13+).
        val common = File(app, "src/main/kotlin/ch/lessnoise/app/ui/Common.kt").readText()
        assertTrue(common.contains("if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS"))
        // Colours only from the scheme (category dots aside, Common.kt).
        assertFalse(screen.contains("Color(0x"))
        assertFalse(screen.contains("secondaryContainer"))
    }

    @Test
    fun idleLogoInTheMiddleActionsAtTheBottom() {
        // 0.8.7 (the owner: «buttons crammed at the top, two thirds empty»): title, then the logo block between
        // two weighted spacers, then the actions; the column at least the viewport's height and scrollable.
        assertEquals("Weniger Lärm", APP_NAME)
        assertEquals(TAGLINE_REPORTS, idleTagline(reports = true))
        // 0.9.0 (owner): the tagline matches «Langzeitmessung starten»; Offline/Labor keep theirs.
        assertEquals("Kurz laut? Melden. Oft laut? Lange messen.", TAGLINE_REPORTS)
        assertEquals("Miss den Lärm vor deinem Fenster.", TAGLINE_PLAIN)
        assertEquals(TAGLINE_PLAIN, idleTagline(reports = false))
        for (t in listOf(TAGLINE_REPORTS, TAGLINE_PLAIN)) {
            // 0.9.0: the owner's «… Lange messen.» is 42 characters (limit was 40); one line at the default font on a 411 dp
            // phone (render messen-idle), two centred lines at 360 dp with 130 % font (messen-idle-360x640-font130).
            assertTrue(t.length <= 42, t)
            assertFalse(t.contains('ß') || Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
        }
        val idle = screen.substring(screen.indexOf("fun IdleMeasure("), screen.indexOf("fun MoreSheetContent("))
        assertTrue(idle.contains("Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)"))
        val order = listOf(
            "ScreenTitle(\"Messen\"", "Spacer(Modifier.weight(IDLE_WEIGHT_ABOVE))", "AppLogo()", "Text(APP_NAME",
            "idleTagline(reports = onReport != null)", "shareStatus?.takeIf(::showOnIdle)", "StatusChip(it, onShareStatus", "Spacer(Modifier.weight(IDLE_WEIGHT_BELOW))",
            "Text(REPORT_BUTTON", "Text(MORE_LABEL)", "ModalBottomSheet(",
        ).map { idle.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), order.toString())
        // No nested lazy list and no fold-out in the idle screen; «Mehr dazu» is a sheet.
        assertFalse(idle.contains("LazyColumn") || idle.contains("AnimatedVisibility"))
        // The logo is the launcher artwork of the edition on its own background colour (resources, no literal colour).
        assertTrue(screen.contains("painterResource(R.drawable.ic_launcher_foreground)"))
        assertTrue(screen.contains("colorResource(R.color.logo_background)"))
        val labor = File(app, "src/labor/res/values/colors.xml").readText()
        assertTrue(labor.contains("<color name=\"logo_background\">#FFB3261E</color>"))
        assertTrue(File(app, "src/labor/res/drawable/ic_launcher_background.xml").readText().contains("@color/logo_background"))
    }
}
