package com.svcmonitor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * StatusParser v8.0 — Parse JSON responses from KPM module.
 */
object StatusParser {

    // ===== Data classes =====

    data class ModuleStatus(
        val ok: Boolean,
        val version: String = "",
        val enabled: Boolean = false,
        val targetUid: Int = -1,
        val hooksInstalled: Int = 0,
        val nrsLogging: Int = 0,
        val eventsTotal: Int = 0,
        val eventsBuffered: Int = 0,
        val tier2: Boolean = false,
        val loggingNrs: List<Int> = emptyList(),
        val nrCount: Int = 0,
        val nrList: List<Int> = emptyList(),
        val hooks: List<HookInfo> = emptyList(),
        val error: String = ""
    )

    data class HookInfo(
        val nr: Int,
        val name: String,
        val method: String
    )

    data class SvcEvent(
        val seq: Long = 0,
        val nr: Int,
        val name: String,
        val tgid: Int,
        val pid: Int,
        val uid: Int,
        val comm: String,
        val pc: Long = 0,
        val caller: Long = 0,
        val fp: Long = 0,
        val sp: Long = 0,
        val bt: List<Long> = emptyList(),
        val cloneFn: Long = 0,
        val ret: Long = 0,
        val a0: Long, val a1: Long, val a2: Long,
        val a3: Long, val a4: Long, val a5: Long,
        val desc: String
    )

    data class DrainResult(
        val ok: Boolean,
        val count: Int = 0,
        val total: Int = 0,
        val events: List<SvcEvent> = emptyList(),
        val error: String = ""
    )

    data class SimpleResult(
        val ok: Boolean,
        val error: String = ""
    )

    // ===== Parsers =====

    fun parseStatus(json: String): ModuleStatus {
        return try {
            val j = JSONObject(json)
            if (!j.optBoolean("ok", false)) {
                return ModuleStatus(false, error = j.optString("error", "unknown"))
            }

            val loggingNrs = mutableListOf<Int>()
            val nrsArr = j.optJSONArray("logging_nrs")
            if (nrsArr != null) {
                for (i in 0 until nrsArr.length()) {
                    loggingNrs.add(nrsArr.getInt(i))
                }
            }

            val hooks = mutableListOf<HookInfo>()
            val hooksArr = j.optJSONArray("hooks")
            if (hooksArr != null) {
                for (i in 0 until hooksArr.length()) {
                    val h = hooksArr.getJSONObject(i)
                    hooks.add(HookInfo(
                        nr = h.optInt("nr"),
                        name = h.optString("name", ""),
                        method = h.optString("method", "")
                    ))
                }
            }
            updateDynamicNrNames(hooks)

            ModuleStatus(
                ok = true,
                version = j.optString("version", ""),
                enabled = j.optBoolean("enabled", false),
                targetUid = j.optInt("target_uid", -1),
                hooksInstalled = j.optInt("hooks_installed", 0),
                nrsLogging = j.optInt("nrs_logging", 0),
                eventsTotal = j.optInt("events_total", 0),
                eventsBuffered = j.optInt("events_buffered", 0),
                tier2 = j.optBoolean("tier2", false),
                loggingNrs = loggingNrs,
                nrCount = loggingNrs.size,
                nrList = loggingNrs,
                hooks = hooks
            )
        } catch (e: Exception) {
            ModuleStatus(false, error = "Parse error: ${e.message}")
        }
    }

    fun parseDrain(json: String): DrainResult {
        return try {
            val j = JSONObject(json)
            if (!j.optBoolean("ok", false)) {
                return DrainResult(false, error = j.optString("error", ""))
            }

            val events = mutableListOf<SvcEvent>()
            val arr = j.optJSONArray("events")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val bt = ArrayList<Long>(3)
                    val btArr = e.optJSONArray("bt")
                    if (btArr != null) {
                        for (k in 0 until btArr.length()) {
                            bt.add(btArr.optLong(k, 0))
                        }
                    }
                    events.add(SvcEvent(
                        seq = e.optLong("seq", 0),
                        nr = e.optInt("nr"),
                        name = e.optString("name", ""),
                        tgid = e.optInt("tgid", e.optInt("pid", 0)),
                        pid = e.optInt("pid", e.optInt("tid", e.optInt("tgid", 0))),
                        uid = e.optInt("uid"),
                        comm = e.optString("comm", ""),
                        pc = e.optLong("pc", 0),
                        caller = e.optLong("caller", 0),
                        fp = e.optLong("fp", 0),
                        sp = e.optLong("sp", 0),
                        bt = bt,
                        cloneFn = e.optLong("clone_fn", 0),
                        ret = e.optLong("ret", 0),
                        a0 = e.optLong("a0"), a1 = e.optLong("a1"),
                        a2 = e.optLong("a2"), a3 = e.optLong("a3"),
                        a4 = e.optLong("a4"), a5 = e.optLong("a5"),
                        desc = e.optString("desc", "")
                    ))
                }
            }

            DrainResult(
                ok = true,
                count = j.optInt("count", 0),
                total = j.optInt("total", 0),
                events = events
            )
        } catch (e: Exception) {
            DrainResult(false, error = "Parse error: ${e.message}")
        }
    }

    fun parseEventLines(text: String): List<SvcEvent> {
        val out = ArrayList<SvcEvent>()
        val lines = text.split('\n')
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("{")) continue
            try {
                val e = JSONObject(line)
                val bt = ArrayList<Long>(3)
                val btArr = e.optJSONArray("bt")
                if (btArr != null) {
                    for (k in 0 until btArr.length()) {
                        bt.add(btArr.optLong(k, 0))
                    }
                }
                out.add(SvcEvent(
                    seq = e.optLong("seq", 0),
                    nr = e.optInt("nr"),
                    name = e.optString("name", ""),
                    tgid = e.optInt("tgid", e.optInt("pid", 0)),
                    pid = e.optInt("pid", e.optInt("tid", e.optInt("tgid", 0))),
                    uid = e.optInt("uid"),
                    comm = e.optString("comm", ""),
                    pc = e.optLong("pc", 0),
                    caller = e.optLong("caller", 0),
                    fp = e.optLong("fp", 0),
                    sp = e.optLong("sp", 0),
                    bt = bt,
                    cloneFn = e.optLong("clone_fn", 0),
                    ret = e.optLong("ret", 0),
                    a0 = e.optLong("a0"), a1 = e.optLong("a1"),
                    a2 = e.optLong("a2"), a3 = e.optLong("a3"),
                    a4 = e.optLong("a4"), a5 = e.optLong("a5"),
                    desc = e.optString("desc", "")
                ))
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun parseSimple(json: String): SimpleResult {
        return try {
            val j = JSONObject(json)
            SimpleResult(
                ok = j.optBoolean("ok", false),
                error = j.optString("error", "")
            )
        } catch (e: Exception) {
            SimpleResult(false, error = "Parse error: ${e.message}")
        }
    }

    // ===== Syscall categories for UI =====

    data class SyscallEntry(val nr: Int, val name: String, val description: String)
    data class SyscallCategory(val name: String, val icon: String, val syscalls: List<SyscallEntry>)

    data class Preset(val id: String, val name: String, val description: String)

    val presets = listOf(
        Preset("re_basic", "re_basic", "RE Basic"),
        Preset("re_full", "re_full", "RE Full"),
        Preset("file", "file", "File Monitor"),
        Preset("net", "net", "Network Monitor"),
        Preset("proc", "proc", "Process Monitor"),
        Preset("mem", "mem", "Memory Monitor"),
        Preset("security", "security", "Security Audit"),
        Preset("all", "all", "Enable All")
    )

    val categories = listOf(
        SyscallCategory("File Operations", "📁", listOf(
            SyscallEntry(56, "openat", "Open file"),
            SyscallEntry(57, "close", "Close file descriptor"),
            SyscallEntry(48, "faccessat", "Check file access"),
            SyscallEntry(35, "unlinkat", "Delete file"),
            SyscallEntry(78, "readlinkat", "Read symlink"),
            SyscallEntry(61, "getdents64", "Read directory"),
            SyscallEntry(63, "read", "Read data"),
            SyscallEntry(64, "write", "Write data"),
            SyscallEntry(79, "newfstatat", "Get file status"),
            SyscallEntry(291, "statx", "Extended file status"),
            SyscallEntry(276, "renameat2", "Rename file"),
            SyscallEntry(34, "mkdirat", "Create directory")
        )),
        SyscallCategory("Process Management", "⚙", listOf(
            SyscallEntry(220, "clone", "Create process/thread"),
            SyscallEntry(435, "clone3", "Create process/thread(new)"),
            SyscallEntry(221, "execve", "Execute program"),
            SyscallEntry(281, "execveat", "Execute program(extended)"),
            SyscallEntry(93, "exit", "Exit process"),
            SyscallEntry(94, "exit_group", "Exit thread group"),
            SyscallEntry(260, "wait4", "Wait child process"),
            SyscallEntry(167, "prctl", "Process control"),
            SyscallEntry(117, "ptrace", "Process tracing")
        )),
        SyscallCategory("Memory Management", "🧠", listOf(
            SyscallEntry(222, "mmap", "Memory mapping"),
            SyscallEntry(226, "mprotect", "Change memory protection"),
            SyscallEntry(215, "munmap", "Unmap memory"),
            SyscallEntry(214, "brk", "Adjust heap size"),
            SyscallEntry(232, "mincore", "Query page residency"),
            SyscallEntry(233, "madvise", "Memory usage advice"),
            SyscallEntry(279, "memfd_create", "Create anonymous file"),
            SyscallEntry(270, "process_vm_readv", "Read process memory"),
            SyscallEntry(271, "process_vm_writev", "Write process memory")
        )),
        SyscallCategory("Network Communication", "🌐", listOf(
            SyscallEntry(198, "socket", "Create socket"),
            SyscallEntry(200, "bind", "Bind address"),
            SyscallEntry(201, "listen", "Listen connection"),
            SyscallEntry(203, "connect", "Start connection"),
            SyscallEntry(202, "accept", "Accept connection"),
            SyscallEntry(242, "accept4", "Accept connection(extended)"),
            SyscallEntry(206, "sendto", "Send data"),
            SyscallEntry(207, "recvfrom", "Receive data")
        )),
        SyscallCategory("Signal Handling", "📡", listOf(
            SyscallEntry(129, "kill", "Send signal"),
            SyscallEntry(131, "tgkill", "Send thread signal"),
            SyscallEntry(134, "rt_sigaction", "Set signal handler")
        )),
        SyscallCategory("Security Related", "🔒", listOf(
            SyscallEntry(277, "seccomp", "Seccomp mode"),
            SyscallEntry(268, "setns", "Switch namespace"),
            SyscallEntry(97, "unshare", "Unshare"),
            SyscallEntry(280, "bpf", "BPF operations")
        )),
        SyscallCategory("Tier2 Extensions", "➕", listOf(
            SyscallEntry(29, "ioctl", "Device control"),
            SyscallEntry(62, "lseek", "File seek"),
            SyscallEntry(65, "readv", "Scatter read"),
            SyscallEntry(66, "writev", "Gather write"),
            SyscallEntry(25, "fcntl", "File control"),
            SyscallEntry(71, "sendfile", "File transfer"),
            SyscallEntry(211, "sendmsg", "Send message"),
            SyscallEntry(212, "recvmsg", "Receive message"),
            SyscallEntry(208, "setsockopt", "Set socket option"),
            SyscallEntry(209, "getsockopt", "Get socket option"),
            SyscallEntry(40, "mount", "Mount filesystem"),
            SyscallEntry(39, "umount2", "Unmount filesystem"),
            SyscallEntry(261, "prlimit64", "Resource limit"),
            SyscallEntry(90, "capget", "Get capabilities"),
            SyscallEntry(91, "capset", "Set capabilities"),
            SyscallEntry(146, "setuid", "Set user ID"),
            SyscallEntry(144, "setgid", "Set group ID"),
            SyscallEntry(273, "finit_module", "Load kernel module (fd)"),
            SyscallEntry(105, "init_module", "Load kernel module"),
            SyscallEntry(106, "delete_module", "Unload kernel module")
        ))
    )

    private val dynamicNrNameMap = HashMap<Int, String>()

    private val nrNameMap: Map<Int, String> by lazy {
        val m = HashMap<Int, String>()
        for (cat in categories) {
            for (s in cat.syscalls) {
                m[s.nr] = s.name
            }
        }
        m
    }

    fun nrToName(nr: Int): String = dynamicNrNameMap[nr] ?: nrNameMap[nr] ?: "nr$nr"

    private val nrCategoryMap: Map<Int, String> by lazy {
        val m = HashMap<Int, String>()
        for (cat in categories) {
            for (s in cat.syscalls) {
                m[s.nr] = cat.name
            }
        }
        m
    }

    fun syscallCategory(nr: Int): String = nrCategoryMap[nr] ?: "-"

    internal fun updateDynamicNrNames(hooks: List<HookInfo>) {
        for (h in hooks) {
            if (h.nr >= 0 && h.name.isNotBlank()) {
                dynamicNrNameMap[h.nr] = h.name.removePrefix("sys_")
            }
        }
    }

    fun parseSysnames(raw: String): Boolean {
        return try {
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return false
            val arr = root.optJSONArray("sysnames") ?: return false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val nr = o.optInt("nr", -1)
                val name = o.optString("name", "")
                if (nr >= 0 && name.isNotBlank()) {
                    dynamicNrNameMap[nr] = name.removePrefix("sys_")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
