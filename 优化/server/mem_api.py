#!/usr/bin/env python3
"""
mem_api.py — Memory Analysis API for SVC Monitor PC Viewer
v2.0 — Routes aligned with mem_analyzer.html frontend

Provides:
1. Smart Maps parser & analyzer with suspicious region detection
2. Memory read/hexdump via /proc/<pid>/mem (adb shell)
3. ARM64 basic disassembler
4. String extraction and SVC #0 scanning
5. Address-to-library resolver
6. Frida script generators

Register as a Blueprint in your Flask app:
    from mem_api import mem_bp
    app.register_blueprint(mem_bp)
"""

import os
import re
import struct
import json
import time
import subprocess
from collections import defaultdict
from flask import Blueprint, request, jsonify, send_from_directory

mem_bp = Blueprint("mem", __name__)

# ═══════════════════════════════════════════════════
# Static file serving (mem_analyzer.html)
# ═══════════════════════════════════════════════════
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'static')

@mem_bp.route("/mem-analyzer")
def serve_mem_analyzer():
    return send_from_directory(STATIC_DIR, 'mem_analyzer.html')


# ═══════════════════════════════════════════════════
# 1. ADB Shell Helper
# ═══════════════════════════════════════════════════
def adb_shell(cmd, timeout=10):
    """Execute adb shell command, returns (stdout, stderr, returncode)."""
    try:
        proc = subprocess.run(
            ["adb", "shell", cmd],
            capture_output=True, text=True, timeout=timeout
        )
        return proc.stdout, proc.stderr, proc.returncode
    except subprocess.TimeoutExpired:
        return "", "timeout", -1
    except FileNotFoundError:
        return "", "adb not found", -1


def adb_shell_su(cmd, timeout=10):
    """Execute adb shell command with su."""
    return adb_shell(f"su -c '{cmd}'", timeout)


# ═══════════════════════════════════════════════════
# 2. Maps Parser & Analyzer
# ═══════════════════════════════════════════════════
def parse_maps_line(line):
    """Parse one /proc/<pid>/maps line."""
    m = re.match(
        r'([0-9a-f]+)-([0-9a-f]+)\s+'
        r'([rwxsp-]{4})\s+'
        r'([0-9a-f]+)\s+'
        r'(\S+)\s+'
        r'(\d+)\s*(.*)',
        line.strip()
    )
    if not m:
        return None
    start = int(m.group(1), 16)
    end = int(m.group(2), 16)
    perms = m.group(3)
    offset = int(m.group(4), 16)
    dev = m.group(5)
    inode = int(m.group(6))
    pathname = m.group(7).strip()
    return {
        "start": start,
        "end": end,
        "size": end - start,
        "perms": perms,
        "offset": offset,
        "dev": dev,
        "inode": inode,
        "name": pathname,
        "start_hex": f"0x{start:x}",
        "end_hex": f"0x{end:x}",
    }


def resolve_addr_from_regions(addr, regions):
    """Resolve one absolute address using parsed maps regions."""
    for r in regions:
        if r["start"] <= addr < r["end"]:
            name = r.get("name") or "[anonymous]"
            offset = addr - r["start"]
            return f"{name}+0x{offset:x} (0x{addr:x})"
    return f"0x{addr:x} (unmapped)"


def load_maps_regions(pid):
    """Load and parse /proc/<pid>/maps regions via adb."""
    out, err, rc = adb_shell_su(f"cat /proc/{pid}/maps")
    if rc != 0 or not out.strip():
        out, err, rc = adb_shell(f"cat /proc/{pid}/maps")
    if not out.strip():
        return [], err
    regions = []
    for line in out.strip().split('\n'):
        r = parse_maps_line(line)
        if r:
            regions.append(r)
    return regions, ""


def human_size(n):
    for unit in ['B', 'KB', 'MB', 'GB']:
        if n < 1024:
            return f"{n:.1f}{unit}" if unit != 'B' else f"{n}{unit}"
        n /= 1024
    return f"{n:.1f}TB"


# Suspicious pattern rules
SUSPICIOUS_RULES = [
    {"pattern": r"xjd[-_]?cache", "tag": "xjd-cache", "reason": "Known packer/VMP cache region"},
    {"pattern": r"libexec\.so", "tag": "libexec.so", "reason": "Common packed/protected library"},
    {"pattern": r"memfd:", "tag": "memfd", "reason": "Anonymous file-backed region (runtime generated code)"},
    {"pattern": r"frida", "tag": "Frida-trace", "reason": "Frida agent detected"},
    {"pattern": r"\/data\/.*\.dex", "tag": "loaded-dex", "reason": "Dynamically loaded DEX file"},
    {"pattern": r"dalvik-.*jit", "tag": "JIT-code", "reason": "ART JIT compiled code"},
    {"pattern": r"\/data\/data\/.*\/lib", "tag": "app-native", "reason": "App native library"},
]


def analyze_region(region):
    """Annotate a region with tags and suspicion markers."""
    tags = []
    reasons = []
    name = region.get("name", "")
    perms = region.get("perms", "----")

    # RWX check
    if 'r' in perms and 'w' in perms and 'x' in perms:
        tags.append("RWX")
        reasons.append("Read-Write-Execute: possible self-modifying code or JIT")

    # Anonymous executable
    if not name and 'x' in perms:
        tags.append("anon-exec")
        reasons.append("Anonymous executable region — could be unpacked code, JIT, or VMP output")

    # Anonymous large writable
    if not name and 'w' in perms and region.get("size", 0) > 1024 * 1024:
        tags.append("large-anon-rw")
        reasons.append(f"Large anonymous writable region ({human_size(region['size'])}) — possible VMP workspace")

    # Name-based rules
    for rule in SUSPICIOUS_RULES:
        if re.search(rule["pattern"], name, re.IGNORECASE):
            tags.append(rule["tag"])
            reasons.append(rule["reason"])

    region["tags"] = tags
    region["suspicious"] = len(tags) > 0
    region["reason"] = "; ".join(reasons) if reasons else ""
    return region


@mem_bp.route("/api/mem/maps/<int:pid>/smart")
def api_maps_smart(pid):
    """Smart maps analysis: parse + annotate + summarize."""
    out, err, rc = adb_shell_su(f"cat /proc/{pid}/maps")
    if rc != 0 or not out.strip():
        # Fallback without su
        out, err, rc = adb_shell(f"cat /proc/{pid}/maps")
    if not out.strip():
        return jsonify({"error": f"Cannot read maps for PID {pid}: {err}"}), 500

    regions = []
    for line in out.strip().split('\n'):
        r = parse_maps_line(line)
        if r:
            r = analyze_region(r)
            regions.append(r)

    suspicious = [r for r in regions if r["suspicious"]]

    # Group libraries
    libs = defaultdict(list)
    for r in regions:
        if r["name"] and '.so' in r["name"]:
            lib = r["name"].split('/')[-1].split('.so')[0] + '.so'
            libs[lib].append(r)

    summary = {
        "total_regions": len(regions),
        "executable_regions": sum(1 for r in regions if 'x' in r.get("perms", "")),
        "suspicious_count": len(suspicious),
        "total_mapped_size": sum(r.get("size", 0) for r in regions),
        "library_count": len(libs),
    }

    return jsonify({
        "pid": pid,
        "regions": regions,
        "suspicious": suspicious,
        "summary": summary,
        "libraries": {k: len(v) for k, v in libs.items()},
    })


# ═══════════════════════════════════════════════════
# 3. Memory Read / Hexdump
# ═══════════════════════════════════════════════════
def read_proc_mem(pid, addr, size, max_size=4096):
    """Read bytes from /proc/<pid>/mem via adb."""
    size = min(size, max_size)
    cmd = f"dd if=/proc/{pid}/mem bs=1 skip={addr} count={size} 2>/dev/null | xxd -p -c{size}"
    out, err, rc = adb_shell_su(cmd, timeout=15)
    hex_str = out.strip().replace('\n', '').replace(' ', '')
    if not hex_str:
        # Fallback: try without su
        out, err, rc = adb_shell(cmd, timeout=15)
        hex_str = out.strip().replace('\n', '').replace(' ', '')
    return hex_str


def format_hexdump(hex_str, base_addr, bytes_per_line=16):
    """Format hex string into hexdump lines [{addr, bytes/hex, ascii}]."""
    lines = []
    total_bytes = len(hex_str) // 2
    for offset in range(0, total_bytes, bytes_per_line):
        chunk_hex = hex_str[offset*2 : (offset+bytes_per_line)*2]
        byte_vals = [int(chunk_hex[i:i+2], 16) for i in range(0, len(chunk_hex), 2)]

        # Format hex with spaces
        hex_display = ' '.join(chunk_hex[i:i+2] for i in range(0, len(chunk_hex), 2))
        # Pad if short
        if len(byte_vals) < bytes_per_line:
            hex_display += '   ' * (bytes_per_line - len(byte_vals))

        # ASCII
        ascii_chars = ''.join(chr(b) if 32 <= b < 127 else '.' for b in byte_vals)

        lines.append({
            "addr": f"0x{base_addr + offset:x}",
            "hex": hex_display,
            "ascii": ascii_chars,
        })
    return lines


@mem_bp.route("/api/mem/read/<int:pid>", methods=["POST"])
def api_mem_read(pid):
    """Read memory at address. POST JSON: {address, size}."""
    data = request.get_json(silent=True) or {}
    addr_str = data.get("address", "")
    size = int(data.get("size", 256))

    if not addr_str:
        return jsonify({"error": "address required"}), 400
    try:
        addr = int(addr_str, 0)  # supports 0x prefix
    except ValueError:
        return jsonify({"error": f"Invalid address: {addr_str}"}), 400

    size = min(max(size, 16), 4096)
    hex_str = read_proc_mem(pid, addr, size)
    if not hex_str:
        return jsonify({"error": f"Cannot read memory at 0x{addr:x} (size={size})"}), 500

    lines = format_hexdump(hex_str, addr)
    return jsonify({
        "address": f"0x{addr:x}",
        "size": len(hex_str) // 2,
        "lines": lines,
        "raw_hex": hex_str,
    })


# ═══════════════════════════════════════════════════
# 4. ARM64 Disassembler
# ═══════════════════════════════════════════════════
def decode_arm64_instruction(word, addr):
    """Decode a single ARM64 instruction (basic subset)."""
    result = {"address": f"0x{addr:x}", "bytes_hex": f"{word:08x}", "mnemonic": "???", "operands": "", "comment": ""}

    # SVC #imm16
    if (word & 0xFFE0001F) == 0xD4000001:
        imm16 = (word >> 5) & 0xFFFF
        result["mnemonic"] = "SVC"
        result["operands"] = f"#{imm16:#x}"
        if imm16 == 0:
            result["comment"] = "SYSCALL"
        return result

    # BRK #imm16
    if (word & 0xFFE0001F) == 0xD4200000:
        imm16 = (word >> 5) & 0xFFFF
        result["mnemonic"] = "BRK"
        result["operands"] = f"#{imm16:#x}"
        return result

    # NOP
    if word == 0xD503201F:
        result["mnemonic"] = "NOP"
        return result

    # RET {Xn}
    if (word & 0xFFFFFC1F) == 0xD65F0000:
        rn = (word >> 5) & 0x1F
        result["mnemonic"] = "RET"
        result["operands"] = f"X{rn}" if rn != 30 else ""
        return result

    # BR Xn
    if (word & 0xFFFFFC1F) == 0xD61F0000:
        rn = (word >> 5) & 0x1F
        result["mnemonic"] = "BR"
        result["operands"] = f"X{rn}"
        result["comment"] = "indirect branch"
        return result

    # BLR Xn
    if (word & 0xFFFFFC1F) == 0xD63F0000:
        rn = (word >> 5) & 0x1F
        result["mnemonic"] = "BLR"
        result["operands"] = f"X{rn}"
        result["comment"] = "indirect call"
        return result

    # B imm26
    if (word >> 26) == 0x05:
        imm26 = word & 0x3FFFFFF
        if imm26 & 0x2000000:
            imm26 |= ~0x3FFFFFF
        target = addr + (imm26 << 2)
        result["mnemonic"] = "B"
        result["operands"] = f"0x{target & 0xFFFFFFFFFFFFFFFF:x}"
        return result

    # BL imm26
    if (word >> 26) == 0x25:
        imm26 = word & 0x3FFFFFF
        if imm26 & 0x2000000:
            imm26 |= ~0x3FFFFFF
        target = addr + (imm26 << 2)
        result["mnemonic"] = "BL"
        result["operands"] = f"0x{target & 0xFFFFFFFFFFFFFFFF:x}"
        return result

    # B.cond
    if (word & 0xFF000010) == 0x54000000:
        cond = word & 0xF
        imm19 = (word >> 5) & 0x7FFFF
        if imm19 & 0x40000:
            imm19 |= ~0x7FFFF
        target = addr + (imm19 << 2)
        cond_names = ['EQ','NE','CS','CC','MI','PL','VS','VC','HI','LS','GE','LT','GT','LE','AL','NV']
        result["mnemonic"] = f"B.{cond_names[cond]}"
        result["operands"] = f"0x{target & 0xFFFFFFFFFFFFFFFF:x}"
        return result

    # MOV Xd, Xm (ORR Xd, XZR, Xm)
    if (word & 0xFF20FC00) == 0xAA0003E0:
        rd = word & 0x1F
        rm = (word >> 16) & 0x1F
        sf = (word >> 31) & 1
        reg = 'X' if sf else 'W'
        result["mnemonic"] = "MOV"
        result["operands"] = f"{reg}{rd}, {reg}{rm}"
        return result

    # MOVZ Xd, #imm16, LSL #shift
    if (word & 0x7F800000) == 0x52800000:
        sf = (word >> 31) & 1
        hw = (word >> 21) & 3
        imm16 = (word >> 5) & 0xFFFF
        rd = word & 0x1F
        reg = 'X' if sf else 'W'
        shift = hw * 16
        result["mnemonic"] = "MOVZ" if (word >> 29) & 3 == 2 else "MOVN" if (word >> 29) & 3 == 0 else "MOVK"
        result["operands"] = f"{reg}{rd}, #{imm16:#x}"
        if shift:
            result["operands"] += f", LSL #{shift}"
        return result

    # LDR/STR (immediate, unsigned offset)
    if (word & 0x3B200C00) == 0x39000000:
        size_bits = (word >> 30) & 3
        is_load = (word >> 22) & 1
        imm12 = (word >> 10) & 0xFFF
        rn = (word >> 5) & 0x1F
        rt = word & 0x1F
        scale = size_bits
        offset = imm12 << scale
        sf = 'X' if size_bits >= 3 else 'W'
        rn_name = f"X{rn}" if rn != 31 else "SP"
        result["mnemonic"] = "LDR" if is_load else "STR"
        result["operands"] = f"{sf}{rt}, [{rn_name}, #{offset:#x}]"
        return result

    # STP/LDP (pre/post-index) simplified
    if (word & 0x7C000000) == 0x28000000:
        is_load = (word >> 22) & 1
        sf = (word >> 31) & 1
        result["mnemonic"] = "LDP" if is_load else "STP"
        rt = word & 0x1F
        rt2 = (word >> 10) & 0x1F
        rn = (word >> 5) & 0x1F
        reg = 'X' if sf else 'W'
        rn_name = f"X{rn}" if rn != 31 else "SP"
        result["operands"] = f"{reg}{rt}, {reg}{rt2}, [{rn_name}]"
        return result

    # ADD/SUB immediate
    if (word & 0x1F000000) == 0x11000000:
        sf = (word >> 31) & 1
        is_sub = (word >> 30) & 1
        sh = (word >> 22) & 1
        imm12 = (word >> 10) & 0xFFF
        rn = (word >> 5) & 0x1F
        rd = word & 0x1F
        reg = 'X' if sf else 'W'
        val = imm12 << (12 if sh else 0)
        rn_name = f"{reg}{rn}" if rn != 31 else "SP"
        rd_name = f"{reg}{rd}" if rd != 31 else "SP"
        result["mnemonic"] = "SUB" if is_sub else "ADD"
        result["operands"] = f"{rd_name}, {rn_name}, #{val:#x}"
        return result

    # CBZ/CBNZ
    if (word & 0x7E000000) == 0x34000000:
        sf = (word >> 31) & 1
        is_nz = (word >> 24) & 1
        imm19 = (word >> 5) & 0x7FFFF
        if imm19 & 0x40000:
            imm19 |= ~0x7FFFF
        rt = word & 0x1F
        target = addr + (imm19 << 2)
        reg = 'X' if sf else 'W'
        result["mnemonic"] = "CBNZ" if is_nz else "CBZ"
        result["operands"] = f"{reg}{rt}, 0x{target & 0xFFFFFFFFFFFFFFFF:x}"
        return result

    # TBZ/TBNZ
    if (word & 0x7E000000) == 0x36000000:
        is_nz = (word >> 24) & 1
        b5 = (word >> 31) & 1
        b40 = (word >> 19) & 0x1F
        bit = (b5 << 5) | b40
        imm14 = (word >> 5) & 0x3FFF
        if imm14 & 0x2000:
            imm14 |= ~0x3FFF
        rt = word & 0x1F
        target = addr + (imm14 << 2)
        result["mnemonic"] = "TBNZ" if is_nz else "TBZ"
        result["operands"] = f"X{rt}, #{bit}, 0x{target & 0xFFFFFFFFFFFFFFFF:x}"
        return result

    # ADRP
    if (word & 0x9F000000) == 0x90000000:
        rd = word & 0x1F
        immlo = (word >> 29) & 3
        immhi = (word >> 5) & 0x7FFFF
        imm = ((immhi << 2) | immlo) << 12
        if imm & (1 << 32):
            imm |= ~0xFFFFFFFF
        target = (addr & ~0xFFF) + imm
        result["mnemonic"] = "ADRP"
        result["operands"] = f"X{rd}, 0x{target & 0xFFFFFFFFFFFFFFFF:x}"
        return result

    # Fallback: show raw encoding
    result["mnemonic"] = ".word"
    result["operands"] = f"0x{word:08x}"
    return result


@mem_bp.route("/api/mem/disasm/<int:pid>", methods=["POST"])
def api_mem_disasm(pid):
    """Disassemble memory. POST JSON: {address, count}."""
    data = request.get_json(silent=True) or {}
    addr_str = data.get("address", "")
    count = int(data.get("count", 50))

    if not addr_str:
        return jsonify({"error": "address required"}), 400
    try:
        addr = int(addr_str, 0)
    except ValueError:
        return jsonify({"error": f"Invalid address: {addr_str}"}), 400

    count = min(max(count, 1), 500)
    byte_size = count * 4  # ARM64 = 4 bytes per instruction
    hex_str = read_proc_mem(pid, addr, byte_size, max_size=byte_size)
    if not hex_str:
        return jsonify({"error": f"Cannot read memory at 0x{addr:x}"}), 500

    instructions = []
    for i in range(0, len(hex_str) - 7, 8):
        word_hex = hex_str[i:i+8]
        # Little-endian: bytes are already in memory order from xxd
        byte_le = bytes.fromhex(word_hex)
        word = struct.unpack('<I', byte_le)[0]
        inst_addr = addr + (i // 2)
        inst = decode_arm64_instruction(word, inst_addr)
        inst["bytes_hex"] = word_hex
        instructions.append(inst)

    return jsonify({
        "start_addr": f"0x{addr:x}",
        "count": len(instructions),
        "instructions": instructions,
    })


# ═══════════════════════════════════════════════════
# 5. String Scanner
# ═══════════════════════════════════════════════════
@mem_bp.route("/api/mem/strings/<int:pid>", methods=["POST"])
def api_mem_strings(pid):
    """Extract strings from memory. POST JSON: {address, size, min_length}."""
    data = request.get_json(silent=True) or {}
    addr_str = data.get("address", "")
    size = int(data.get("size", 65536))
    min_length = int(data.get("min_length", 4))

    if not addr_str:
        return jsonify({"error": "address required"}), 400
    try:
        addr = int(addr_str, 0)
    except ValueError:
        return jsonify({"error": f"Invalid address: {addr_str}"}), 400

    size = min(size, 262144)  # 256KB max
    hex_str = read_proc_mem(pid, addr, size, max_size=size)
    if not hex_str:
        return jsonify({"error": f"Cannot read memory at 0x{addr:x}"}), 500

    raw = bytes.fromhex(hex_str)
    regions, _ = load_maps_regions(pid)
    strings = []
    current = ""
    current_start = 0

    for i, b in enumerate(raw):
        if 32 <= b < 127:
            if not current:
                current_start = i
            current += chr(b)
        else:
            if len(current) >= min_length:
                strings.append({
                    "addr": f"0x{addr + current_start:x}",
                    "value": current,
                    "length": len(current),
                    "library": resolve_addr_from_regions(addr + current_start, regions),
                })
            current = ""

    # Final string
    if len(current) >= min_length:
        strings.append({
            "addr": f"0x{addr + current_start:x}",
            "value": current,
            "length": len(current),
            "library": resolve_addr_from_regions(addr + current_start, regions),
        })

    return jsonify({
        "address": f"0x{addr:x}",
        "scan_size": len(raw),
        "strings": strings[:500],  # cap at 500
        "total_found": len(strings),
    })


# ═══════════════════════════════════════════════════
# 6. SVC #0 Scanner
# ═══════════════════════════════════════════════════
@mem_bp.route("/api/mem/svc_scan/<int:pid>", methods=["POST"])
def api_mem_svc_scan(pid):
    """Scan for SVC #0 instructions. POST JSON: {address, size, context}."""
    data = request.get_json(silent=True) or {}
    addr_str = data.get("address", "")
    size = int(data.get("size", 65536))
    ctx_count = int(data.get("context", 5))

    if not addr_str:
        return jsonify({"error": "address required"}), 400
    try:
        addr = int(addr_str, 0)
    except ValueError:
        return jsonify({"error": f"Invalid address: {addr_str}"}), 400

    size = min(size, 262144)
    hex_str = read_proc_mem(pid, addr, size, max_size=size)
    if not hex_str:
        return jsonify({"error": f"Cannot read memory at 0x{addr:x}"}), 500

    # SVC #0 = 0xD4000001 in little-endian = bytes 01 00 00 d4
    SVC_LE = "010000d4"
    sites = []
    raw = bytes.fromhex(hex_str)
    regions, _ = load_maps_regions(pid)

    # Scan aligned to 4 bytes
    for offset in range(0, len(raw) - 3, 4):
        chunk = hex_str[offset*2 : offset*2 + 8]
        if chunk == SVC_LE:
            svc_addr = addr + offset

            # Get context instructions around this SVC
            context_insts = []
            ctx_start = max(0, offset - ctx_count * 4)
            ctx_end = min(len(raw), offset + (ctx_count + 1) * 4)
            for ci in range(ctx_start, ctx_end, 4):
                if ci + 4 > len(raw):
                    break
                word_hex = hex_str[ci*2 : ci*2 + 8]
                byte_le = bytes.fromhex(word_hex)
                word = struct.unpack('<I', byte_le)[0]
                inst = decode_arm64_instruction(word, addr + ci)
                inst["bytes_hex"] = word_hex
                inst["is_target"] = (ci == offset)
                context_insts.append(inst)

            sites.append({
                "address": f"0x{svc_addr:x}",
                "offset_in_region": offset,
                "library": resolve_addr_from_regions(svc_addr, regions),
                "context": context_insts,
            })

    return jsonify({
        "address": f"0x{addr:x}",
        "scan_size": len(raw),
        "sites": sites,
    })


# ═══════════════════════════════════════════════════
# 7. Address Resolver
# ═══════════════════════════════════════════════════
@mem_bp.route("/api/mem/resolve/<int:pid>/<addr_str>")
def api_mem_resolve(pid, addr_str):
    """Resolve address to library/region."""
    try:
        addr = int(addr_str, 0)
    except ValueError:
        return jsonify({"error": f"Invalid address: {addr_str}"}), 400

    out, err, rc = adb_shell_su(f"cat /proc/{pid}/maps")
    if not out.strip():
        out, err, rc = adb_shell(f"cat /proc/{pid}/maps")
    if not out.strip():
        return jsonify({"error": "Cannot read maps"}), 500

    for line in out.strip().split('\n'):
        r = parse_maps_line(line)
        if r and r["start"] <= addr < r["end"]:
            return jsonify({
                "region": {
                    "name": r["name"] or "[anonymous]",
                    "start": r["start_hex"],
                    "end": r["end_hex"],
                    "perms": r["perms"],
                    "offset_in_region": f"0x{addr - r['start']:x}",
                    "size": r["size"],
                }
            })

    return jsonify({"region": None, "error": "Address not found in any mapped region"})


@mem_bp.route("/api/mem/events/normalize/<int:pid>", methods=["POST"])
def api_mem_events_normalize(pid):
    """
    Normalize incoming event list with minimal fallback resolving.
    If *_resolved fields are present, keep them.
    If missing/blank, resolve from current /proc/<pid>/maps.
    """
    data = request.get_json(silent=True) or {}
    events = data.get("events", [])
    if not isinstance(events, list):
        return jsonify({"error": "events must be a list"}), 400

    out, err, rc = adb_shell_su(f"cat /proc/{pid}/maps")
    if rc != 0 or not out.strip():
        out, err, rc = adb_shell(f"cat /proc/{pid}/maps")
    if not out.strip():
        return jsonify({"error": f"Cannot read maps for PID {pid}: {err}"}), 500

    regions = []
    for line in out.strip().split('\n'):
        r = parse_maps_line(line)
        if r:
            regions.append(r)

    normalized = []
    for e in events:
        if not isinstance(e, dict):
            continue
        item = dict(e)

        pc_res = str(item.get("pc_resolved", "") or "").strip()
        if not pc_res:
            pc = item.get("pc")
            if isinstance(pc, int):
                item["pc_resolved"] = resolve_addr_from_regions(pc, regions)

        caller_res = str(item.get("caller_resolved", "") or "").strip()
        if not caller_res:
            caller = item.get("caller")
            if isinstance(caller, int):
                item["caller_resolved"] = resolve_addr_from_regions(caller, regions)

        bt_res = str(item.get("bt_resolved", "") or "").strip()
        if not bt_res:
            bt = item.get("bt", [])
            if isinstance(bt, list):
                resolved_bt = []
                for a in bt:
                    if isinstance(a, int):
                        resolved_bt.append(resolve_addr_from_regions(a, regions))
                item["bt_resolved"] = " ".join(resolved_bt)

        normalized.append(item)

    return jsonify({
        "pid": pid,
        "count": len(normalized),
        "events": normalized
    })


# ═══════════════════════════════════════════════════
# 8. Pattern Search
# ═══════════════════════════════════════════════════
@mem_bp.route("/api/mem/search/<int:pid>", methods=["POST"])
def api_mem_search(pid):
    """Search for hex pattern. POST JSON: {address, size, pattern}."""
    data = request.get_json(silent=True) or {}
    addr_str = data.get("address", "")
    size = int(data.get("size", 65536))
    pattern = data.get("pattern", "").replace(" ", "").lower()

    if not addr_str or not pattern:
        return jsonify({"error": "address and pattern required"}), 400
    try:
        addr = int(addr_str, 0)
    except ValueError:
        return jsonify({"error": f"Invalid address: {addr_str}"}), 400

    size = min(size, 262144)
    hex_str = read_proc_mem(pid, addr, size, max_size=size)
    if not hex_str:
        return jsonify({"error": f"Cannot read memory at 0x{addr:x}"}), 500

    matches = []
    pos = 0
    while True:
        idx = hex_str.find(pattern, pos)
        if idx == -1:
            break
        byte_offset = idx // 2
        matches.append({
            "address": f"0x{addr + byte_offset:x}",
            "offset": byte_offset,
        })
        pos = idx + 2
        if len(matches) >= 100:
            break

    return jsonify({
        "pattern": pattern,
        "scan_size": len(hex_str) // 2,
        "matches": matches,
        "total_found": len(matches),
    })


# ═══════════════════════════════════════════════════
# Detection Reverser (dr_) API — kpmctl bridge
# ═══════════════════════════════════════════════════

def kpmctl(cmd, timeout=10):
    """Execute kpmctl command via adb shell su."""
    full_cmd = f'kpmctl svc_monitor {cmd}'
    out, err, rc = adb_shell_su(full_cmd, timeout)
    return out.strip(), err.strip(), rc


@mem_bp.route("/api/dr/enable/<int:pid>", methods=["POST"])
def api_dr_enable(pid):
    """Enable detect_reverser for target PID."""
    data = request.get_json(silent=True) or {}
    tids = data.get("tids", [])

    out, err, rc = kpmctl(f"dr_enable {pid}")
    results = {"enable": out}

    # Optionally add specific TIDs
    for tid in tids:
        t_out, _, _ = kpmctl(f"dr_add_tid {tid}")
        results[f"add_tid_{tid}"] = t_out

    # Apply config options if provided
    config = data.get("config", {})
    for key, val in config.items():
        val_str = "on" if val else "off"
        c_out, _, _ = kpmctl(f"dr_config {key} {val_str}")
        results[f"config_{key}"] = c_out

    return jsonify({"success": rc == 0, "results": results})


@mem_bp.route("/api/dr/disable", methods=["POST"])
def api_dr_disable():
    """Disable detect_reverser."""
    out, err, rc = kpmctl("dr_enable 0")
    return jsonify({"success": True, "output": out})


@mem_bp.route("/api/dr/needles", methods=["GET"])
def api_dr_needles():
    """Get captured detection strings (needles)."""
    out, err, rc = kpmctl("dr_needles", timeout=15)
    if rc != 0:
        return jsonify({"error": err or "kpmctl failed", "raw": out}), 500

    # Parse the dr_needles output into structured data
    needles = []
    current = None
    for line in out.split('\n'):
        line = line.strip()
        if not line or line.startswith('===') or line.startswith('---'):
            continue

        # Match: [0] "frida"
        m = re.match(r'\[(\d+)\]\s+"(.+)"', line)
        if m:
            if current:
                needles.append(current)
            current = {
                "index": int(m.group(1)),
                "value": m.group(2),
                "hits": 0,
                "via": "",
                "addr": "",
                "pc": "",
                "lr": "",
                "tid": 0,
                "comm": "",
                "syscall_nr": 0,
            }
            continue

        # Match detail line: hits=23  via=register(x1)  addr=0x7a1234abcd
        if current and line.startswith("hits="):
            parts = re.findall(r'(\w+)=([^\s]+)', line)
            for k, v in parts:
                if k == "hits":
                    current["hits"] = int(v)
                elif k == "via":
                    current["via"] = v
                elif k == "addr":
                    current["addr"] = v
                elif k == "pc":
                    current["pc"] = v
                elif k == "lr":
                    current["lr"] = v
                elif k == "tid":
                    current["tid"] = int(v)
                elif k == "comm":
                    current["comm"] = v
                elif k == "nr":
                    current["syscall_nr"] = int(v)
            continue

        # Continuation detail lines (pc=... lr=... tid=... etc.)
        if current:
            parts = re.findall(r'(\w+)=([^\s]+)', line)
            for k, v in parts:
                if k == "pc":
                    current["pc"] = v
                elif k == "lr":
                    current["lr"] = v
                elif k == "tid":
                    current["tid"] = int(v)
                elif k == "comm":
                    current["comm"] = v
                elif k == "nr":
                    current["syscall_nr"] = int(v)

    if current:
        needles.append(current)

    return jsonify({
        "needles": needles,
        "total": len(needles),
        "raw": out,
    })


@mem_bp.route("/api/dr/status", methods=["GET"])
def api_dr_status():
    """Get detect_reverser status."""
    out, err, rc = kpmctl("dr_status")
    if rc != 0:
        return jsonify({"error": err or "kpmctl failed"}), 500

    # Parse key=value pairs
    status = {}
    for line in out.split('\n'):
        line = line.strip()
        m = re.match(r'(\w+):\s*(.+)', line)
        if m:
            key = m.group(1)
            val = m.group(2).strip()
            # Try to convert numeric values
            try:
                val = int(val)
            except (ValueError, TypeError):
                pass
            status[key] = val

    return jsonify({"status": status, "raw": out})


@mem_bp.route("/api/dr/dump/<int:idx>", methods=["GET"])
def api_dr_dump(idx):
    """Get a specific context dump entry."""
    out, err, rc = kpmctl(f"dr_dump {idx}")
    if rc != 0:
        return jsonify({"error": err or "kpmctl failed"}), 500

    # Parse context dump
    dump = {
        "index": idx,
        "raw": out,
        "registers": [],
        "stack_strings": [],
        "meta": {},
    }

    section = "meta"
    for line in out.split('\n'):
        line = line.strip()
        if not line or line.startswith('==='):
            continue

        if 'Registers with strings' in line:
            section = "regs"
            continue
        if 'Stack strings' in line:
            section = "stack"
            continue

        if section == "meta":
            # time=... pid=... tid=... etc.
            parts = re.findall(r'(\w+)=([^\s]+)', line)
            for k, v in parts:
                try:
                    dump["meta"][k] = int(v)
                except ValueError:
                    dump["meta"][k] = v

        elif section == "regs":
            # x1 = 0x7a1234abcd → "frida"
            m = re.match(r'(x\d+)\s*=\s*(0x[0-9a-f]+)\s*→\s*"(.+)"', line)
            if m:
                dump["registers"].append({
                    "reg": m.group(1),
                    "addr": m.group(2),
                    "value": m.group(3),
                })

        elif section == "stack":
            # [sp+0x48] → 0x7b00001234 → "linjector"
            m = re.match(r'\[sp\+(0x[0-9a-f]+)\]\s*→\s*(0x[0-9a-f]+)\s*→\s*"(.+)"', line)
            if m:
                dump["stack_strings"].append({
                    "offset": m.group(1),
                    "addr": m.group(2),
                    "value": m.group(3),
                })

    return jsonify(dump)


@mem_bp.route("/api/dr/config", methods=["POST"])
def api_dr_config():
    """Update detect_reverser configuration."""
    data = request.get_json(silent=True) or {}
    results = {}
    for key, val in data.items():
        if isinstance(val, bool):
            val_str = "on" if val else "off"
        else:
            val_str = str(val)
        out, _, _ = kpmctl(f"dr_config {key} {val_str}")
        results[key] = out
    return jsonify({"success": True, "results": results})


@mem_bp.route("/api/dr/clear", methods=["POST"])
def api_dr_clear():
    """Clear all captured data."""
    out, err, rc = kpmctl("dr_clear")
    return jsonify({"success": True, "output": out})


@mem_bp.route("/api/dr/add_tid", methods=["POST"])
def api_dr_add_tid():
    """Add a TID to monitor."""
    data = request.get_json(silent=True) or {}
    tid = data.get("tid")
    if not tid:
        return jsonify({"error": "tid required"}), 400
    out, err, rc = kpmctl(f"dr_add_tid {tid}")
    return jsonify({"success": rc == 0, "output": out})


@mem_bp.route("/api/dr/frida_patch", methods=["POST"])
def api_dr_frida_patch():
    """Generate Frida script to patch detection based on captured needles."""
    data = request.get_json(silent=True) or {}
    needles = data.get("needles", [])
    pc_addr = data.get("pc", "")
    patch_mode = data.get("mode", "nop_return")

    script_lines = [
        "// Auto-generated by Detection Reverser",
        "// Patches the shell's detection function",
        f"// Detection PC: {pc_addr}",
        f"// Mode: {patch_mode}",
        "",
    ]

    if patch_mode == "nop_return":
        script_lines += [
            f'var detectFunc = ptr("{pc_addr}");',
            'Interceptor.attach(detectFunc, {',
            '    onEnter: function(args) {',
            '        console.log("[DR] Detection function called, forcing return 0");',
            '        this.context.x0 = ptr(0);  // return NULL/false',
            '    }',
            '});',
        ]
    elif patch_mode == "replace_needle":
        script_lines += [
            "// Replace needle strings in memory so detection never matches",
        ]
        for n in needles:
            val = n.get("value", "")
            addr = n.get("addr", "0x0")
            if val and addr != "0x0":
                safe_val = val.replace('"', '\\"')
                replacement = 'X' * len(val)
                script_lines += [
                    f'// Overwrite "{safe_val}" at {addr}',
                    f'try {{',
                    f'    Memory.writeUtf8String(ptr("{addr}"), "{replacement}");',
                    f'    console.log("[DR] Patched \\"{safe_val}\\" → \\"{replacement}\\"");',
                    f'}} catch(e) {{',
                    f'    console.log("[DR] Failed to patch {addr}: " + e);',
                    f'}}',
                    '',
                ]
    elif patch_mode == "hook_strcmp":
        script_lines += [
            "// Hook the custom memmem/strstr to always return NULL",
            f'var searchFunc = ptr("{pc_addr}");',
            'Interceptor.replace(searchFunc, new NativeCallback(function(haystack, haystackLen, needle, needleLen) {',
            '    var needleStr = needle.readUtf8String();',
            '    console.log("[DR] Search intercepted: \\"" + needleStr + "\\"");',
            '    return ptr(0);  // Always return "not found"',
            '}, "pointer", ["pointer", "int", "pointer", "int"]));',
        ]

    script = '\n'.join(script_lines)
    return jsonify({"script": script, "mode": patch_mode, "needle_count": len(needles)})


# ═══════════════════════════════════════════════════
# Health check
# ═══════════════════════════════════════════════════
@mem_bp.route("/api/mem/health")
def api_mem_health():
    out, err, rc = adb_shell("echo ok")
    adb_ok = "ok" in out
    return jsonify({
        "status": "ok" if adb_ok else "adb_disconnected",
        "adb": adb_ok,
        "timestamp": time.time(),
    })
