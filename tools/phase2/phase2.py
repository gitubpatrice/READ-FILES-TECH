#!/usr/bin/env python3
"""Phase 2 exit criterion, host side (port plan, phase 2): seed a Flutter 2.15.x, take its census,
install the port OVER it, and collect what the port reads.

    phase2.py seed  --serial S --apk seed.apk --tag 2.15.3 --scenario full --out DIR [--wipe-shared]
    phase2.py check --serial S --apk port.apk --out DIR
    phase2.py compare --out DIR            (tools/phase2/compare.py, on the files of DIR)

`seed` leaves in DIR: expected.json (what the 2.15.x itself reads back), census.json (every file of the
sandbox and of the shared-storage areas, every key of the preferences file) and seed.log. `check` leaves
report.json, what the port read. Both builds must be signed by the same key (debug), the port with the
real package (-Prft.replaceFlutter=true) and a higher versionCode.

⚠️ DESTRUCTIVE for the device's Read Files Tech: `seed` uninstalls the installed package (its private
data goes with it). `--wipe-shared` also deletes the 2.15.x areas of shared storage — only on an
emulator or a test phone whose storage carries no real Read Files Tech data (docs D-017).
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PACKAGE = "com.readfilestech.read_files_tech"
SHARED = "/storage/emulated/0"
# The areas of shared storage the 2.15.x writes (INVENTAIRE §5.2), and the seed's own folder.
SHARED_AREAS = [f"{SHARED}/RFT seed", f"{SHARED}/.RFT_Corbeille", f"{SHARED}/Files Tech", f"{SHARED}/Android/data/{PACKAGE}"]
VAULT_PASSWORD = "Coffre été 🔐 2026"
BACKUP_PASSWORD = "Sauvegarde ✓ export"
HISTORY_NAMES = ["notes.txt", "mes_notes.txt", "rapport 📊.md", "Relevé été ✓.txt"]
SEED_TIMEOUT_S = 900


class Device:
    def __init__(self, serial: str):
        self.serial = serial

    def adb(self, *args: str, check: bool = True, timeout: int = 600) -> str:
        done = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, timeout=timeout)
        out = done.stdout.decode("utf-8", errors="replace")
        if check and done.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} -> {done.returncode}: {out}{done.stderr.decode('utf-8', 'replace')}")
        return out

    def raw(self, *args: str) -> bytes:
        done = subprocess.run(["adb", "-s", self.serial, "exec-out", *args], capture_output=True, timeout=600)
        if done.returncode != 0:
            raise RuntimeError(f"exec-out {' '.join(args)} -> {done.returncode}: {done.stderr!r}")
        return done.stdout

    def shell(self, command: str, check: bool = True) -> str:
        return self.adb("shell", command, check=check)

    def sdk(self) -> int:
        return int(self.shell("getprop ro.build.version.sdk").strip())

    def run_as(self, command: str) -> str:
        return self.shell(f"run-as {PACKAGE} sh -c '{command}'")

    def grant_storage(self) -> None:
        if self.sdk() >= 30:
            self.shell(f"appops set {PACKAGE} MANAGE_EXTERNAL_STORAGE allow")
        else:
            for p in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
                self.shell(f"pm grant {PACKAGE} android.permission.{p}")

    def package_line(self) -> str:
        out = self.shell(f"dumpsys package {PACKAGE}", check=False)
        code = re.search(r"versionCode=(\d+)", out)
        flags = re.search(r"pkgFlags=\[([^\]]*)\]", out)
        return f"versionCode={code.group(1) if code else '?'} flags=[{flags.group(1).strip() if flags else '?'}]"


def log(out_dir: str, name: str, text: str) -> None:
    print(text)
    with open(os.path.join(out_dir, name), "a", encoding="utf-8") as handle:
        handle.write(text + "\n")


def write_json(path: str, value) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=1)


def census(device: Device) -> dict:
    """Every file, folder and link of the sandbox (run-as) and of the shared areas, and the prefs keys."""
    lister = 'for t in f d l; do find . -type $t | while read p; do echo "$t|$(stat -c %s "$p")|$p"; done; done'
    sandbox = device.run_as(f"cd /data/data/{PACKAGE} && {lister}")
    shared = []
    for area in SHARED_AREAS:
        quoted = area.replace("'", "")
        out = device.shell(f"[ -e '{quoted}' ] && cd '{quoted}' && {lister}", check=False)
        shared += [f"{area}|{line}" for line in out.splitlines() if line.strip()]
    prefs = device.raw("run-as", PACKAGE, "cat", "shared_prefs/FlutterSharedPreferences.xml").decode("utf-8")
    keys = re.findall(r'name="([^"]*)"', prefs)
    return {
        "sandbox": sorted(line for line in sandbox.splitlines() if line.strip()),
        "shared": sorted(shared),
        "prefsKeys": sorted(keys),
        "prefsXml": prefs,
    }


def seed(args) -> int:
    os.makedirs(args.out, exist_ok=True)
    device = Device(args.serial)
    log(args.out, "seed.log", f"device {args.serial} sdk {device.sdk()} before: {device.package_line()}")
    if args.keep:
        # A second stage: this version installed OVER the previous seed (2.15.2 then 2.15.3), data kept.
        device.adb("install", "-r", "-t", args.apk)
    else:
        device.adb("uninstall", PACKAGE, check=False)
        if args.wipe_shared:
            for area in SHARED_AREAS:
                device.shell(f"rm -rf '{area}'", check=False)
        device.adb("install", "-t", args.apk)
    device.grant_storage()
    log(args.out, "seed.log", f"seed installed: {device.package_line()}")
    # ASCII only, double quotes escaped: it goes through two shells (adb's, then run-as's `sh -c '...'`).
    scenario = json.dumps({"scenario": args.scenario, "tag": args.tag}).replace('"', '\\"')
    device.run_as(f'mkdir -p files/rft_seed && echo "{scenario}" > files/rft_seed/scenario.json')
    device.shell(f"am start -W -n {PACKAGE}/.MainActivity")
    status = ""
    started = time.time()
    while time.time() - started < SEED_TIMEOUT_S:
        time.sleep(3)
        status = device.shell(f"run-as {PACKAGE} cat files/rft_seed/status.txt", check=False).strip()
        if status.startswith(("ok", "failed")):
            break
    log(args.out, "seed.log", f"seed status after {int(time.time() - started)} s: {status[:2000]}")
    if not status.startswith("ok"):
        return 1
    expected = device.raw("run-as", PACKAGE, "cat", "files/rft_seed/expected.json")
    with open(os.path.join(args.out, "expected.json"), "wb") as handle:
        handle.write(expected)
    device.shell(f"am force-stop {PACKAGE}")
    # The seed's own files go before the census: they are not the 2.15.x's.
    device.run_as("rm -r files/rft_seed")
    write_json(os.path.join(args.out, "census.json"), census(device))
    log(args.out, "seed.log", "expected.json and census.json written")
    return 0


def check(args) -> int:
    os.makedirs(args.out, exist_ok=True)
    device = Device(args.serial)
    with open(os.path.join(args.out, "expected.json"), encoding="utf-8") as handle:
        expected = json.load(handle)
    log(args.out, "check.log", f"before: {device.package_line()}")
    if args.reboot:
        # D2: a lockout armed before a reboot, read by the port after it.
        device.adb("reboot")
        device.adb("wait-for-device", timeout=600)
        started = time.time()
        while device.shell("getprop sys.boot_completed", check=False).strip() != "1":
            if time.time() - started > 600:
                raise RuntimeError("the device did not finish booting")
            time.sleep(3)
        time.sleep(10)
        write_json(os.path.join(args.out, "rebooted.json"), {"after": int(time.time() - started)})
        log(args.out, "check.log", f"rebooted, booted in {int(time.time() - started)} s")
    # An UPDATE, never an uninstall: the point is to find the 2.15.x's data in place.
    device.adb("install", "-r", "-t", args.apk)
    device.grant_storage()
    log(args.out, "check.log", f"port installed: {device.package_line()}")
    request = {
        "vaultPassword": VAULT_PASSWORD,
        "backupPassword": BACKUP_PASSWORD,
        "repair": True,
        "restores": [b["path"] for b in expected["vault"]["backups"]],
        "historyNames": HISTORY_NAMES,
    }
    arg = base64.b64encode(json.dumps(request, ensure_ascii=False).encode("utf-8")).decode("ascii")
    started = time.time()
    out = device.shell(f"content call --uri content://{PACKAGE}.portcheck --method observe --arg {arg}")
    match = re.search(r"report=([A-Za-z0-9+/=]+)", out)
    if not match:
        log(args.out, "check.log", f"no report in: {out[:3000]}")
        return 1
    report = json.loads(base64.b64decode(match.group(1)).decode("utf-8"))
    write_json(os.path.join(args.out, "report.json"), report)
    log(args.out, "check.log", f"report.json written after {int(time.time() - started)} s")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    s = sub.add_parser("seed")
    s.add_argument("--serial", required=True)
    s.add_argument("--apk", required=True)
    s.add_argument("--tag", required=True, choices=["2.15.2", "2.15.3"])
    s.add_argument("--scenario", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--wipe-shared", action="store_true")
    s.add_argument("--keep", action="store_true", help="install over the previous seed, data kept")
    c = sub.add_parser("check")
    c.add_argument("--serial", required=True)
    c.add_argument("--apk", required=True)
    c.add_argument("--out", required=True)
    c.add_argument("--reboot", action="store_true", help="reboot the device before installing the port")
    args = parser.parse_args()
    return {"seed": seed, "check": check}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
