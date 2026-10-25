"""The fixed corpus of the switch protocol (docs/09-PROTOCOLE-BASCULE.md §3), and its comparison.

Usage:
    python -I tools/corpus/gen_switch.py make OUT_DIR
    python -I tools/corpus/gen_switch.py verify DIR [--part vault|recents|trash|editor|extract] [--negative-control]

`make` writes the corpus into OUT_DIR (which must not exist) and `switch-corpus.json` beside it: every file's
path, size and SHA-256. The bytes are the same at each run (seeded generator, ZIP entries dated), so the host
knows them before the 2.15.x ever sees them.

`verify` compares DIR — what came back from the phone after the update: files exported from the vault in
clear, items restored from the trash, a folder moved out of the old extractions — with the manifest of the
part named, by RELATIVE path: a file missing, one extra or one whose SHA-256 differs fails, each named.
`--negative-control` first breaks a copy of DIR three ways (a byte changed, a file removed, a file added) and
requires each copy to fail: a comparison that passes those measures nothing (protocol §5).

What the parts are for, in the 2.15.x before the update:
- vault/    imported into the vault (accented names, 60 MiB, a folder of 50 files, a ZIP), then exported back;
- recents/  five files opened, two made favourites, one removed from the list;
- trash/    one file and one folder deleted from the explorer, restored after the update;
- editor/   one text saved three times: three copies in the old history;
- extract/  one ZIP, "Extract all" (app_flutter/<name>_extracted/), moved after the update.
"""
import hashlib
import io
import json
import pathlib
import random
import shutil
import sys
import tempfile
import zipfile

MIB = 1024 * 1024
MANIFEST = "switch-corpus.json"
STAMP = (2026, 1, 1, 0, 0, 0)
PARTS = ("vault", "recents", "trash", "editor", "extract")


def _bytes(seed, size):
    return random.Random(seed).randbytes(size)


def _zip(entries):
    """A ZIP whose bytes do not depend on the clock: every entry dated STAMP, stored in the given order."""
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o644 << 16) if not name.endswith("/") else (0o755 << 16) | 0x10
            archive.writestr(info, data)
    return out.getvalue()


def corpus():
    """Relative path -> bytes, for every file of the corpus."""
    files = {
        "vault/Été à l'école — notes.txt": "Première ligne, accentuée : àâçéèêëîïôûùüÿœæ.\nDeuxième ligne.\n".encode("utf-8"),
        "vault/Données çà.csv": "nom;quantité;prix\npommes;3;2,50\npoires;5;3,10\n".encode("utf-8"),
        "vault/gros fichier 60 Mo.bin": _bytes("vault-large", 60 * MIB),
        "vault/archive du coffre.zip": _zip([
            ("lisez-moi.txt", "Une archive dans le coffre.\n".encode("utf-8")),
            ("sous dossier/", b""),
            ("sous dossier/données.bin", _bytes("vault-zip-entry", 4096)),
        ]),
        "recents/un texte.txt": "Ouvert, puis mis en favori.\n".encode("utf-8"),
        "recents/une note.md": "# Une note\n\nOuverte, puis mise en favori.\n".encode("utf-8"),
        "recents/un tableau.csv": "a,b\n1,2\n".encode("utf-8"),
        "recents/des données.json": '{"ouvert": true, "retiré de la liste": false}\n'.encode("utf-8"),
        "recents/une page.html": "<!doctype html><title>Une page</title><p>Ouverte, puis retirée de la liste.</p>\n".encode("utf-8"),
        "trash/à supprimer.txt": "Supprimé depuis l'explorateur, restauré après la mise à jour.\n".encode("utf-8"),
        "trash/dossier supprimé/un.txt": b"un\n",
        "trash/dossier supprimé/deux é.bin": _bytes("trash-folder", 2048),
        "editor/historique.txt": "Version 1 (avant les trois enregistrements).\n".encode("utf-8"),
        "extract/photos de vacances.zip": _zip([
            ("plage.jpg", _bytes("extract-1", 30_000)),
            ("montagne é.jpg", _bytes("extract-2", 20_000)),
            ("album/", b""),
            ("album/légende.txt", "Trois photos, une légende.\n".encode("utf-8")),
        ]),
    }
    for i in range(1, 51):
        files[f"vault/dossier de 50/fichier {i:02d}.txt"] = f"Fichier {i:02d} sur 50 — é\n".encode("utf-8")
    return files


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def make(out_dir):
    out = pathlib.Path(out_dir)
    if out.exists():
        sys.exit(f"{out} exists: the corpus is written into a new folder only.")
    manifest = {}
    for relative, data in sorted(corpus().items()):
        path = out / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        manifest[relative] = {"size": len(data), "sha256": sha256(data)}
    (out / MANIFEST).write_text(json.dumps(manifest, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    print(f"{len(manifest)} files written in {out}, {sum(v['size'] for v in manifest.values()) / MIB:.1f} MiB.")


def expected_of(part):
    """The part's files, relative to the part's folder; what "Extract all" made of a ZIP is its entries."""
    files = corpus()
    if part == "extract-moved":
        with zipfile.ZipFile(io.BytesIO(files["extract/photos de vacances.zip"])) as archive:
            return {i.filename: sha256(archive.read(i)) for i in archive.infolist() if not i.is_dir()}
    prefix = part + "/"
    return {k[len(prefix):]: sha256(v) for k, v in files.items() if k.startswith(prefix)}


def found_in(directory):
    root = pathlib.Path(directory)
    return {p.relative_to(root).as_posix(): sha256(p.read_bytes()) for p in sorted(root.rglob("*")) if p.is_file()}


def problems(directory, part):
    expected, found = expected_of(part), found_in(directory)
    out = [f"missing: {k}" for k in sorted(expected.keys() - found.keys())]
    out += [f"not in the corpus: {k}" for k in sorted(found.keys() - expected.keys())]
    out += [f"different (SHA-256): {k}" for k in sorted(expected.keys() & found.keys()) if expected[k] != found[k]]
    return out


def negative_control(directory, part):
    reasons = {"a byte changed": "different", "a file removed": "missing", "a file added": "not in the corpus"}
    caught = 0
    for label, reason in reasons.items():
        with tempfile.TemporaryDirectory() as tmp:
            copy = pathlib.Path(tmp) / "copy"
            shutil.copytree(directory, copy)
            victim = next(p for p in sorted(copy.rglob("*")) if p.is_file())
            if label == "a byte changed":
                data = bytearray(victim.read_bytes())
                data[0] ^= 0x01
                victim.write_bytes(bytes(data))
            elif label == "a file removed":
                victim.unlink()
            else:
                (copy / "en trop.txt").write_bytes(b"x")
            ok = any(p.startswith(reason) for p in problems(copy, part))
        caught += ok
        print(f"  {label:20s} {'caught' if ok else 'NOT CAUGHT'}")
    print(f"NEGATIVE CONTROL: {caught}/{len(reasons)}")
    return caught == len(reasons)


def main(argv):
    if len(argv) >= 3 and argv[1] == "make":
        make(argv[2])
        return 0
    if len(argv) >= 3 and argv[1] == "verify":
        directory = argv[2]
        part = argv[argv.index("--part") + 1] if "--part" in argv else "vault"
        if part not in PARTS + ("extract-moved",):
            sys.exit(f"unknown part {part}: {', '.join(PARTS + ('extract-moved',))}")
        if "--negative-control" in argv and not negative_control(directory, part):
            return 1
        found = problems(directory, part)
        for line in found:
            print("ERROR:", line)
        if not found:
            print(f"OK: {len(expected_of(part))} files of {part}, every SHA-256 equal to the corpus.")
        return 1 if found else 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
