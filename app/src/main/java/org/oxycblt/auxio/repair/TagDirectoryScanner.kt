/*
 * Copyright (c) 2025 Auxio Project
 * TagDirectoryScanner.kt is part of Auxio.
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
 
package org.oxycblt.auxio.repair

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.tag.write.TagEncoderRepair
import org.oxycblt.musikr.tag.write.TagRepairInfo

/** A file that has at least one text frame we could repair. */
internal data class RepairCandidate(
    val uri: Uri,
    val displayName: String,
    val folder: String,
    val analysis: TagRepairInfo,
) {
    val fixCount: Int
        get() = analysis.fixes.size
}

/**
 * Walks the folders that are already registered in Auxio and reports the MP3s whose ID3v2 text
 * frames look like GBK written under a Latin-1 declaration.
 *
 * Only the tag header and frames are read, never the audio payload, so scanning a few hundred
 * files stays cheap.
 */
internal class TagDirectoryScanner(private val context: Context) {

    suspend fun scan(
        roots: List<Location.Opened>,
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> },
    ): List<RepairCandidate> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<RepairCandidate>()
            var scanned = 0
            for (root in roots) {
                ensureActive()
                walk(
                    treeUri = root.uri,
                    documentId = DocumentsContract.getTreeDocumentId(root.uri),
                    folder = root.uri.lastPathSegment ?: "",
                    results = results,
                    counter = { scanned++ },
                    onProgress = { onProgress(scanned, results.size) },
                )
            }
            onProgress(scanned, results.size)
            results
        }

    private suspend fun walk(
        treeUri: Uri,
        documentId: String,
        folder: String,
        results: MutableList<RepairCandidate>,
        counter: () -> Unit,
        onProgress: () -> Unit,
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
                // Hidden folders hold thumbnails and trash, never music.
                if (name.startsWith(".")) {
                    continue
                }
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, childId, "$folder/$name", results, counter, onProgress)
                    continue
                }
                if (!name.endsWith(".mp3", ignoreCase = true)) {
                    continue
                }
                counter()
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val analysis = inspect(fileUri) ?: continue
                if (analysis.fixes.isNotEmpty()) {
                    results += RepairCandidate(fileUri, name, folder, analysis)
                }
                onProgress()
            }
        }
    }

    private fun inspect(uri: Uri): TagRepairInfo? {
        val tag = readTagBytes(uri) ?: return null
        return try {
            TagEncoderRepair.analyze(tag)
        } catch (e: Exception) {
            null
        }
    }

    /** Reads just the ID3v2 header and its frames, without touching the audio payload. */
    private fun readTagBytes(uri: Uri): ByteArray? {
        val head = ByteArray(TAG_HEADER)
        context.contentResolver.openInputStream(uri)?.use { input ->
            if (!readFully(input, head, TAG_HEADER)) return null
            if (head[0] != 'I'.code.toByte() ||
                head[1] != 'D'.code.toByte() ||
                head[2] != '3'.code.toByte()) {
                return null
            }
            val size = synchsafe(head, 6)
            if (size <= 0 || size > MAX_TAG_SIZE) return null
            val body = ByteArray(size)
            if (!readFully(input, body, size)) return null
            return head + body
        }
        return null
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun synchsafe(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)

    private companion object {
        const val TAG_HEADER = 10
        const val MAX_TAG_SIZE = 16 * 1024 * 1024

        val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
    }
}
