"""Sabotages : remplace un motif exact dans un fichier, lance une commande de test, restaure TOUJOURS.

Usage : python -I sabotage.py <fichier> <motifs.json> <commande de test...>
motifs.json : liste de {"label", "old", "new"}. Chaque sabotage est applique SEUL ; s'il ne trouve
pas son motif exactement une fois, il est signale NON APPLIQUE (un sabotage absent ne mesure rien).
Verdict attendu : la commande de test ECHOUE (code != 0) pour chaque sabotage.
"""
import json
import subprocess
import sys

sys.stdout.reconfigure(errors="replace")  # console cp1252 : un caractere hors table ne doit pas tuer le rapport
path, motifs_path, command = sys.argv[1], sys.argv[2], sys.argv[3:]
with open(path, encoding="utf-8", newline="") as handle:
    original = handle.read()
with open(motifs_path, encoding="utf-8") as handle:
    motifs = json.load(handle)

results = []


def untracked():
    """Fichiers non suivis du depot : un sabotage qui retire une garde de chemin peut ecrire HORS du
    dossier temporaire du test (vecu le 2026-10-11 : `app/relative/r.txt`)."""
    run = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"], capture_output=True, text=True)
    return {line[3:] for line in run.stdout.splitlines() if line.startswith("??")}


before = untracked()
# Temoin : sans sabotage la commande DOIT reussir. Le 2026-10-11, `cmd //c` lance depuis Python ne
# trouvait pas gradlew.bat : chaque sabotage « echouait », donc paraissait attrape, sans qu'un seul test
# ait tourne.
baseline = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
if baseline.returncode != 0:
    print("TEMOIN EN ECHEC : la commande echoue sans sabotage, rien ne serait mesure.")
    print((baseline.stdout + baseline.stderr).strip()[-800:])
    sys.exit(2)
# Et elle doit avoir lance Gradle. Le 2026-10-22, `cmd /c` tape dans Git Bash sans MSYS_NO_PATHCONV=1 :
# `/c` devenu un chemin, cmd s'ouvrait et rendait 0 — temoin vert, chaque sabotage « inapercu », rien lance.
if any("gradlew" in part for part in command) and "BUILD SUCCESSFUL" not in baseline.stdout + baseline.stderr:
    print("TEMOIN SANS BUILD : la commande rend 0 sans que Gradle ait tourne (MSYS_NO_PATHCONV=1 ?).")
    print((baseline.stdout + baseline.stderr).strip()[-800:])
    sys.exit(2)
try:
    for motif in motifs:
        count = original.count(motif["old"])
        if count != 1:
            results.append((motif["label"], "NON APPLIQUE (motif trouve %d fois)" % count))
            continue
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(original.replace(motif["old"], motif["new"]))
        run = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", shell=False)
        output = run.stdout + run.stderr
        # Toute la sortie, pour relire un verdict indetermine plutot que le deviner.
        log = "%s.sabotage-%d.log" % (motifs_path, len(results) + 1)
        with open(log, "w", encoding="utf-8") as handle:
            handle.write(output)
        if run.returncode == 0:
            verdict = "PASSE INAPERCU"
        elif "Compilation error" in output or "\ne: file:" in output:
            # Un sabotage qui ne compile pas ne mesure rien : ce n'est pas un test qui l'a vu.
            verdict = "NE COMPILE PAS (rien mesure)"
        elif "failing tests" in output or "tests completed" in output:
            verdict = "ATTRAPE (tests en echec)"
        elif "Requested array size exceeds VM limit" in output or "OutOfMemoryError" in output:
            # Le sabotage a retire une borne d'allocation : le test l'a atteinte et l'executeur est tombe.
            verdict = "ATTRAPE (l'executeur de tests tombe en OOM)"
        else:
            verdict = "ECHEC INDETERMINE : " + output.strip().splitlines()[-1][:120] if output.strip() else "ECHEC SANS SORTIE"
        results.append((motif["label"], verdict))
finally:
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(original)

with open(path, encoding="utf-8", newline="") as handle:
    restored = handle.read() == original
for label, verdict in results:
    print("%-60s %s" % (label, verdict))
print("fichier restaure :", restored)
stray = sorted(untracked() - before)
if stray:
    print("ATTENTION fichiers laisses dans le depot par un sabotage :", ", ".join(stray))
sys.exit(0 if restored and all(v.startswith("ATTRAPE") for _, v in results) else 1)
