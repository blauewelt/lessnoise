package ch.lessnoise.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.audio.MicChoice
import ch.lessnoise.app.container

/** 0.7.2: texts of «Mikrofon» (pure, unit-tested). */
object MicrophoneTexts {
    const val TITLE = "Mikrofon"
    const val INTRO = "Gemessen wird mit dem eingebauten Mikrofon. Ein anderes Mikrofon hier wählen: " +
        "Die App nimmt es, sobald es verbunden ist, sonst das eingebaute."
    const val UNUSABLE = "«nicht nutzbar»: Bluetooth-Headsets liefern den Ton nur über die Telefonie-Verbindung, die die App nicht verwendet."
    const val WHILE_MEASURING = "Gilt sofort, auch für die laufende Messung."
}

/**
 * «Einstellungen → Erweitert → Mikrofon» (0.7.2): the connected microphones, the built-in one first
 * («Standard»). The choice is remembered per microphone (MicChoice.key); see [MicChoice.rows].
 */
@Composable
fun MicrophoneCard() {
    val c = LocalContext.current.container
    val inputs by c.microphones.inputs.collectAsStateWithLifecycle()
    val chosen by c.microphones.chosen.collectAsStateWithLifecycle()
    val live by c.live.collectAsStateWithLifecycle()
    val rows = MicChoice.rows(chosen, inputs)
    SectionCard(MicrophoneTexts.TITLE) {
        Text(MicrophoneTexts.INTRO, style = MaterialTheme.typography.bodySmall)
        Column {
            for (row in rows) {
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = row.selected, enabled = row.selectable, role = Role.RadioButton,
                        onClick = { c.microphones.choose(row.option) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = row.selected, onClick = null, enabled = row.selectable)
                    Text(
                        row.option.label + (row.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.selectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (rows.any { it.note == "nicht nutzbar" }) Text(MicrophoneTexts.UNUSABLE, style = MaterialTheme.typography.bodySmall)
        if (live.running) Text(MicrophoneTexts.WHILE_MEASURING, style = MaterialTheme.typography.bodySmall)
    }
}
