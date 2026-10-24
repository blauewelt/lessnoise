package ch.lessnoise.app.ui

import java.util.Locale

/**
 * 0.8.2: picks the licence plate out of the text lines an on-device text recognition found on a
 * photo of «Kontrollschild» (pure, PlateOcrTest). Order of preference:
 *
 * 1. a Swiss plate: canton code (AG AI AR BE BL BS FR GE GL GR JU LU NE NW OW SG SH SO SZ TG TI UR VD
 *    VS ZG ZH) and 1–6 digits, spaces, dashes, dots or the middle dot allowed between them
 *    («ZH·123 456», «be-12345») → «ZH 123456»;
 * 2. Liechtenstein: «FL» and 1–6 digits, the same way → «FL 1234»;
 * 3. a foreign plate (D, A, F, I …): a whole line of 4–10 characters, at least three quarters
 *    letters and digits, with at least one letter and one digit («M-AB 1234») → as read, in capitals.
 *
 * Among several of the same kind the one with the most letters and digits wins, then the first.
 * The result is at most 10 characters (fits [ch.lessnoise.app.upload.ReportPlate.MAX_LENGTH]); null
 * when nothing fits. The person can always edit the field afterwards.
 */
object PlateOcr {
    val CANTONS = listOf(
        "AG", "AI", "AR", "BE", "BL", "BS", "FR", "GE", "GL", "GR", "JU", "LU", "NE",
        "NW", "OW", "SG", "SH", "SO", "SZ", "TG", "TI", "UR", "VD", "VS", "ZG", "ZH",
    )

    private const val SEP = """[\s.\-·•]*"""
    private const val DIGITS = """(\d(?:[\s.\-]?\d){0,5})(?![\s.\-]?\d)"""
    private val swiss = Regex("""(?<![A-Z])(${CANTONS.joinToString("|")})$SEP$DIGITS""")
    private val liechtenstein = Regex("""(?<![A-Z])(FL)$SEP$DIGITS""")

    fun bestPlate(lines: List<String>): String? {
        val up = lines.map { it.uppercase(Locale.ROOT) }
        return best(up.flatMap { coded(swiss, it) })
            ?: best(up.flatMap { coded(liechtenstein, it) })
            ?: best(up.mapNotNull(::foreign))
    }

    /** «ZH 123456» for every match of [re] (code + digits) in [line]. */
    private fun coded(re: Regex, line: String): List<String> =
        re.findAll(line).map { m -> m.groupValues[1] + " " + m.groupValues[2].filter(Char::isDigit) }.toList()

    /** A whole line that looks like a plate of another country, or null. */
    private fun foreign(line: String): String? {
        val t = line.trim().replace(Regex("""\s+"""), " ")
        if (t.length !in 4..10) return null
        val alnum = t.count { it in 'A'..'Z' || it in '0'..'9' }
        if (alnum * 4 < t.length * 3) return null
        if (t.none { it in 'A'..'Z' } || t.none { it in '0'..'9' }) return null
        // Keep letters, digits, spaces and dashes; drop stray marks the recogniser read (·, :, …).
        return t.filter { it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '-' }.trim().takeIf { it.length >= 4 }
    }

    private fun best(candidates: List<String>): String? =
        candidates.maxByOrNull { c -> c.count { it.isLetterOrDigit() } }
}
