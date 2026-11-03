/*
 * Copyright (c) 2025 Auxio Project
 * LyricsScanner.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.lyrics.LrcParser
import timber.log.Timber as L

/** One lyric file found on the device, reduced to what matching needs. */
internal data class LrcEntry(
    val uri: Uri,
    val name: String,
    val title: String?,
    val artist: String?,
    val lastMs: Long,
    val lineCount: Int,
)

/**
 * Finds every `.lrc` under the folders Auxio already has access to.
 *
 * Lyric folders are usually a sibling of the music folder (both under `Music/`), so the same SAF
 * roots that the library uses are walked recursively. Files are small, so each one is read whole
 * and parsed once; the result is kept in memory for the duration of a scan only.
 */
internal class LyricsScanner(private val context: Context) {

    suspend fun scan(
        roots: List<Location.Opened>,
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> },
    ): List<LrcEntry> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<LrcEntry>()
            var scanned = 0
            for (root in roots) {
                currentCoroutineContext().ensureActive()
                walk(
                    treeUri = root.uri,
                    documentId = DocumentsContract.getTreeDocumentId(root.uri),
                    out = out,
                    tick = {
                        scanned++
                        if (scanned % 16 == 0) {
                            onProgress(scanned, out.size)
                        }
                    },
                )
            }
            onProgress(scanned, out.size)
            out
        }

    private suspend fun walk(
        treeUri: Uri,
        documentId: String,
        out: MutableList<LrcEntry>,
        tick: () -> Unit,
    ) {
        val scope = currentCoroutineContext()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor =
            try {
                context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
            } catch (e: Exception) {
                null
            } ?: return

        cursor.use {
            while (it.moveToNext()) {
                scope.ensureActive()
                val childId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                if (name.startsWith(".")) {
                    continue
                }
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, childId, out, tick)
                    continue
                }
                if (!name.endsWith(".lrc", ignoreCase = true)) {
                    continue
                }
                tick()
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                readEntry(uri, name)?.let { entry ->
                    if (entry.lineCount > 0) {
                        out += entry
                    }
                }
            }
        }
    }

    private fun readEntry(uri: Uri, name: String): LrcEntry? {
        val bytes =
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // A sane ceiling: real lyric files are a few kilobytes.
                    val buffer = ByteArray(MAX_SIZE)
                    var read = 0
                    while (read < MAX_SIZE) {
                        val n = input.read(buffer, read, MAX_SIZE - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
            } catch (e: Exception) {
                L.w(e, "Could not read $name")
                null
            } ?: return null

        val lrc =
            try {
                val text = decode(bytes) ?: return null
                LrcParser.parse(text)
            } catch (e: Exception) {
                return null
            }

        val title = lrc.title ?: lrc.lines.firstOrNull()?.text?.let(::titleFromFirstLine)
        return LrcEntry(
            uri = uri,
            name = name,
            title = title,
            artist = lrc.artist,
            lastMs = lrc.lastTimeMs,
            lineCount = lrc.lines.size,
        )
    }

    /**
     * Many files carry no `[ti:]`, but the first timed line is conventionally "Title - Artist".
     * Only used when there is no metadata tag at all.
     */
    private fun titleFromFirstLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        // Instrumental placeholders are not song names.
        if (trimmed.contains("没有填词") || trimmed.contains("纯音乐")) {
            return null
        }
        val separator = trimmed.lastIndexOf(" - ")
        val candidate = if (separator > 0) trimmed.substring(0, separator) else trimmed
        return candidate.trim().ifEmpty { null }?.takeIf { it.length in 1..40 }
    }

    private fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) {
            return null
        }
        // UTF-8 BOM is common in files exported by Chinese tagging tools.
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        decodeStrict(bytes, Charsets.UTF_8)?.let {
            return it
        }
        // Fall back to GB18030, which covers GBK and is what older tools wrote.
        return decodeStrict(bytes, GB18030)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? =
        try {
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

    private companion object {
        const val MAX_SIZE = 512 * 1024
        val GB18030: Charset = Charset.forName("GB18030")

        val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
    }
}
