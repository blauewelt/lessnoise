package ch.lessnoise.app.labor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lessnoise.app.icons.AppIcons
import ch.lessnoise.app.ui.ActionRow
import ch.lessnoise.app.ui.CardBlock
import ch.lessnoise.app.ui.SectionCard
import ch.lessnoise.app.ui.SwitchRow

// LABOR BUILD ONLY (app/src/labor/). 0.9.4.6: the «Labor» card in «Einstellungen» — ONE switch «Audio speichern»
// and, only while it is on or recordings exist, what the recordings need: the space used, «Clips anhören», the
// export and the two deletes. Stateless, so it renders in a JVM test (LaborCardScreenshots); EditionUi.SettingsSection
// (edition/Edition.kt) holds the state, the dialogs and the export service.

/** The export block's state and actions (scope choice, the two exports, the folder, the running job). */
class LaborExportBlock(
    val scope: ExportScope,
    val onScope: (ExportScope) -> Unit,
    val withoutHourFiles: Boolean,
    val onWithoutHourFiles: (Boolean) -> Unit,
    val estimate: String?,
    val folderNote: String?,
    val job: ExportJobState,
    val onFolder: () -> Unit,
    val onZip: () -> Unit,
    val onPickFolder: () -> Unit,
    val onCancel: () -> Unit,
    val onShareZip: () -> Unit,
)

/**
 * The card. [switchSupporting]: the line under «Audio speichern». [showFiles] ([LaborCardTexts.showsFiles]): the
 * rest of the card; [used] «1,2 GB von 5 GB belegt» (null while it is computed); [notices] the red storage lines
 * (EditionUi.StorageNotice and «Speicher voll …», only while recording); [clips] how many clips exist («Clips
 * anhören» is disabled without any); [deleteEnabled] false while recording or exporting, [deleteNote] says why.
 */
@Composable
fun LaborCardContent(
    recordAudio: Boolean,
    onRecordAudio: (Boolean) -> Unit,
    switchSupporting: String,
    showFiles: Boolean,
    used: String?,
    clips: Int,
    onClips: () -> Unit,
    export: LaborExportBlock,
    deleteEnabled: Boolean,
    deleteNote: String?,
    onDeleteExported: () -> Unit,
    onDeleteAll: () -> Unit,
    message: String?,
    notices: @Composable () -> Unit = {},
) {
    SectionCard(LaborCardTexts.TITLE, rows = true) {
        SwitchRow(LaborCardTexts.SWITCH, recordAudio, onRecordAudio, supporting = switchSupporting, icon = AppIcons.Mic)
        if (!showFiles) return@SectionCard
        CardBlock {
            Text(used ?: "…", style = MaterialTheme.typography.bodyMedium)
            notices()
            OutlinedButton(onClick = onClips, enabled = clips > 0) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(LaborCardTexts.CLIPS)
            }
            val exporting = export.job is ExportJobState.Running
            ExportScopeControls(
                scope = export.scope, onScope = export.onScope,
                withoutHourFiles = export.withoutHourFiles, onWithoutHourFiles = export.onWithoutHourFiles,
                estimate = export.estimate, folderNote = export.folderNote, enabled = !exporting,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 0.9.4.1: both stay tappable; while an export runs a second one is refused with a short message.
                OutlinedButton(onClick = export.onFolder, modifier = Modifier.weight(1f)) {
                    Text(LaborCardTexts.EXPORT_FOLDER, textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = export.onZip, modifier = Modifier.weight(1f)) {
                    Text(LaborCardTexts.EXPORT_ZIP, textAlign = TextAlign.Center)
                }
            }
            TextButton(onClick = export.onPickFolder) { Text(LaborCardTexts.PICK_FOLDER) }
            ExportJobPanel(state = export.job, onCancel = export.onCancel, onShareZip = export.onShareZip, onFolderExport = export.onFolder)
        }
        ActionRow(LaborCardTexts.DELETE_EXPORTED, Icons.Outlined.Delete, onDeleteExported, enabled = deleteEnabled)
        ActionRow(
            LaborCardTexts.DELETE_ALL, Icons.Outlined.Delete, onDeleteAll, enabled = deleteEnabled, destructive = true,
            supporting = deleteNote,
        )
        message?.let { CardBlock { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
