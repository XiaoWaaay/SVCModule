# Android Apps Analysis: SVC Monitor (`android/`) and MX/Mamu (`MX_APP/`)

## 1) Repository-level split

This repository contains **two Android applications** with different goals:

1. **SVC Monitor app** in `android/`:
   - A Kotlin Android control UI for a kernel module (`kpm/src/svc_monitor.c`) that hooks Linux syscalls and streams events.
2. **MX/Mamu app** in `MX_APP/`:
   - A rooted Android memory scanner/modifier app (GameGuardian-like), with Kotlin UI + Rust native core (`libmamu_core.so`) over JNI.

---

## 2) SVC Monitor app (`android/`)

## 2.1 Purpose

SVC Monitor is a **syscall monitoring controller**:
- It does not implement kernel hooking itself in app code.
- It talks to a privileged KPM userspace tool (`kpatch`/`ksud`) and sends control commands (`status`, `uid`, `set_nrs`, `enable`, `disable`, `drain`, etc.).
- It displays and filters syscall events in real time.

The project README explicitly describes this control flow and command set.

## 2.2 High-level architecture

### A) UI layer (single activity, programmatic tabs)
- `MainActivity` builds a multi-tab UI **fully in Kotlin code** (no XML layouts for main pages).
- Tabs include Monitor, Filter, Events, Threads, Settings.
- It binds controls to `MainViewModel` LiveData and sends user actions (start/stop, apply presets, set UID/NR filters).

### B) ViewModel orchestration
- `MainViewModel` is the runtime coordinator:
  - Polls module status periodically.
  - Pulls event stream from binary file first, with JSON drain fallback.
  - Persists events to Room.
  - Applies filters and publishes UI-ready lists/counts.
  - Manages monitor lifecycle (`enable`/`disable`) with sequencing and polling pauses.

### C) Bridge to privileged command execution
- `KpmBridge` is the abstraction to execute module commands via `su`:
  - Detects available CLI style (`kpatch` or `ksud`).
  - Builds proper ctl command string.
  - Executes through persistent root shell when possible.
  - Parses basic success/error JSON response.

### D) Parsing and data model
- `StatusParser` parses module JSON (`status`, `drain`, simple responses).
- `BinEventParser` parses binary event records from `/data/local/tmp/svc_events.bin` for higher-throughput ingestion.

### E) Storage
- Room database stores events (`SvcEventEntity`) + FTS shadow table.
- DAO supports latest events, per-thread queries, full-text-like searches, thread stats and clone edge extraction.

## 2.3 Data flow (how it works)

1. App starts, builds UI and starts polling.
2. User picks target app -> app resolves UID from installed packages.
3. On start monitoring:
   - clear previous events/storage;
   - send `uid <n>` + optional tuning flags (`do_filp_open`, backtrace mode);
   - send `enable`.
4. Poll loop:
   - if monitoring: read binary event file chunk, parse records;
   - fallback to `drain` JSON after repeated empty binary polls;
   - periodically fetch `status` and syscall names.
5. Parsed events saved to Room and reflected to UI lists/search.
6. User can modify filters/presets/NR sets live and stop monitoring via `disable`.

## 2.4 Important implementation details

- **Resilience**: start flow logs non-fatal command failures and tries to continue toward `enable`.
- **Race avoidance**: ViewModel pauses polling during command batches.
- **Throughput strategy**: binary file path first, JSON fallback only when binary appears idle.
- **Root portability**: bridge supports APatch-style `kpatch` and KernelSU `ksud` command styles.

---

## 3) MX/Mamu app (`MX_APP/`)

## 3.1 Purpose

MX/Mamu is a **root-required memory search/edit toolkit**:
- Similar to GameGuardian style workflows (exact/fuzzy/group/refine search).
- Includes floating overlay controls for in-game live operations.
- Uses Kotlin for UI/control + Rust for high-performance search/memory operations through JNI.

## 3.2 High-level architecture

### A) Android app shell + permissions/services
- Manifest requests root-adjacent capabilities (overlay, FGS, package visibility, storage/media permissions).
- Entry begins at `PermissionSetupActivity` then main UI.
- Core live operations run in `FloatingWindowService` foreground service (floating icon/fullscreen panel).

### B) Main app layer (Kotlin)
- `MainActivity`: Compose shell + auto-start floating service logic.
- `MainViewModel`: loads system/root/driver status and observes floating window active state.
- Repositories provide root status / SELinux / driver summary.

### C) Driver and search facades (Kotlin JNI wrappers)
- `WuwaDriver`: process listing/binding, memory region query, read/write, batch R/W, driver install APIs.
- `SearchEngine`: async search APIs with shared direct `ByteBuffer` progress protocol (status/progress/found/cancel).
- `FreezeManager`: add/remove/loop frozen address writes.

### D) Rust native core
- `lib.rs` sets up JNI and auto-registers methods.
- `jni_interface/*` exposes Rust functionality to Kotlin classes.
- `core/driver_manager.rs` centralizes loaded driver, bound process, memory access mode, unified read/write path.
- `search/engine/manager.rs` runs async/cancellable searches (tokio + rayon), updates shared buffer, stores results.
- pointer scan, disassembler and freeze modules provide additional advanced tooling.

## 3.3 Data flow (how it works)

1. App process loads `libmamu_core.so` and initializes core services.
2. Initializes SearchEngine cache dirs/buffers and pointer scanner work dirs.
3. User starts floating service; overlay controllers manage tabs and actions.
4. User binds a target process through driver APIs.
5. Search starts:
   - Kotlin sends query/type/ranges via JNI.
   - Rust search manager spawns async task; parallel scans memory regions.
   - Progress is written to direct ByteBuffer.
6. Results are paged back to Kotlin (`getResults`) and can be modified/frozen.
7. Freeze manager keeps periodic writes to lock values.

## 3.4 Important implementation details

- **Performance path**: heavy logic in Rust (parallel scanning, custom result manager).
- **Cancellation model**: dual mechanism via token + shared cancel flag.
- **Memory access abstraction**: one driver manager supports multiple access modes (normal / write-through / non-cache / page-fault/physical path).
- **Operational UX**: FloatingWindowService orchestrates all main runtime controls.

---

## 4) Relationship between both Android apps

They target different low-level monitoring/manipulation domains:

- **SVC Monitor (`android/`)**
  - Focus: **syscall telemetry** from a kernel hook module.
  - Core action: configure hooks/filtering + collect syscall event stream.

- **MX/Mamu (`MX_APP/`)**
  - Focus: **process memory inspection/editing** on rooted devices.
  - Core action: bind process, scan memory, edit/freeze values, pointer scan.

Shared traits:
- both require privileged/root environment for full functionality,
- both use an Android UI layer as control plane over deeper native/system mechanisms.

---

## 5) Suggested next deep-dive steps

If you want, I can follow this with:
1. A **package-by-package class map** (every class grouped by responsibility) for both apps.
2. A **sequence diagram** of startup + runtime loops for each app.
3. A **risk/safety review** (root command surfaces, storage/logging, attack surface).
4. A **build-and-run checklist** with expected outputs and troubleshooting matrix.
