#!/usr/bin/env python3
"""Fichiers pieges pour les correctifs du 2026-08-11.

Chacun vise UN defaut corrige ce jour-la. Le nom porte l'attendu, pour qu'un
essai sur appareil se lise sans revenir au code.

Deploiement :
    python tools/make_corpus_v216.py
    adb push <sortie>/. /sdcard/Download/rft_test_v216/
"""

import os
import struct
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else 'corpus_v216'
os.makedirs(OUT, exist_ok=True)


def w(name, data):
    path = os.path.join(OUT, name)
    mode = 'wb' if isinstance(data, bytes) else 'w'
    kwargs = {} if isinstance(data, bytes) else {'encoding': 'utf-8'}
    with open(path, mode, **kwargs) as f:
        f.write(data)
    print('%-46s %8d o' % (name, os.path.getsize(path)))


# ── 1. HTML : DOCTYPE jamais referme ────────────────────────────────────────
# Avant le correctif, le balayage du DOCTYPE s'arretait sur le `>` de la balise
# SUIVANTE : la CSP etait posee APRES l'image distante et la requete partait.
# Attendu : la page s'affiche, l'image ne charge pas, aucune requete sortante.
w('01_html_doctype_non_ferme.html',
  '<!doctype html <img src="https://example.invalid/pixel.png">\n'
  '<h1>DOCTYPE non ferme</h1>\n'
  '<p>Si vous lisez ceci sans image cassee distante, la CSP a ete posee '
  'en tete comme il faut.</p>\n')

# ── 2. HTML : CSP concurrente permissive ────────────────────────────────────
# Plusieurs CSP se cumulent (intersection). Celle du document ne doit pas
# desserrer la notre.
w('02_html_csp_concurrente.html',
  '<!doctype html><html><head>'
  '<meta http-equiv="Content-Security-Policy" content="default-src *">'
  '<img src="https://example.invalid/pixel.png">'
  '</head><body><h1>CSP concurrente</h1>'
  '<p>L\'image distante doit rester bloquee.</p></body></html>')

# ── 3. GIF de 10 octets annoncant 65535x65535 ───────────────────────────────
# Le seuil `bytes.length < 24` etait global : ce fichier n'etait jamais
# inspecte. Attendu : refus « Dimensions image suspectes », pas un plantage.
gif = bytearray(b'GIF89a')
gif += struct.pack('<HH', 65535, 65535)
w('03_gif_10o_65535x65535.gif', bytes(gif))

# ── 4. BMP annoncant 50000x50000 ────────────────────────────────────────────
# `ImageBounds` ne connaissait ni le BMP ni le WebP : la garde se contournait
# en changeant de format.
bmp = bytearray(b'BM' + b'\x00' * 24)
bmp[18:22] = struct.pack('<i', 50000)
bmp[22:26] = struct.pack('<i', 50000)
w('04_bmp_50000x50000.bmp', bytes(bmp))

# ── 5. WebP annoncant 16383x16383 ───────────────────────────────────────────
webp = bytearray(b'RIFF' + b'\x00' * 4 + b'WEBP' + b'VP8 ' + b'\x00' * 14)
webp[26] = 16383 & 0xFF
webp[27] = (16383 >> 8) & 0x3F
webp[28] = 16383 & 0xFF
webp[29] = (16383 >> 8) & 0x3F
w('05_webp_16383x16383.webp', bytes(webp))

# ── 6. CSV : formule precedee d'une espace ──────────────────────────────────
# `sanitizeCell` n'examinait que le PREMIER caractere. Ce fichier sert a
# l'IMPORT : ouvrir puis reexporter, et verifier que la formule ressort
# prefixee d'une apostrophe.
w('06_csv_formule_espace.csv',
  'nom,valeur\n'
  'normal,bonjour\n'
  'espace_devant," =1+1"\n'
  'saut_de_ligne,"\n=HYPERLINK(""http://x"")"\n'
  'tabulation,"\t@SUM(A1)"\n')

# ── 7. Corbeille : metadonnee valide mais enorme ────────────────────────────
# A DEPOSER A LA MAIN dans .RFT_Corbeille/meta/ pour reproduire. Sans plafond,
# l'ouverture de la corbeille figeait.
w('07_meta_enorme_A_DEPOSER_DANS_meta.json',
  '{"id":"99999999999999","originalPath":"/sdcard/Download/x.txt",'
  '"name":"x.txt","isDir":false,"size":1,'
  '"deletedAt":"2026-08-11T00:00:00.000","bourrage":"'
  + 'A' * (2 * 1024 * 1024) + '"}')

# ── 8. Recherche par contenu : temoins ──────────────────────────────────────
# Une recherche de « chercher-ceci » ne doit rendre QUE le fichier 08b.
# Avant le correctif, elle rendait tous les fichiers du dossier.
w('08a_sans_le_motif.txt', 'ce fichier ne contient pas le motif\n')
w('08b_avec_le_motif.txt', 'ligne 1\nchercher-ceci est ici\nligne 3\n')
w('08c_sans_le_motif_non_plus.txt', 'rien a signaler\n')

print()
print('Depose dans : %s' % os.path.abspath(OUT))
