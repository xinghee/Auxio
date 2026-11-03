/*
 * Copyright (c) 2025 Auxio Project
 * TagFileWriter.kt is part of Auxio.
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
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** One file that was rewritten, and where its original tag went. */
internal data class RepairRecord(
    val name: String,
    val uri: String,
    val backup: String,
    val tagSize: Int,
)

/** Reads and writes whole media files through SAF. */
internal class TagFileWriter(private val context: Context) {

    fun readAll(uri: Uri): ByteArray? =
        try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }

    /**
     * Write [newBytes], falling back to [originalBytes] if the write fails part way through.
     *
     * SAF has no atomic replace, so the only safety net is keeping the original contents in memory
     * and writing them back when something goes wrong.
     */
    fun rewrite(uri: Uri, originalBytes: ByteArray, newBytes: ByteArray): Boolean =
        try {
            write(uri, newBytes)
            true
        } catch (e: Exception) {
            try {
                write(uri, originalBytes)
            } catch (ignored: Exception) {
                // The tag backup on disk is the remaining way out.
            }
            false
        }

    private fun write(uri: Uri, bytes: ByteArray) {
        val stream =
            context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("cannot open $uri for writing")
        stream.use {
            it.write(bytes)
            it.flush()
        }
    }
}

/**
 * Keeps the original ID3 block of every rewritten file, plus an index of what was done.
 *
 * Only the tag is stored, not the audio: a file's audio payload is never touched, so restoring the
 * tag block is enough to undo the change while costing a few kilobytes per song.
 */
internal class TagBackupStore(context: Context) {
    private val dir = File(context.filesDir, BACKUP_DIR)
    private val indexFile = File(dir, INDEX_FILE)

    /** @return the backup file name, or null when it could not be written. */
    fun backup(uri: Uri, tagBytes: ByteArray): String? =
        try {
            dir.mkdirs()
            val name = "${key(uri)}.id3"
            File(dir, name).writeBytes(tagBytes)
            name
        } catch (e: Exception) {
            null
        }

    fun commit(records: List<RepairRecord>) {
        if (records.isEmpty()) return
        try {
            dir.mkdirs()
            val root =
                if (indexFile.exists()) JSONObject(indexFile.readText()) else JSONObject()
            val batches = root.optJSONArray(KEY_BATCHES) ?: JSONArray()

            val entries = JSONArray()
            records.forEach { record ->
                entries.put(
                    JSONObject()
                        .put("name", record.name)
                        .put("uri", record.uri)
                        .put("backup", record.backup)
                        .put("tagSize", record.tagSize)
                )
            }

            val kept = JSONArray()
            val from = maxOf(0, batches.length() - (MAX_BATCHES - 1))
            for (i in from until batches.length()) {
                kept.put(batches.get(i))
            }
            kept.put(JSONObject().put("time", System.currentTimeMillis()).put("entries", entries))

            root.put(KEY_BATCHES, kept)
            indexFile.writeText(root.toString())
        } catch (e: Exception) {
            // Losing the index is not fatal, the .id3 files are still on disk.
        }
    }

    fun lastBatch(): List<RepairRecord> =
        try {
            if (!indexFile.exists()) return emptyList()
            val batches = JSONObject(indexFile.readText()).optJSONArray(KEY_BATCHES)
                ?: return emptyList()
            if (batches.length() == 0) return emptyList()
            val entries = batches.getJSONObject(batches.length() - 1).optJSONArray("entries")
                ?: return emptyList()
            (0 until entries.length()).mapNotNull { i ->
                val entry = entries.optJSONObject(i) ?: return@mapNotNull null
                RepairRecord(
                    name = entry.optString("name"),
                    uri = entry.optString("uri"),
                    backup = entry.optString("backup"),
                    tagSize = entry.optInt("tagSize"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

    fun loadTag(record: RepairRecord): ByteArray? =
        try {
            val file = File(dir, record.backup)
            if (!file.exists()) null else file.readBytes()
        } catch (e: Exception) {
            null
        }

    fun dropLastBatch() {
        try {
            val records = lastBatch()
            records.forEach { File(dir, it.backup).delete() }
            if (!indexFile.exists()) return
            val root = JSONObject(indexFile.readText())
            val batches = root.optJSONArray(KEY_BATCHES) ?: return
            val kept = JSONArray()
            for (i in 0 until batches.length() - 1) {
                kept.put(batches.get(i))
            }
            root.put(KEY_BATCHES, kept)
            indexFile.writeText(root.toString())
        } catch (e: Exception) {
            // Nothing else to do.
        }
    }

    private fun key(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BACKUP_DIR = "tag-backup"
        const val INDEX_FILE = "index.json"
        const val KEY_BATCHES = "batches"
        const val MAX_BATCHES = 3
    }
}
