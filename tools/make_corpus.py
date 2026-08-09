"""Jeu de fichiers pieges ciblant les correctifs du 2026-08-09.

Chaque fichier existe pour mettre en defaut UN correctif precis. Si l'un
d'eux fait planter, geler ou mal afficher l'application, le correctif
correspondant est incomplet.
"""
import io
import os
import struct
import zipfile

OUT = 'corpus'
os.makedirs(OUT, exist_ok=True)


def w(name, data, mode='wb'):
    p = os.path.join(OUT, name)
    with open(p, mode) as f:
        f.write(data)
    print('%-34s %8d o' % (name, os.path.getsize(p)))


# ---------------------------------------------------------------- 1. zip-bomb
# En-tete menteur : annonce 1 Ko, produit 80 Mo. Vise safeEntryBytes sur les
# chemins "apercu", "extraire tout" et "extraire et partager".
buf = io.BytesIO()
with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr('enorme.txt', b'A' * (80 * 1024 * 1024))
raw = bytearray(buf.getvalue())
# Reecrit la taille decompressee (u32 LE) dans les deux en-tetes.
for sig, off in ((0x04034B50, 18 + 4), (0x02014B50, 20 + 4)):
    i = 0
    while i + 4 <= len(raw):
        if struct.unpack_from('<I', raw, i)[0] == sig:
            struct.pack_into('<I', raw, i + off, 1024)
        i += 1
w('01_bombe_entete_menteur.zip', bytes(raw))

# ------------------------------------------------------- 2. archive corrompue
# Vise le LateInitializationError : ouvrir, puis toucher « Extraire tout ».
w('02_archive_corrompue.zip', b'PK\x03\x04' + os.urandom(4096))

# ------------------------------------------- 3. ODT a regex quadratique
odt_piege = '<?xml version="1.0"?><office:document-content>'
odt_piege += '<text:p>' * 30000  # jamais fermees
odt_piege += '</office:document-content>'
b = io.BytesIO()
with zipfile.ZipFile(b, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('mimetype', 'application/vnd.oasis.opendocument.text')
    z.writestr('content.xml', odt_piege)
w('03_odt_regex_quadratique.odt', b.getvalue())

# ------------------------------------------ 4. DOCX a regex quadratique
docx_piege = '<?xml version="1.0"?><w:document><w:body>'
docx_piege += '<w:t>' * 30000
docx_piege += '</w:body></w:document>'
b = io.BytesIO()
with zipfile.ZipFile(b, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('word/document.xml', docx_piege)
w('04_docx_regex_quadratique.docx', b.getvalue())

# -------------------------------------------- 5. ODT titres uniquement
# Avant correctif : entierement vide. Apres : les trois titres.
odt_titres = (
    '<?xml version="1.0"?><office:document-content><office:text>'
    '<text:h text:outline-level="1">Titre premier</text:h>'
    '<text:h text:outline-level="2">Sous-titre accentue : caf&#233;</text:h>'
    '<text:p>Un paragraphe avec &amp;lt; qui doit rester litteral.</text:p>'
    '<text:p>Ligne un<text:line-break/>Ligne deux</text:p>'
    '</office:text></office:document-content>'
)
b = io.BytesIO()
with zipfile.ZipFile(b, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('mimetype', 'application/vnd.oasis.opendocument.text')
    z.writestr('content.xml', odt_titres)
w('05_odt_titres_et_entites.odt', b.getvalue())

# ---------------------------------------------- 6. DOCX entites + sauts
docx_ok = (
    '<?xml version="1.0"?><w:document><w:body>'
    '<w:p><w:r><w:t>Le texte &amp;lt; doit rester litteral.</w:t></w:r></w:p>'
    '<w:p><w:r><w:t>Accents : caf&#233; cr&#xE8;me</w:t></w:r></w:p>'
    '<w:p><w:r><w:t>Avant</w:t><w:br/><w:t>Apres</w:t></w:r></w:p>'
    '</w:body></w:document>'
)
b = io.BytesIO()
with zipfile.ZipFile(b, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('word/document.xml', docx_ok)
w('06_docx_entites_et_sauts.docx', b.getvalue())

# ------------------------------------------------------ 7. .doc binaire (OLE)
w('07_vrai_doc_ole.doc', b'\xd0\xcf\x11\xe0' + os.urandom(2048))

# ----------------------------------------------------------- 8. zip-slip
b = io.BytesIO()
with zipfile.ZipFile(b, 'w') as z:
    z.writestr('../../../evade.txt', 'ne doit jamais atterrir hors du dossier')
    z.writestr('normal.txt', 'celui-ci est legitime')
w('08_zip_slip.zip', b.getvalue())

# -------------------------------------------------- 9. HTML faux head + CSP
w(
    '09_html_faux_head.html',
    b'<!DOCTYPE html PUBLIC "a>b">\n'
    b'<!-- <head> commentaire trompeur </head> -->\n'
    b'<html><head><title>Test CSP</title></head><body>\n'
    b'<h1>Si vous lisez ceci sans image distante, la CSP tient</h1>\n'
    b'<img src="https://example.invalid/pixel.png" alt="pixel distant">\n'
    b'<iframe src="https://example.invalid/"></iframe>\n'
    b'<script>document.body.innerHTML="<h1>JS EXECUTE - DEFAUT</h1>";</script>\n'
    b'</body></html>\n',
)

# ------------------------------------------- 10. Markdown image distante
w(
    '10_md_image_distante.md',
    '# Test pixel espion\n\n'
    'Une image **distante** ne doit PAS etre chargee :\n\n'
    '![pixel](https://example.invalid/pixel.png)\n\n'
    'Le reste du document doit s\'afficher normalement.\n'.encode('utf-8'),
)

# -------------------------------------------------------- 11. CSV injection
w(
    '11_csv_injection.csv',
    'nom,formule\n'
    'test,=1+1\n'
    'test2,+SOMME(A1:A9)\n'
    'test3,-2+3\n'
    'test4,@SUM(1)\n'.encode('utf-8'),
)

# ------------------------------------------------------- 12. fichiers sains
w('12_note.txt', 'Fichier texte parfaitement normal.\nDeuxieme ligne.\n'.encode('utf-8'))
w('13_table.csv', 'a,b,c\n1,2,3\n4,5,6\n'.encode('utf-8'))

print('\ncorpus complet dans ./%s' % OUT)
