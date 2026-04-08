package com.svcmonitor.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Resolves memory addresses to library+offset using /proc/<pid>/maps.
 * Maintains per-PID snapshots with TTL and crash detection.
 * When a process crashes, the last known snapshot is still used for resolution.
 */
object AddressResolver {

    private data class MapRegion(
        val start: Long,
        val end: Long,
        val perms: String,
        val mapOffset: Long,
        val path: String
    )

    private data class MapsSnapshot(
        val tsMs: Long,
        val regions: List<MapRegion>,
        val regionCount: Int,
        val totalSize: Long
    )

    // Per-PID circular buffer (last 5 snapshots)
    private val mapsHistory = HashMap<Int, ArrayDeque<MapsSnapshot>>()
    private val mapsDisabledForPid = HashSet<Int>()
    private const val MAPS_TTL_MS = 5000L
    private const val MAX_SNAPSHOTS_PER_PID = 5

    fun getRecentSnapshotSummaries(pid: Int, limit: Int = 5): List<String> {
        if (pid <= 0) return emptyList()
        val snapshots = synchronized(mapsHistory) {
            mapsHistory[pid]?.toList().orEmpty()
        }
        if (snapshots.isEmpty()) return emptyList()
        return snapshots.takeLast(limit.coerceAtLeast(1)).reversed().map {
            "ts=${Date(it.tsMs)} regions=${it.regionCount} total_size=${it.totalSize}"
        }
    }

    /**
     * Resets tracking for a PID (e.g., when starting to monitor a new app).
     */
    fun resetForPid(pid: Int) {
        synchronized(mapsDisabledForPid) {
            mapsDisabledForPid.remove(pid)
        }
        synchronized(mapsHistory) {
            mapsHistory.remove(pid)
        }
    }

    /**
     * Resolves an address to a human-readable string: "library+offset (0xaddr)"
     * or "unmapped" if not found.
     * Falls back to the last known snapshot if the process has crashed.
     */
    suspend fun resolveAddress(pid: Int, addr: Long): String {
        if (pid <= 0 || addr == 0L) return ""
        val regions = getMapsRegions(pid) ?: return ""
        val region = findMapRegion(regions, addr) ?: return ""
        val fileOffset = (addr - region.start) + region.mapOffset
        if (region.path.isBlank() || region.path.startsWith("[")) {
            val startHex = java.lang.Long.toHexString(region.start)
            val endHex = java.lang.Long.toHexString(region.end)
            val sizeKb = ((region.end - region.start) / 1024L).coerceAtLeast(0L)
            val prot = region.perms.take(3)
            return "[anon:$startHex-$endHex]+0x${java.lang.Long.toHexString(fileOffset)}(size=${sizeKb}KB,prot=$prot)"
        }
        val name = region.path.substringAfterLast('/')
        return "$name+0x${java.lang.Long.toHexString(fileOffset)}"
    }

    /**
     * Returns "library+offset (0xaddr)" if resolved, or "0xaddr (unmapped)" otherwise.
     */
    suspend fun formatAddrSoOffset(pid: Int, addr: Long): String {
        val abs = "0x${java.lang.Long.toHexString(addr)}"
        val resolved = resolveAddress(pid, addr)
        return if (resolved.isNotEmpty()) "$resolved ($abs)" else "$abs (unmapped)"
    }



    fun exportRecentMapsSnapshots(context: Context, limitPerPid: Int = 5): File? {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val outFile = File(outDir, "svc_maps_recent_${ts}.jsonl")

        val snapshotCopy: Map<Int, List<MapsSnapshot>> = synchronized(mapsHistory) {
            mapsHistory.mapValues { (_, deque) -> deque.toList() }
        }
        if (snapshotCopy.isEmpty()) return null

        var lineCount = 0
        outFile.bufferedWriter().use { w ->
            snapshotCopy.forEach { (pid, snapshots) ->
                snapshots.takeLast(limitPerPid.coerceAtLeast(1)).forEach { snap ->
                    val obj = JSONObject()
                    obj.put("pid", pid)
                    obj.put("ts_ms", snap.tsMs)
                    obj.put("region_count", snap.regionCount)
                    obj.put("total_size", snap.totalSize)
                    val regions = JSONArray()
                    snap.regions.forEach { r ->
                        regions.put(JSONObject().apply {
                            put("start", r.start)
                            put("end", r.end)
                            put("perms", r.perms)
                            put("map_offset", r.mapOffset)
                            put("path", r.path)
                        })
                    }
                    obj.put("regions", regions)
                    w.write(obj.toString())
                    w.newLine()
                    lineCount++
                }
            }
        }
        return if (lineCount > 0) outFile else null
    }

    private suspend fun getMapsRegions(pid: Int): List<MapRegion>? {
        val now = System.currentTimeMillis()
        val history = synchronized(mapsHistory) {
            mapsHistory.getOrPut(pid) { ArrayDeque() }
        }

        val lastSnapshot = history.lastOrNull()

        // If PID is disabled (crashed), still return the last known regions (if any)
        if (synchronized(mapsDisabledForPid) { mapsDisabledForPid.contains(pid) }) {
            return lastSnapshot?.regions
        }

        // If we have a recent snapshot (within TTL), return it
        if (lastSnapshot != null && now - lastSnapshot.tsMs <= MAPS_TTL_MS) {
            return lastSnapshot.regions
        }

        // Fetch fresh maps
        val maps = withContext(Dispatchers.IO) { KpmBridge.readProcMaps(pid) }
        if (maps.isBlank()) {
            // If fetch fails but we have a snapshot, return it (stale)
            return lastSnapshot?.regions
        }

        val newRegions = parseMapsRegions(maps)
        val newCount = newRegions.size
        val newTotalSize = newRegions.sumOf { it.end - it.start }

        // Crash detection: if region count or total size decreased, assume process died
        if (lastSnapshot != null) {
            if (newCount < lastSnapshot.regionCount || newTotalSize < lastSnapshot.totalSize) {
                synchronized(mapsDisabledForPid) {
                    mapsDisabledForPid.add(pid)
                }
                android.util.Log.w("AddressResolver", "PID $pid maps shrank (${lastSnapshot.regionCount}→$newCount, size ${lastSnapshot.totalSize}→$newTotalSize). Disabling further fetches, but last snapshot remains.")
                return lastSnapshot.regions
            }
        }

        // Create new snapshot and add to history (keep last 5)
        val newSnapshot = MapsSnapshot(now, newRegions, newCount, newTotalSize)
        history.addLast(newSnapshot)
        while (history.size > MAX_SNAPSHOTS_PER_PID) {
            history.removeFirst()
        }

        return newRegions
    }

    private fun parseMapsRegions(maps: String): List<MapRegion> {
        val out = ArrayList<MapRegion>(256)
        val lines = maps.split('\n')
        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 3) continue
            val range = parts[0]
            val perms = parts[1]
            val offStr = parts[2]
            val path = if (parts.size >= 6) parts[5] else ""
            val dash = range.indexOf('-')
            if (dash <= 0) continue
            val start = range.substring(0, dash).toLongOrNull(16) ?: continue
            val end = range.substring(dash + 1).toLongOrNull(16) ?: continue
            val offset = offStr.toLongOrNull(16) ?: 0L
            out.add(MapRegion(start = start, end = end, perms = perms, mapOffset = offset, path = path))
        }
        return out
    }

    private fun findMapRegion(regions: List<MapRegion>, addr: Long): MapRegion? {
        for (r in regions) {
            if (addr >= r.start && addr < r.end) return r
        }
        return null
    }
}
