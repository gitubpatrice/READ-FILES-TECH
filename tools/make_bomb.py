"""Bombe zip correctement calibree.

La premiere version produisait 80 Mo, sous le plafond de 200 Mo par entree :
l'extraction aurait reussi et n'aurait donc rien prouve. Celle-ci produit
300 Mo, franchement au-dessus, tout en pesant quelques centaines de kilo-octets
sur le disque et en ANNONCANT 1 Ko dans ses deux en-tetes.

Trois choses a la fois :
  - la garde doit refuser l'entree malgre l'en-tete menteur ;
  - elle doit le faire SANS allouer les 300 Mo (sinon le S9 meurt par OOM) ;
  - l'application doit rester vivante et le dire.
"""
import io
import os
import struct
import zipfile

TAILLE = 300 * 1024 * 1024  # 300 Mo, au-dela du plafond de 200 Mo
ANNONCE = 1024              # ce que l'en-tete pretendra

buf = io.BytesIO()
with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    # Ecriture par blocs pour ne pas materialiser 300 Mo en RAM ici non plus.
    with z.open('bombe.bin', 'w') as f:
        bloc = b'\0' * (4 * 1024 * 1024)
        reste = TAILLE
        while reste > 0:
            n = min(len(bloc), reste)
            f.write(bloc[:n])
            reste -= n

raw = bytearray(buf.getvalue())

# `uncompressed size` est un u32 little-endian :
#   local file header : signature 0x04034B50, champ a +22
#   central directory : signature 0x02014B50, champ a +24
patches = 0
for sig, off in ((0x04034B50, 22), (0x02014B50, 24)):
    i = 0
    while i + 4 <= len(raw):
        if struct.unpack_from('<I', raw, i)[0] == sig:
            cur = struct.unpack_from('<I', raw, i + off)[0]
            if cur == TAILLE:
                struct.pack_into('<I', raw, i + off, ANNONCE)
                patches += 1
        i += 1

os.makedirs('corpus2', exist_ok=True)
p = os.path.join('corpus2', '01_bombe_300Mo.zip')
with open(p, 'wb') as f:
    f.write(bytes(raw))

print('champs reecrits      : %d (attendu 2)' % patches)
print('taille sur disque    : %d o' % os.path.getsize(p))
print('taille annoncee      : %d o' % ANNONCE)
print('taille reelle produite: %d o (%d Mo)' % (TAILLE, TAILLE // 1024 // 1024))

# Controle : ce que Python lit dans l'en-tete doit etre le mensonge.
with zipfile.ZipFile(p) as z:
    info = z.getinfo('bombe.bin')
    print('lu par zipfile       : file_size=%d compress_size=%d'
          % (info.file_size, info.compress_size))
    assert info.file_size == ANNONCE, 'le mensonge n a pas pris'
print('OK : l en-tete ment bien.')
