package com.svcmonitor.app

import org.json.JSONArray
import org.json.JSONObject

object ExportEventFormatter {

    private suspend fun resolveEventFields(e: StatusParser.SvcEvent): Map<String, String> {
        val pcRes = AddressResolver.formatAddrSoOffset(e.tgid, e.pc)
        val callerRes = AddressResolver.formatAddrSoOffset(e.tgid, e.caller)
        val btRes = if (e.bt.isNotEmpty()) {
            e.bt.map { addr -> AddressResolver.formatAddrSoOffset(e.tgid, addr) }.joinToString(" ")
        } else ""
        return mapOf("pc" to pcRes, "caller" to callerRes, "bt" to btRes)
    }

    /**
     * Requested by user: swap displayed/exported pid/tgid fields.
     * - exported_tgid <- event.pid
     * - exported_pid  <- event.tgid
     */
    private fun swappedIds(e: StatusParser.SvcEvent): Pair<Int, Int> = e.pid to e.tgid

    fun csvHeader(): String {
        return "seq,nr,name,tgid,pid,uid,comm,pc_resolved,caller_resolved,bt_resolved,maps_snapshots,desc"
    }

    suspend fun toCsvLine(e: StatusParser.SvcEvent): String {
        val resolved = resolveEventFields(e)
        val (tgidOut, pidOut) = swappedIds(e)
        val comm = e.comm.replace("\"", "\"\"")
        val desc = e.desc.replace("\"", "\"\"")
        val maps = AddressResolver.getRecentSnapshotSummaries(e.tgid, 5).joinToString(" || ").replace("\"", "\"\"")
        return "${e.seq},${e.nr},${e.name},$tgidOut,$pidOut,${e.uid},\"$comm\",\"${resolved["pc"]}\",\"${resolved["caller"]}\",\"${resolved["bt"]}\",\"$maps\",\"$desc\""
    }

    suspend fun toJsonObject(e: StatusParser.SvcEvent): JSONObject {
        val resolved = resolveEventFields(e)
        val (tgidOut, pidOut) = swappedIds(e)
        val snapshots = AddressResolver.getRecentSnapshotSummaries(e.tgid, 5)
        return JSONObject().apply {
            put("seq", e.seq)
            put("nr", e.nr)
            put("name", e.name)
            put("tgid", tgidOut)
            put("pid", pidOut)
            put("uid", e.uid)
            put("comm", e.comm)
            put("pc", e.pc)
            put("pc_resolved", resolved["pc"])
            put("caller", e.caller)
            put("caller_resolved", resolved["caller"])
            put("fp", e.fp)
            put("sp", e.sp)
            put("bt", JSONArray(e.bt))
            put("bt_resolved", resolved["bt"])
            put("clone_fn", e.cloneFn)
            put("ret", e.ret)
            put("a0", e.a0); put("a1", e.a1); put("a2", e.a2)
            put("a3", e.a3); put("a4", e.a4); put("a5", e.a5)
            put("desc", e.desc)
            put("maps_snapshots", JSONArray(snapshots))
        }
    }
}
