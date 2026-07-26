package de.nereide.strohhalm

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import de.nereide.strohhalm.domain.archive.ArchiveNames

/**
 * Serves archives to other apps, under a name a person would want to receive.
 *
 * `FileProvider` reports the on-disk filename as the display name, which would
 * send every backup out carrying a twelve-character hash. That suffix exists so
 * two ref states never collide on one path — it is meaningless to a recipient,
 * so it is stripped here rather than given up on disk.
 */
class ArchiveFileProvider : FileProvider() {

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val delegate = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val column = delegate.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column < 0) return delegate

        return delegate.use { source ->
            val names = Array(source.columnCount) { source.getColumnName(it) }
            val rewritten = MatrixCursor(names, source.count)
            while (source.moveToNext()) {
                val row = arrayOfNulls<Any>(source.columnCount)
                for (i in 0 until source.columnCount) {
                    row[i] = when {
                        i == column -> ArchiveNames.displayName(source.getString(i).orEmpty())
                        source.getType(i) == Cursor.FIELD_TYPE_INTEGER -> source.getLong(i)
                        else -> source.getString(i)
                    }
                }
                rewritten.addRow(row)
            }
            rewritten
        }
    }
}
