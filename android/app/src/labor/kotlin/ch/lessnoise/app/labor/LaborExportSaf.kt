package ch.lessnoise.app.labor

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

// LABOR BUILD ONLY (app/src/labor/). The Android half of «In Ordner exportieren»: the picked
// folder (a Storage Access Framework tree URI — Downloads, a USB stick, the Google Drive app …),
// the persisted ledger and the DocumentsContract sink.

/** The export destination and its bookkeeping, in the Labor SharedPreferences and filesDir. */
object LaborExportStore {
    private const val KEY_TREE = "export_tree_uri"
    private const val LEDGER_FILE = "labor-export-ledger.tsv"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("labor", Context.MODE_PRIVATE)

    fun treeUri(context: Context): Uri? = prefs(context).getString(KEY_TREE, null)?.let { Uri.parse(it) }

    fun setTreeUri(context: Context, uri: Uri?) {
        prefs(context).edit().apply { if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString()) }.apply()
    }

    private const val KEY_SCOPE = "export_scope"
    private const val KEY_WITHOUT_HOURS = "export_without_hour_files"

    /** 0.9.4: the «Export» row («Letzte 24 h» until changed). */
    fun scope(context: Context): ExportScope = ExportScope.of(prefs(context).getString(KEY_SCOPE, null))

    fun setScope(context: Context, scope: ExportScope) { prefs(context).edit().putString(KEY_SCOPE, scope.key).apply() }

    /** 0.9.4: «Ohne Stundendateien», one switch for both buttons, on until changed. */
    fun withoutHourFiles(context: Context): Boolean = prefs(context).getBoolean(KEY_WITHOUT_HOURS, true)

    fun setWithoutHourFiles(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_WITHOUT_HOURS, v).apply() }

    /** True if the persisted read+write grant for [uri] is still held (false after a reinstall or «Clear data»). */
    fun hasPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    private fun ledgerFile(context: Context) = File(context.applicationContext.filesDir, LEDGER_FILE)

    fun loadLedger(context: Context): ExportLedger = try {
        val f = ledgerFile(context)
        if (f.isFile) ExportLedger.parse(f.readText()) else ExportLedger()
    } catch (e: Exception) {
        ExportLedger()
    }

    fun saveLedger(context: Context, ledger: ExportLedger) {
        val f = ledgerFile(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(ledger.serialize())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** After «Alle Aufnahmen löschen»: a new recording could reuse a name with the same size and second. */
    fun clearLedger(context: Context) { ledgerFile(context).delete() }

    /**
     * 0.9.4: since the ZIP records into the same ledger, a new destination also forgets what went into
     * ZIPs («Exportierte Aufnahmen löschen» then deletes less, never more).
     * A new destination starts with an empty ledger: nothing is there yet.
     */
    fun changeTree(context: Context, uri: Uri) { setTreeUri(context, uri); clearLedger(context) }

    /** The folder's display name for the final message («Exportiert … nach Downloads»). */
    fun treeName(context: Context, uri: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        try {
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {
        }
        // Fallback: the last path segment of the tree id, e.g. «primary:Download» → «Download».
        return DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Ordner" }
    }
}

/**
 * Writes into a SAF tree with DocumentsContract: sub-folders (`clips`, …) are created once and
 * cached, an existing file of the same name is deleted first (the manifest is re-copied every
 * time). A SecurityException from the resolver (grant revoked) propagates to the export, which
 * asks for the folder again.
 */
class SafExportSink(private val resolver: ContentResolver, private val tree: Uri, override val name: String) : ExportSink {
    private val rootDoc: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val folders = HashMap<String, Uri>()

    private fun childrenOf(parent: Uri): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))

    /** The child named [name] of [parent], or null. */
    private fun find(parent: Uri, name: String): Pair<Uri, String>? {
        resolver.query(
            childrenOf(parent),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) to c.getString(2)
            }
        }
        return null
    }

    private fun folder(folder: String): Uri {
        if (folder.isEmpty()) return rootDoc
        folders[folder]?.let { return it }
        val existing = find(rootDoc, folder)
        val uri = when {
            existing == null -> DocumentsContract.createDocument(resolver, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, folder)
                ?: throw FileNotFoundException("Ordner $folder nicht erstellt")
            existing.second == DocumentsContract.Document.MIME_TYPE_DIR -> existing.first
            else -> throw FileNotFoundException("$folder ist kein Ordner")
        }
        folders[folder] = uri
        return uri
    }

    override fun openForWrite(folder: String, name: String): OutputStream {
        val parent = folder(folder)
        find(parent, name)?.let { DocumentsContract.deleteDocument(resolver, it.first) }
        val doc = DocumentsContract.createDocument(resolver, parent, mimeOf(name), name)
            ?: throw FileNotFoundException("$name nicht erstellt")
        return resolver.openOutputStream(doc, "w") ?: throw FileNotFoundException("$name nicht schreibbar")
    }

    /** 0.9.4.1: after «Abbrechen» in the middle of a file: delete the half-written document. */
    override fun discard(folder: String, name: String) {
        find(folder(folder), name)?.let { DocumentsContract.deleteDocument(resolver, it.first) }
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "")) {
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "jsonl" -> "application/x-ndjson"
        else -> "application/octet-stream"
    }
}
