package com.svcmonitor.app

import android.content.Context
import com.svcmonitor.app.db.SvcEventDb
import com.svcmonitor.app.db.toSvcEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FullLogExporter {

    data class ExportResult(val file: File, val count: Int)

    suspend fun exportCsv(context: Context): ExportResult = withContext(Dispatchers.IO) {
        val dao = SvcEventDb.get(context.applicationContext).dao()
        val maxSeq = dao.latest(1).firstOrNull()?.seq ?: throw IllegalStateException("No events to export")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val outFile = File(outDir, "svc_events_resolved_$ts.csv")
        var cursor = 0L
        var count = 0
        outFile.bufferedWriter().use { w ->
            w.write(ExportEventFormatter.csvHeader())
            w.newLine()
            while (cursor < maxSeq) {
                val chunk = dao.afterSeq(cursor, 1000).filter { it.seq <= maxSeq }
                if (chunk.isEmpty()) break
                for (entity in chunk) {
                    val e = entity.toSvcEvent()
                    w.write(ExportEventFormatter.toCsvLine(e))
                    w.newLine()
                    cursor = entity.seq
                    count++
                }
                w.flush()
            }
        }
        if (count == 0) throw IllegalStateException("No events to export")
        ExportResult(outFile, count)
    }

    suspend fun exportJsonl(context: Context): ExportResult = withContext(Dispatchers.IO) {
        val dao = SvcEventDb.get(context.applicationContext).dao()
        val maxSeq = dao.latest(1).firstOrNull()?.seq ?: throw IllegalStateException("No events to export")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val outFile = File(outDir, "svc_events_resolved_$ts.jsonl")
        var cursor = 0L
        var count = 0
        outFile.bufferedWriter().use { w ->
            while (cursor < maxSeq) {
                val chunk = dao.afterSeq(cursor, 1000).filter { it.seq <= maxSeq }
                if (chunk.isEmpty()) break
                for (entity in chunk) {
                    val e = entity.toSvcEvent()
                    w.write(ExportEventFormatter.toJsonObject(e).toString())
                    w.newLine()
                    cursor = entity.seq
                    count++
                }
                w.flush()
            }
        }
        if (count == 0) throw IllegalStateException("No events to export")
        ExportResult(outFile, count)
    }
}
