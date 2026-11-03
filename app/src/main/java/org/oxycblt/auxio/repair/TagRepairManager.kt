/*
 * Copyright (c) 2025 Auxio Project
 * TagRepairManager.kt is part of Auxio.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.tag.write.TagEncoderRepair

/** What a repair run achieved. */
internal data class RepairOutcome(val repaired: Int, val failed: List<String>)

/**
 * Glue between scanning, rewriting and backing up.
 *
 * Every file goes through the same sequence: read it whole, rewrite in memory, verify the result
 * has no remaining fixes, back up the original tag, then write. Any step fails, the file is left
 * alone and reported.
 */
internal class TagRepairManager(context: Context) {
    private val appContext = context.applicationContext
    private val scanner = TagDirectoryScanner(appContext)
    private val writer = TagFileWriter(appContext)
    private val backups = TagBackupStore(appContext)

    suspend fun scan(
        roots: List<Location.Opened>,
        onProgress: (scanned: Int, found: Int) -> Unit,
    ): List<RepairCandidate> = scanner.scan(roots, onProgress)

    suspend fun repair(
        items: List<RepairCandidate>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RepairOutcome =
        withContext(Dispatchers.IO) {
            val records = mutableListOf<RepairRecord>()
            val failed = mutableListOf<String>()
            var done = 0
            for (item in items) {
                currentCoroutineContext().ensureActive()
                if (!repairOne(item, records)) {
                    failed += item.displayName
                }
                done++
                onProgress(done, items.size)
            }
            backups.commit(records)
            RepairOutcome(records.size, failed)
        }

    /** @return true when the file was rewritten and recorded. */
    private fun repairOne(item: RepairCandidate, records: MutableList<RepairRecord>): Boolean {
        val original = writer.readAll(item.uri) ?: return false
        val rewritten = TagEncoderRepair.rewrite(original, item.analysis) ?: return false

        // The rewrite must leave nothing left to repair, otherwise we would write a half fix.
        val verify = TagEncoderRepair.analyze(rewritten)
        if (verify != null && verify.fixes.isNotEmpty()) return false

        val tagSize = item.analysis.tagSize
        if (tagSize <= 0 || tagSize > original.size) return false

        // Back up before writing; if we cannot, skip the file so the change stays reversible.
        val backupName = backups.backup(item.uri, original.copyOfRange(0, tagSize)) ?: return false

        if (!writer.rewrite(item.uri, original, rewritten)) {
            return false
        }

        records += RepairRecord(item.displayName, item.uri.toString(), backupName, tagSize)
        return true
    }

    fun lastBackupBatch(): List<RepairRecord> = backups.lastBatch()

    fun dropLastBackupBatch() = backups.dropLastBatch()
}
