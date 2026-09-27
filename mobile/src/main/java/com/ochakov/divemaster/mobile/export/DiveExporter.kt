package com.ochakov.divemaster.mobile.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ochakov.divemaster.data.db.DiveDao
import com.ochakov.divemaster.data.export.DiveCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phone-side export: the same Subsurface CSV the watch writes, handed over
 * through the Android share sheet (email, Drive, Bluetooth, Subsurface-mobile,
 * …) via a FileProvider URI. Files are staged in the app cache; stale ones
 * are swept on the next export so nothing accumulates. [context] must be an
 * Activity, since the chooser is started from it.
 */
class DiveExporter(private val context: Context, private val dao: DiveDao) {

    /** One dive as a CSV. Returns false if the dive no longer exists. */
    suspend fun shareDive(diveId: Long): Boolean {
        val staged = withContext(Dispatchers.IO) {
            val dive = dao.dive(diveId) ?: return@withContext null
            val samples = dao.samplesFor(diveId)
            val file = stage(DiveCsv.fileName(dive))
            file.bufferedWriter().use { DiveCsv.write(dive, samples, it) }
            val started = SUBJECT_DATE.format(Instant.ofEpochMilli(dive.startEpochMs).atZone(ZoneId.systemDefault()))
            file to "DiveMaster dive $started"
        } ?: return false
        share(staged.first, "text/csv", staged.second)
        return true
    }

    /**
     * Every dive as a ZIP of per-dive CSVs — Subsurface imports each file as
     * its own dive, and the phone is the archive. Returns the dive count (0 =
     * nothing to export, no sheet shown).
     */
    suspend fun shareAll(): Int {
        val staged = withContext(Dispatchers.IO) {
            val dives = dao.allFinalized()
            if (dives.isEmpty()) return@withContext null
            val zip = stage("DiveMaster_dives_${FILE_STAMP.format(LocalDateTime.now())}.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                for (dive in dives) {
                    out.putNextEntry(ZipEntry(DiveCsv.fileName(dive)))
                    out.write(DiveCsv.render(dive, dao.samplesFor(dive.id)).toByteArray())
                    out.closeEntry()
                }
            }
            zip to dives.size
        } ?: return 0
        share(staged.first, "application/zip", "DiveMaster dive log (${staged.second} dives)")
        return staged.second
    }

    private fun stage(name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Sweep old exports, but leave anything recent alone: a share target
        // may still be reading the previous file.
        val cutoff = System.currentTimeMillis() - STALE_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        return File(dir, name)
    }

    private fun share(file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export ${file.name}"))
    }

    private companion object {
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
        val SUBJECT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        const val STALE_MS = 60 * 60 * 1000L
    }
}
