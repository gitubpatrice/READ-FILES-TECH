"""The hostile corpus of the viewers (port plan, phase 4, finding 8), run on a device.

Usage:
    python -I tools/corpus/corpus.py --serial emulator-5554 [--package <applicationId>] [--only TEXT]

What it does:
1. Builds the corpus in a scratch folder: the files of every `tools/corpus/<viewer>/` folder (made by
   REAL software, never by the code that reads them; their expectations in that folder's
   `corpus.json`), plus the hostile files that each `tools/corpus/gen_<viewer>.py` generates (too large,
   one endless line, binary under a text name...).
2. Pushes it to the device (`/sdcard/Download/rft-corpus` by default) and calls the check provider of
   the `debug` or `check` build: `content call --method corpus`. The `check` build is the release as R8
   makes it — the one the phase's exit criterion names.
3. Compares each outcome with what was expected, the time with its budget, and that nothing was
   written in the app's sandbox or in the corpus folder. Exit code 0 only if every file passes.

The app must hold "All files access" (Android 11+: `appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`,
which this script does with --grant). Reading only: the corpus is pushed to the shared storage of the
device, and removed afterwards (--keep to leave it). On a phone, prefer the emulator (docs D-017).
"""
import argparse
import base64
import importlib.util
import json
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_PACKAGE = "com.readfilestech.read_files_tech.next"
DEVICE_DIR = "/sdcard/Download/rft-corpus"


def adb(serial, *args, check=True):
    run = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and run.returncode != 0:
        sys.exit("adb %s failed: %s" % (" ".join(args), (run.stdout + run.stderr).strip()))
    return run.stdout


def build(out, only):
    """Copies the real files and generates the hostile ones; returns the merged expectations."""
    cases = []
    for folder in sorted(p for p in HERE.iterdir() if p.is_dir() and not p.name.startswith(("_", "."))):
        manifest = folder / "corpus.json"
        if not manifest.exists():
            continue
        for case in json.loads(manifest.read_text(encoding="utf-8")):
            if only and case["viewer"] != only:
                continue
            name = "%s__%s" % (folder.name, case["file"])
            shutil.copyfile(folder / case["file"], out / name)
            cases.append(dict(case, file=name))
    for generator in sorted(HERE.glob("gen_*.py")):
        spec = importlib.util.spec_from_file_location(generator.stem, generator)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for case in module.generate(out):
            if not only or case["viewer"] == only:
                cases.append(case)
            else:
                (out / case["file"]).unlink()
    (out / "corpus.json").write_text(json.dumps(cases, indent=1), encoding="utf-8")
    return cases


def report_of(output):
    match = re.search(r"report=([A-Za-z0-9+/=]+)", output)
    if not match:
        sys.exit("no report in the provider's answer:\n" + output[-2000:])
    return json.loads(base64.b64decode(match.group(1)).decode("utf-8"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--only", help="one viewer (a ViewerKind name)")
    parser.add_argument("--grant", action="store_true", help="appops MANAGE_EXTERNAL_STORAGE allow")
    parser.add_argument("--keep", action="store_true", help="leave the corpus on the device")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="rft-corpus-") as scratch:
        out = pathlib.Path(scratch) / "rft-corpus"
        out.mkdir()
        cases = build(out, args.only)
        print("corpus: %d files" % len(cases))
        if args.grant:
            adb(args.serial, "shell", "appops", "set", args.package, "MANAGE_EXTERNAL_STORAGE", "allow")
        adb(args.serial, "shell", "rm", "-rf", DEVICE_DIR)
        adb(args.serial, "push", str(out), DEVICE_DIR)
    uri = "content://%s.portcheck" % args.package
    output = adb(args.serial, "shell", "content", "call", "--uri", uri, "--method", "corpus", "--arg", DEVICE_DIR, check=False)
    if not args.keep:
        adb(args.serial, "shell", "rm", "-rf", DEVICE_DIR, check=False)
    if "report=" not in output:
        # The process died under a file (an OutOfMemoryError the load did not catch, a native crash).
        print("THE APP DIED during the corpus:\n" + output[-2000:])
        return 1
    failures = 0
    print("%-46s %-6s %-14s %-14s %8s %10s  %s" % ("file", "viewer", "expected", "outcome", "ms", "heap kept", "verdict"))
    for result in report_of(output)["results"]:
        problems = []
        if result["outcome"] != result["expect"]:
            problems.append("outcome")
        if result["millis"] > result["maxMillis"]:
            problems.append("slow")
        if result["wroteInSandbox"] or result["changedInCorpus"]:
            problems.append("wrote %s %s" % (result["wroteInSandbox"], result["changedInCorpus"]))
        failures += bool(problems)
        print("%-46s %-6s %-14s %-14s %8d %10d  %s%s" % (
            result["file"][:46], result["viewer"], result["expect"], result["outcome"], result["millis"],
            result["heapKeptBytes"], "OK" if not problems else "FAIL " + ", ".join(problems),
            "" if not result["detail"] else "  (%s)" % result["detail"],
        ))
    print("%d file(s) failed" % failures if failures else "every file passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
