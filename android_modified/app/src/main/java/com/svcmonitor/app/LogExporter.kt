package com.svcmonitor.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * LogExporter v8.1 — Export events to CSV or JSON files.
 * Uses app-private external storage (no permissions required).
 */
class LogExporter(private val ctx: Context) {

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private fun getExportDir(): File {
        val dir = File(ctx.getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun exportCsv(events: List<StatusParser.SvcEvent>): File {
        val ts = dateFormat.format(Date())
        val dir = getExportDir()
        val file = File(dir, "svc_events_$ts.csv")

        file.bufferedWriter().use { w ->
            w.write("seq,nr,name,pid,uid,comm,pc,caller,fp,sp,bt,clone_fn,a0,a1,a2,a3,a4,a5,desc")
            w.newLine()
            for (ev in events) {
                val desc = ev.desc.replace("\"", "\"\"")
                w.write("${ev.seq},${ev.nr},${ev.name},${ev.pid},${ev.uid},${ev.comm},")
                val bt = ev.bt.joinToString("|")
                w.write("${ev.pc},${ev.caller},${ev.fp},${ev.sp},\"$bt\",${ev.cloneFn},")
                w.write("${ev.a0},${ev.a1},${ev.a2},${ev.a3},${ev.a4},${ev.a5},")
                w.write("\"$desc\"")
                w.newLine()
            }
        }
        return file
    }

    fun exportJson(events: List<StatusParser.SvcEvent>): File {
        val ts = dateFormat.format(Date())
        val dir = getExportDir()
        val file = File(dir, "svc_events_$ts.json")

        val arr = JSONArray()
        for (ev in events) {
            arr.put(JSONObject().apply {
                put("seq", ev.seq)
                put("nr", ev.nr)
                put("name", ev.name)
                put("pid", ev.pid)
                put("uid", ev.uid)
                put("comm", ev.comm)
                put("pc", ev.pc)
                put("caller", ev.caller)
                put("fp", ev.fp)
                put("sp", ev.sp)
                put("bt", JSONArray(ev.bt))
                put("clone_fn", ev.cloneFn)
                put("a0", ev.a0)
                put("a1", ev.a1)
                put("a2", ev.a2)
                put("a3", ev.a3)
                put("a4", ev.a4)
                put("a5", ev.a5)
                put("desc", ev.desc)
            })
        }

        file.writeText(arr.toString(2))
        return file
    }
}
