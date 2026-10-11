#!/usr/bin/env python3
"""Phase 2 exit criterion: compare what the port read (report.json) with what the Flutter 2.15.x read
of its own data (expected.json), against an independent census of the device (census.json).

    compare.py DIR            exit 0 when everything matches
    compare.py DIR --negative also mutate every compared datum of expected.json, one at a time: each
                              mutation must be caught (exit 1 if one is not)

Four checks, all needed (external review of the port plan, 2026-10-10, finding 1):
1. ORACLE: each family the port read equals the 2.15.x's reading — preferences (types and values),
   recents, trash, editor history, extractions, every vault file (decrypted by the port's own path), every
   `.rftvault` restored, and the two lockouts recomputed by the 2.15.3 rule at the port's own instants.
2. CENSUS: every file of the sandbox and of the shared areas, every key of the preferences file, as the
   device listed them (run-as find), is classified read / purged / ignored with a reason — an unknown one
   fails — and everything classified "read" is found in the port's report. Data absent on BOTH sides
   cannot pass: the census is not produced by either of them.
3. PRESENCE: the states the scenario must contain are there (a recent with microseconds, a favourite,
   an accented and an emoji path, a folder in the trash, the D6 history collision...).
4. NEGATIVE CONTROL (--negative): one mutation per compared datum, each caught.
"""

from __future__ import annotations

import copy
import json
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PACKAGE = "com.readfilestech.read_files_tech"
SHARED = "/storage/emulated/0"

# ───────────────────────────── the 2.15.3 lockout rule (vault_lockout.dart) ─────────────────────────────


def lockout_delay_ms(fails: int) -> int:
    if fails < 5:
        return 0
    minutes = [1, 2, 4, 8, 16, 30]
    return minutes[min(max(fails - 5, 0), len(minutes) - 1)] * 60 * 1000


def lockout_remaining_ms(fails, wall_now, mono_now, wall_deadline, mono_deadline, mono_armed_at):
    delay = lockout_delay_ms(fails)
    if delay == 0:
        return 0
    mono_remaining = mono_deadline - mono_now
    mono_this_boot = (mono_armed_at is None or mono_now >= mono_armed_at) and mono_remaining <= delay
    wall_remaining = wall_deadline - wall_now
    wall_trusted = wall_remaining <= delay
    mono = mono_remaining if mono_this_boot else 0
    wall = wall_remaining if (wall_trusted or not mono_this_boot) else 0
    return max(mono, wall, 0)


LOCKOUT_KEYS = {
    "unlock": ("vault_unlock_fails", "vault_lockout_until_ms", "vault_lockout_until_elapsed", "vault_lockout_armed_elapsed"),
    "backup": ("vault_backup_fails", "vault_backup_lockout_until_ms", "vault_backup_lockout_until_elapsed", "vault_backup_lockout_armed_elapsed"),
}
REPAIR_KEYS = ("vault_zero_key_repair_v1", "vault_zero_key_repair_attempts", "vault_zero_key_repair_current",
               "vault_zero_key_repair_skipped", "vault_zero_key_repair_report", "vault_zero_key_repair_unexamined")

# ───────────────────────────── census rules ─────────────────────────────
# (regex on the path, class, reason). First match wins; no match = failure.

SANDBOX_RULES = [
    (r"^\.$|^\./(app_flutter|cache|files|shared_prefs)$", "ignored", "a folder of the sandbox itself"),
    (r"^\./code_cache(/.*)?$", "ignored", "the engine's shader cache; Android empties code_cache at an update (observed)"),
    (r"^\./app_flutter/flutter_assets(/.*)?$|^\./app_flutter/res_timestamp-\d+-\d+$", "ignored",
     "the Flutter engine of a DEBUG build (kernel, snapshots); a release 2.15.x has none"),
    (r"^\./files/profileInstalled$", "ignored", "androidx.profileinstaller's marker, the port's own library rewrites it"),
    (r"^\./shared_prefs/FlutterSharedPreferences\.xml$", "read", "preferences (D-003, D-018)"),
    (r"^\./app_flutter/vault$|^\./app_flutter/vault/_check\.enc$", "read", "the vault and its sentinel"),
    (r"^\./app_flutter/vault/.+\.enc$", "read", "a vault file"),
    (r"^\./app_flutter/vault/.+\.enc\.\d+-[0-9a-f]+\.tmp$", "ignored",
     "the temporary of an interrupted write (D24): never listed as a vault file, never opened"),
    (r"^\./app_flutter/history$|^\./app_flutter/history/.+\.bak$", "read", "the editor's old history (D-021)"),
    (r"^\./app_flutter/[^/]+_extracted(/.*)?$", "read", "an old \"Extract all\" folder (D-022)"),
    (r"^\./cache/.+$", "purged", "the cache the 2.15.x left, plain copies included: emptied at the port's first start "
                                 "(StartupCleanup; plan, finding 5)"),
]
# Plain copies a full seed must leave in the cache: each, absent after the port's first start.
CACHE_RESIDUES = [r"^\./cache/vault_decrypt/.+/.+", r"^\./cache/share_plus/.+", r"^\./cache/file_picker/.+/.+",
                  r"^\./cache/image_picker[^/]+$", r"^\./cache/[^/]+_no_exif[^/]*$", r"^\./cache/[^/]+_signe[^/]*$",
                  r"^\./cache/Facture été\.pdf$"]
# On the full path: the census line `area|type|size|./relative` made `area/relative` (shared_path).
SHARED_RULES = [
    (rf"^{SHARED}/RFT seed(/.*)?$", "ignored", "the seed's own user files (what recents, trash and history point to)"),
    (rf"^{SHARED}/\.RFT_Corbeille(/items|/meta)?$", "read", "the trash (D-017)"),
    (rf"^{SHARED}/\.RFT_Corbeille/items/\d+(_\d+)?(/.*)?$|^{SHARED}/\.RFT_Corbeille/meta/\d+(_\d+)?\.json$", "read", "a trash entry"),
    (rf"^{SHARED}/Files Tech(/[^/]+)?$", "read", "the output tree of output_base_path"),
    (rf"^{SHARED}/Files Tech/Sauvegardes coffre/[^/]+\.rftvault$", "read", "a vault backup, restored by the check"),
]


def shared_path(line: str) -> str:
    area, _, _, relative = line.split("|", 3)
    return area if relative == "." else area + relative[1:]
PREF_RULES = {
    "permissions_asked": "read", "theme_mode": "read", "recent_files": "read", "recents_dismissed_paths": "read",
    "output_base_path": "read", "output_auto_share": "read",
    "vault_setup_v1": "read", "vault_salt_v1": "read", "vault_kdf_version": "read", "vault_argon2_mem_kb": "read",
    "vault_argon2_iter": "read", "vault_v2_only": "read",
    **{k: "read" for keys in LOCKOUT_KEYS.values() for k in keys},
    **{k: "read" for k in REPAIR_KEYS},
    # INVENTAIRE §9 bis: orphan since 2.15.3, ignored, erased in phase 8.
    "update_last_check_ms_read-files-tech": "ignored",
}


def classify(item: str, rules) -> tuple[str, str] | None:
    for pattern, klass, reason in rules:
        if re.search(pattern, item):
            return klass, reason
    return None


# ───────────────────────────── comparison ─────────────────────────────


class Diff:
    def __init__(self):
        self.lines: list[str] = []

    def eq(self, what: str, port, oracle) -> None:
        if port != oracle:
            self.lines.append(f"{what}: port {short(port)} != 2.15.x {short(oracle)}")

    def true(self, what: str, condition: bool, detail: str = "") -> None:
        if not condition:
            self.lines.append(f"{what}{': ' + detail if detail else ''}")


def short(value) -> str:
    text = json.dumps(value, ensure_ascii=False, sort_keys=True)
    return text if len(text) < 300 else text[:300] + "…"


def pref_value(prefs: dict, key: str):
    entry = prefs.get(key)
    return None if entry is None else entry.get("value")


def compare_lockouts(d: Diff, expected: dict, report: dict) -> None:
    raw = expected["vault"]["lockouts"]
    for name, keys in LOCKOUT_KEYS.items():
        values = raw[name]
        seen = report["vault"]["lockouts"][name]
        oracle = lockout_remaining_ms(
            values[keys[0]] or 0, seen["wallMs"], seen["monoMs"], values[keys[1]] or 0, values[keys[2]] or 0, values[keys[3]],
        )
        d.eq(f"lockout {name}: remaining at the port's instant", seen["remainingMs"], oracle)
    # The unlock with the RIGHT password: refused while the lockout runs, accepted otherwise.
    unlock_remaining = report["vault"]["lockouts"]["unlock"]["remainingMs"]
    unlock = report["vault"].get("unlock")
    if isinstance(unlock_remaining, int) and unlock_remaining > 0:
        d.true("unlock during a running lockout is refused", isinstance(unlock, dict) and unlock.get("error") == "lockout", short(unlock))
    else:
        d.eq("unlock with the right password", unlock, True)


WRITE_RESIDUE = re.compile(r"^.+\.\d+-[0-9a-f]+\.tmp$")


def readable(entry) -> object:
    """A file's digest, or only the fact that it cannot be read (each side names its errors its own way)."""
    return "unreadable" if isinstance(entry, dict) and "error" in entry else entry


def compare_repair(d: Diff, expected: dict, report: dict) -> None:
    vault, seen = expected["vault"], report["vault"]
    oracle = expected.get("repairOracle")
    repair, pending = seen.get("repair"), seen.get("pendingReport")
    if oracle is not None:
        # Dual run: the 2.15.3's own pass, on a snapshot of the very state the port found.
        d.eq("repair pass, port vs the 2.15.3 on the same state", repair, oracle["repairRun"])
        d.eq("pending report, port vs the 2.15.3", pending, oracle["pendingReport"])
        for k in REPAIR_KEYS:
            d.eq(f"after the pass, {k}", report["prefsAfter"].get(k), oracle["prefs"].get(k))
        theirs = {k: readable(v) for k, v in oracle["vaultReads"].items() if not WRITE_RESIDUE.match(k)}
        d.eq("vault files after the pass, port vs the 2.15.3", {k: readable(v) for k, v in seen.get("files", {}).items()}, theirs)
        return
    d.eq("vault files, decrypted by the port", seen.get("files"), vault["imported"])
    if pref_value(expected["prefs"], "vault_zero_key_repair_v1") is True:
        d.eq("repair already done by the 2.15.3: no pass", repair, None)
        # The report the 2.15.3 left unseen (null when it had nothing to show), shown by the port.
        d.eq("pending report", pending, expected.get("pendingReport"))
        return
    d.true("repair pass result", isinstance(repair, dict) and "repaired" in repair, short(repair))
    if isinstance(repair, dict):
        d.eq("files repaired (D1)", sorted(repair.get("repaired", [])), sorted(vault["zeroKey"]))
        d.eq("files left unexamined", repair.get("unexamined"), [])
        d.eq("pending report after the pass", pending,
             {"scanned": 0, "repaired": repair.get("repaired"), "unexamined": []} if repair.get("repaired") else None)


def compare_vault(d: Diff, expected: dict, report: dict) -> None:
    vault, seen = expected["vault"], report["vault"]
    d.eq("vault set up", seen.get("setUp"), vault["setUp"])
    compare_lockouts(d, expected, report)
    if seen.get("unlock") is not True:
        return
    compare_repair(d, expected, report)
    restores = {r["path"]: r for r in seen.get("restores", [])}
    backup_remaining = seen["lockouts"]["backup"]["remainingMs"]
    backup_blocked = isinstance(backup_remaining, int) and backup_remaining > 0
    for backup in vault["backups"]:
        r = restores.get(backup["path"])
        d.true(f"restore of {backup['path']} attempted", r is not None)
        if r is None:
            continue
        if backup_blocked:
            result = r["result"]
            d.true("restore with the right password refused while the restore lockout runs",
                   isinstance(result, dict) and result.get("error") == "lockout", short(result))
            d.eq("vault untouched by the refused restore", r.get("files"), vault["imported"])
            continue
        n = len(backup["entries"])
        d.eq(f"restore of {backup['path']}", r["result"], {"total": n, "restored": n, "skipped": 0, "failed": [], "refused": []})
        files = r.get("files", {})
        for name, digest in backup["entries"].items():
            d.eq(f"restored {name}", files.get(name), digest)


def compare_prefs_after(d: Diff, expected: dict, report: dict) -> None:
    """What the port WROTE: only the unlock counter cleared, and the repair keys if it repaired."""
    before, after = report["prefsBefore"], report["prefsAfter"]
    allowed = set()
    if report["vault"].get("unlock") is True:
        allowed |= set(LOCKOUT_KEYS["unlock"])
        for k in LOCKOUT_KEYS["unlock"]:
            d.true(f"{k} cleared after the unlock", k not in after, short(after.get(k)))
    if isinstance(report["vault"].get("repair"), dict):
        allowed |= set(REPAIR_KEYS)
        # With a dual run the 2.15.3's own keys are the oracle (a pass may end unfinished); otherwise
        # the pass went through every file and must be recorded as done.
        if "repairOracle" not in expected:
            d.eq("repair recorded as done", pref_value(after, "vault_zero_key_repair_v1"), True)
    for key in sorted(set(before) | set(after)):
        if key not in allowed:
            d.eq(f"preference {key} untouched by the port", after.get(key), before.get(key))


# The port's own keys, in the one preferences file (docs D-018), and the value it must have written.
PORT_KEYS = {"port_flutter_cache_purged_v1": {"type": "bool", "value": True}}


def compare_prefs(d: Diff, expected: dict, report: dict) -> None:
    port, oracle = report["prefsBefore"], expected["prefs"]
    for key in sorted(set(port) | set(oracle)):
        if key in PORT_KEYS:
            d.eq(f"port key {key}", port.get(key), PORT_KEYS[key])
        else:
            d.eq(f"preference {key} (type and value)", port.get(key), oracle.get(key))
    for key, value in PORT_KEYS.items():
        d.eq(f"port key {key} written at the first start", port.get(key), value)


def compare(expected: dict, report: dict, census: dict) -> list[str]:
    d = Diff()
    compare_prefs(d, expected, report)
    for k in ("stored", "loaded", "dismissed"):
        d.eq(f"recents.{k}", report["recents"][k], expected["recents"][k])
    d.eq("trash", report["trash"], expected["trash"])
    d.eq("history.legacy", report["history"]["legacy"], expected["history"]["legacy"])
    d.eq("history.byName (D-021)", report["history"]["byName"], expected["history"]["byName"])
    d.eq("extractions (D-022)", report["extractions"], expected["extractions"])
    compare_vault(d, expected, report)
    compare_prefs_after(d, expected, report)
    # The seed's own reading of the vault agrees with what it imported, but for the D1 files it cannot read.
    # Nor the temporary of an interrupted write, which the 2.15.x lists as a vault file (D24).
    reads = {k: v for k, v in expected["vaultReads"].items() if k not in expected["vault"]["zeroKey"] and not WRITE_RESIDUE.match(k)}
    imported = {k: v for k, v in expected["vault"]["imported"].items() if k not in expected["vault"]["zeroKey"]}
    d.eq("seed self-check: the 2.15.x read back what it imported", reads, imported)
    # The lockout values the rule is fed are the preferences' own (the same reading, recorded twice).
    for name, keys in LOCKOUT_KEYS.items():
        for k in keys:
            d.eq(f"seed self-check: lockout {k} as recorded", expected["vault"]["lockouts"][name][k], pref_value(expected["prefs"], k))
    d.lines += census_problems(census, report, expected)
    return d.lines


# ───────────────────────────── census ─────────────────────────────


def census_problems(census: dict, report: dict, expected: dict) -> list[str]:
    problems = []
    d = Diff()
    d.eq("startup cleanup", report.get("startup"), "done")
    problems += d.lines
    at_start = {"./" + e["path"] for e in report["sandboxAtStart"]} if isinstance(report.get("sandboxAtStart"), list) else set()
    paths = [line.split("|", 2)[2] for line in census["sandbox"]]
    for path in paths:
        c = classify(path, SANDBOX_RULES)
        if c is None:
            problems.append(f"census: unclassified sandbox entry {path}")
        elif c[0] == "purged" and path in at_start:
            problems.append(f"census: {path} still there after the port's first start")
        elif c[0] == "read" and path not in at_start:
            problems.append(f"census: {path} GONE after the port's first start — it was to be read")
    if expected["seed"]["scenario"] == "full":
        for pattern in CACHE_RESIDUES:
            if not any(re.search(pattern, p) for p in paths):
                problems.append(f"presence: a cache residue matching {pattern} in the seed")
    for line in census["shared"]:
        if classify(shared_path(line), SHARED_RULES) is None:
            problems.append(f"census: unclassified shared entry {line}")
    for raw_key in census["prefsKeys"]:
        key = raw_key.removeprefix("flutter.")
        if not raw_key.startswith("flutter.") or key not in PREF_RULES:
            problems.append(f"census: unclassified preference key {raw_key}")
        elif key not in report["prefsBefore"]:
            problems.append(f"census: preference {raw_key} on the device, not read by the port")
    problems += census_read_by_port(census, report)
    return problems


def census_read_by_port(census: dict, report: dict) -> list[str]:
    """Everything the census calls "read" must be in the report."""
    problems = []
    vault_files = set(report["vault"].get("files") or {})
    legacy = {c["name"] for c in report["history"]["legacy"]}
    extracted = {(e["dir"], f) for e in report["extractions"] for f in e["content"].get("files", {})}
    trash_ids = {t["id"] for t in report["trash"]}
    for line in census["sandbox"]:
        kind, _, path = line.split("|", 2)
        if kind != "f":
            continue
        m = re.match(r"^\./app_flutter/vault/(.+)\.enc$", path)
        # Unlock refused during a running lockout: the port must NOT open the vault (lockout scenarios).
        opened = report["vault"].get("unlock") is True
        if m and opened and m.group(1) != "_check" and m.group(1) not in vault_files:
            problems.append(f"census: vault file {path} not read by the port")
        m = re.match(r"^\./app_flutter/history/(.+\.bak)$", path)
        if m and m.group(1) not in legacy:
            problems.append(f"census: history copy {path} not read by the port")
        m = re.match(r"^\./app_flutter/([^/]+_extracted)/(.+)$", path)
        if m and (m.group(1), m.group(2)) not in extracted:
            problems.append(f"census: extracted file {path} not read by the port")
    for line in census["shared"]:
        m = re.search(r"\.RFT_Corbeille\|f\|\d+\|\./meta/(.+)\.json$", line)
        if m and m.group(1) not in trash_ids:
            problems.append(f"census: trash entry {m.group(1)} not read by the port")
    return problems


def census_table(census: dict) -> list[str]:
    rows = []
    for line in census["sandbox"]:
        path = line.split("|", 2)[2]
        c = classify(path, SANDBOX_RULES)
        rows.append(f"sandbox | {path} | {c[0] if c else 'UNCLASSIFIED'} | {c[1] if c else ''}")
    for line in census["shared"]:
        c = classify(shared_path(line), SHARED_RULES)
        rows.append(f"shared | {line} | {c[0] if c else 'UNCLASSIFIED'} | {c[1] if c else ''}")
    for raw_key in census["prefsKeys"]:
        rows.append(f"pref | {raw_key} | {PREF_RULES.get(raw_key.removeprefix('flutter.'), 'UNCLASSIFIED')} |")
    return rows


# ───────────────────────────── presence ─────────────────────────────


def remaining_at_seed(expected: dict, name: str) -> int:
    seed, values = expected["seed"], expected["vault"]["lockouts"][name]
    keys = LOCKOUT_KEYS[name]
    return lockout_remaining_ms(values[keys[0]] or 0, seed["wallMs"], seed["monoMs"], values[keys[1]] or 0,
                                values[keys[2]] or 0, values[keys[3]])


def presence_problems(expected: dict, report: dict | None = None, rebooted: bool = False) -> list[str]:
    """The states a scenario must contain: absent from the seed, they would pass the comparison untested."""
    scenario, tag = expected["seed"]["scenario"], expected["seed"]["tag"]
    p, r, v = expected["prefs"], expected["recents"], expected["vault"]
    checks: list[tuple[str, bool]] = []
    if rebooted:
        checks.append(("the device rebooted between the seed and the port", report is not None
                       and report["port"]["bootId"] != expected["seed"]["bootId"]))
    # D1 only in the scenarios that reproduce it, and only a 2.15.2 can (the 2.15.3 fixed it): the
    # interrupted repair is a 2.15.3 over the D1 file of a 2.15.2.
    with_d1 = (tag == "2.15.2" and scenario in ("full", "zerokey")) or scenario == "repair-interrupted"
    checks.append(("zeroed-key files exactly where D1 is reproduced", len(v["zeroKey"]) == (1 if with_d1 else 0)))
    with_backup = scenario in ("full", "backup-lockout", "big-export", "restore-only")
    checks.append(("a backup exactly where the scenario makes one", len(v["backups"]) == (1 if with_backup else 0)))
    if scenario == "repair-report":
        checks += [
            ("the 2.15.3 repaired the D1 file", (expected.get("repairRun") or {}).get("repaired") == ["clé-nulle 2M.bin"]),
            ("its report not yet seen", bool((expected.get("pendingReport") or {}).get("repaired"))),
        ]
    if scenario == "repair-interrupted":
        residues = [k for k in expected["vaultReads"] if WRITE_RESIDUE.match(k)]
        checks += [
            ("the pass died on the D1 file: _current set", pref_value(p, "vault_zero_key_repair_current") == "clé-nulle 2M.bin.enc"),
            ("its temporary next to it, listed by the 2.15.x itself (D24)", len(residues) == 1),
            ("the 2.15.3 ran its own pass on the same state", expected.get("repairOracle") is not None),
            ("the port does not take the temporary for a vault file (D24)",
             report is not None and not any(WRITE_RESIDUE.match(k) for k in (report["vault"].get("files") or {}))),
        ]
    if scenario == "legacy":
        checks += [
            ("no vault_kdf_version: PBKDF2", "vault_kdf_version" not in p),
            ("no vault_v2_only: v1 accepted", "vault_v2_only" not in p),
            ("a v1 sentinel and a v1 file", expected.get("legacy", {}).get("sentinel") == "v1"
             and len(expected.get("legacy", {}).get("v1Files", [])) == 1),
            ("the 2.15.x opens it", set(expected["vaultReads"]) == set(v["imported"])),
        ]
    if scenario == "backup-lockout":
        checks += [
            ("restore counter at 7", pref_value(p, "vault_backup_fails") == 7),
            ("restore lockout running at the end of the seed", remaining_at_seed(expected, "backup") > 0),
        ]
    if scenario == "unlock-lockout":
        checks += [
            ("unlock counter at 7", pref_value(p, "vault_unlock_fails") == 7),
            ("unlock lockout running at the end of the seed", remaining_at_seed(expected, "unlock") > 0),
            ("the arming instant recorded (2.15.3), absent (2.15.2)",
             (v["lockouts"]["unlock"]["vault_lockout_armed_elapsed"] is not None) == (tag == "2.15.3")),
        ]
    if scenario == "full":
        stored = r["stored"] or []
        checks += [
            ("every key of the inventory (§5.1) present", all(k in p for k in PREF_RULES
                                                              if k not in LOCKOUT_KEYS["backup"] and k not in REPAIR_KEYS
                                                              and k not in LOCKOUT_KEYS["unlock"][1:])),
            ("unlock counter at 4", pref_value(p, "vault_unlock_fails") == 4),
            ("five recents", len(stored) == 5),
            ("a favourite", any(e.get("isFavorite") for e in stored)),
            ("a lastOpened with microseconds", any(re.search(r"\.\d{3}[1-9]\d{2}$|\.\d{4}[1-9]\d$|\.\d{5}[1-9]$", e["lastOpened"]) for e in stored)),
            ("an accented path", any(re.search(r"[^\x00-\x7f]", e["path"]) for e in stored)),
            ("an emoji path (outside the BMP)", any(any(ord(c) > 0xFFFF for c in e["path"]) for e in stored)),
            ("two dismissed paths, one accented", len(r["dismissed"]) == 2 and any(re.search(r"[^\x00-\x7f]", x) for x in r["dismissed"])),
            ("a file and a folder in the trash", {t["isDir"] for t in expected["trash"]} == {True, False}),
            ("the D6 collision: notes.txt and mes_notes.txt copies", all(expected["history"]["byName"][n] for n in ("notes.txt", "mes_notes.txt"))),
            ("an emoji name in the history, two units made `__`", any("___.md_" in c["name"] for c in expected["history"]["legacy"])),
            ("an extraction with a subfolder", any("/" in f for e in expected["extractions"] for f in e["content"]["files"])),
            ("an empty vault file", any(x["size"] == 0 for x in v["imported"].values())),
            ("a vault file over 5 MB (native path)", any(x["size"] >= 5 * 1024 * 1024 for x in v["imported"].values())),
            ("a vault file of 1-5 MB (isolate path)", any(1024 * 1024 <= x["size"] < 5 * 1024 * 1024 for x in v["imported"].values())),
        ]
        if tag == "2.15.2":
            checks.append(("the 2.15.2 itself cannot read its D1 file", all("error" in (expected["vaultReads"].get(n) or {}) for n in v["zeroKey"])))
        else:
            checks.append(("the repair marked done at setup (2.15.3)", pref_value(p, "vault_zero_key_repair_v1") is True))
    return [f"presence: {what}" for what, ok in checks if not ok]


# ───────────────────────────── negative control ─────────────────────────────

COMPARED = ["prefs", "recents", "trash", "history", "extractions", "vault", "vaultReads"]
REBOOTED = False  # set by main: the run's folder holds rebooted.json (phase2.py check --reboot)


def not_compared(expected: dict, report: dict, path: tuple) -> str | None:
    """Why a datum is left out of the negative control, or None."""
    if path[-1] == "error" and (path[0] == "vaultReads" or path[:2] == ("repairOracle", "vaultReads")):
        return "the class name of the exception Dart raised on an unreadable file: only its presence matters"
    if "repairOracle" in expected and path[:2] == ("vault", "imported") and path[2] in expected["vault"]["zeroKey"]:
        return "the pass died on this D1 file and skips it: neither side can read it, its content is not compared"
    backup_remaining = report["vault"]["lockouts"]["backup"]["remainingMs"]
    if path[:2] == ("vault", "backups") and "entries" in path and isinstance(backup_remaining, int) and backup_remaining > 0:
        return "the restore is refused while its lockout runs: the backup's content is not read (scenario full reads it)"
    return None


def leaves(value, path=()):
    if isinstance(value, dict):
        if not value:
            yield path
        for k, v in value.items():
            yield from leaves(v, path + (k,))
    elif isinstance(value, list):
        if not value:
            yield path
        for i, v in enumerate(value):
            yield from leaves(v, path + (i,))
    else:
        yield path


def mutate(root, path):
    target = root
    for step in path[:-1]:
        target = target[step]
    key = path[-1] if path else None
    value = target[key] if path else root
    if isinstance(value, bool):
        new = not value
    elif isinstance(value, int):
        # An arming instant only matters once it is in the future of the clock: move it far.
        new = value + 10**9 if str(key).endswith("_armed_elapsed") else value + 1
    elif isinstance(value, str):
        new = value + "x"
    elif value is None:
        new = 1
    elif isinstance(value, (list, dict)):
        new = [1] if isinstance(value, list) else {"x": 1}
    else:
        new = None
    target[key] = new


def problems_of(expected, report, census) -> set[str]:
    try:
        return set(presence_problems(expected, report, REBOOTED) + compare(expected, report, census))
    except (KeyError, TypeError, AttributeError, IndexError) as e:
        # A mutation that changes the SHAPE (a list made a number): the comparator rejects the input.
        return {f"comparator refused the input: {e!r}"}


def negative_control(expected, report, census) -> list[str]:
    """Caught = the mutation adds a difference the unmutated run does not have. Against a baseline that
    already fails, "a difference appeared" would be true of every mutation and prove nothing."""
    baseline = problems_of(expected, report, census)
    escaped = []
    count = 0
    roots = [(section,) for section in COMPARED]
    if "repairOracle" in expected:
        # Of the 2.15.3's pass, what is compared: its result, its report, its repair keys, its reads.
        roots += [("repairOracle", "repairRun"), ("repairOracle", "pendingReport"), ("repairOracle", "vaultReads")]
        roots += [("repairOracle", "prefs", k) for k in REPAIR_KEYS if k in expected["repairOracle"]["prefs"]]
    for root in roots:
        subtree = expected
        for step in root:
            subtree = subtree[step]
        for path in leaves(subtree, root):
            if not_compared(expected, report, path):
                continue
            mutated = copy.deepcopy(expected)
            mutate(mutated, path)
            count += 1
            if not (problems_of(mutated, report, census) - baseline):
                escaped.append("/".join(str(s) for s in path))
    # The census side: a purged entry still there, a read entry gone, a file nobody classified.
    for line in census["sandbox"]:
        kind, _, path = line.split("|", 2)
        c = classify(path, SANDBOX_RULES)
        if c is None or c[0] == "ignored":
            continue
        mutated = copy.deepcopy(report)
        relative = path.removeprefix("./")
        if c[0] == "purged":
            mutated["sandboxAtStart"].append({"path": relative, "type": kind, "size": 0})
        else:
            mutated["sandboxAtStart"] = [e for e in mutated["sandboxAtStart"] if e["path"] != relative]
        count += 1
        if not (problems_of(expected, mutated, census) - baseline):
            escaped.append(f"census {c[0]} {path}")
    for extra in ("f|1|./app_flutter/unknown.db", "f|1|./databases/x.db"):
        mutated = copy.deepcopy(census)
        mutated["sandbox"].append(extra)
        count += 1
        if not (problems_of(expected, report, mutated) - baseline):
            escaped.append(f"census unknown {extra}")
    clean = "clean baseline" if not baseline else f"baseline with {len(baseline)} difference(s)"
    print(f"negative control ({clean}): {count} mutations, {count - len(escaped)} caught")
    return escaped


def main() -> int:
    out = sys.argv[1]
    load = lambda name: json.load(open(os.path.join(out, name), encoding="utf-8"))  # noqa: E731
    expected, report, census = load("expected.json"), load("report.json"), load("census.json")
    global REBOOTED
    REBOOTED = os.path.exists(os.path.join(out, "rebooted.json"))
    if os.path.exists(os.path.join(out, "repair_oracle.json")):
        expected["repairOracle"] = load("repair_oracle.json")
    problems = presence_problems(expected, report, REBOOTED) + compare(expected, report, census)
    print(f"{expected['seed']['tag']} / {expected['seed']['scenario']} — port {report['port']['buildType']} "
          f"sdk {report['port']['sdk']}")
    for row in census_table(census):
        print("  " + row)
    for p in problems:
        print("DIFF " + p)
    status = 1 if problems else 0
    print("OK: everything the 2.15.x read, the port read the same" if not problems else f"{len(problems)} difference(s)")
    if "--negative" in sys.argv:
        escaped = negative_control(expected, report, census)
        for e in escaped:
            print("NOT CAUGHT " + e)
        status |= 1 if escaped else 0
    return status


if __name__ == "__main__":
    sys.exit(main())
